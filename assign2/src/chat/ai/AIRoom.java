package chat.ai;

import chat.room.Room;
import chat.room.RoomKind;
import chat.room.RoomMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * AI-powered chat room backed by a local Ollama LLM.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>Extends {@link Room} and overrides {@link #afterUserMessage} — the
 *       hook is called <em>after</em> the room lock has been released and
 *       the message has been broadcast, so we never hold room locks while
 *       waiting for Ollama.</li>
 *   <li>A dedicated virtual-thread worker consumes a bounded work queue so
 *       AI requests are processed <strong>sequentially</strong> per room.
 *       This avoids race conditions where two concurrent requests see the
 *       same history and produce duplicate or out-of-order Bot replies.</li>
 *   <li>Bot messages ({@code author == "Bot"}) <strong>never</strong>
 *       trigger new AI calls, preventing infinite response loops.</li>
 *   <li>Only the last {@value #CONTEXT_MESSAGE_LIMIT} non-system messages
 *       are sent to Ollama so token usage stays bounded.</li>
 *   <li>Consecutive Ollama errors trigger an exponential backoff; the room
 *       posts a single error notice and silently drops further requests
 *       until the backoff period expires, avoiding error spam.</li>
 * </ul>
 */
public final class AIRoom extends Room {
    /** Special author name used for LLM replies. */
    public static final String BOT_AUTHOR = "Bot";

    /** Maximum non-system messages included in the Ollama context window. */
    static final int CONTEXT_MESSAGE_LIMIT = 15;

    /** Maximum characters of a single bot reply posted to the room. */
    private static final int MAX_REPLY_LENGTH = 4096;

    /** Maximum pending AI work items (prevents unbounded memory growth). */
    private static final int WORK_QUEUE_CAPACITY = 64;

    /** Backoff parameters for consecutive Ollama errors. */
    private static final long INITIAL_BACKOFF_MS = 5_000;
    private static final long MAX_BACKOFF_MS = 120_000;

    private final String systemPrompt;
    private final OllamaClient ollama;

    /*
     * Sequential work queue.  A single virtual-thread worker drains this
     * queue and invokes Ollama for each item.  The BoundedQueue from the
     * concurrent package would work too, but since we only need simple
     * offer/take semantics and the worker is always a single thread, a
     * plain ArrayDeque + ReentrantLock + Condition is cleaner.
     */
    private final ReentrantLock workLock = new ReentrantLock();
    private final java.util.concurrent.locks.Condition workAvailable = workLock.newCondition();
    private final java.util.ArrayDeque<Runnable> workQueue = new java.util.ArrayDeque<>();
    private int workQueueSize;
    private volatile boolean shutdown;

    /* Error backoff state — guarded by workLock (accessed only by worker). */
    private int consecutiveErrors;
    private long backoffUntilMillis;

    public AIRoom(String name, String systemPrompt, OllamaClient ollama) {
        super(name, RoomKind.AI);
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
        this.ollama = Objects.requireNonNull(ollama, "ollama");
        startWorker();
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    // ── afterUserMessage hook ──────────────────────────────────────────
    //
    // Called by Room.postUserMessage AFTER the room lock is released and
    // the message has been broadcast to subscribers.  We enqueue a work
    // item for the AI worker — never block here.

    @Override
    protected void afterUserMessage(RoomMessage message) {
        // Prevent Bot-reply loops: messages authored by "Bot" must not
        // trigger another AI call.
        if (BOT_AUTHOR.equals(message.author())) return;

        enqueueWork(() -> processAIRequest());
    }

    // ── sequential worker ──────────────────────────────────────────────

    private void startWorker() {
        Thread.ofVirtual()
                .name("ai-room-worker-" + name())
                .start(this::workerLoop);
    }

    private void workerLoop() {
        while (!shutdown) {
            Runnable task;
            workLock.lock();
            try {
                while (workQueueSize == 0 && !shutdown) {
                    workAvailable.awaitUninterruptibly();
                }
                if (shutdown) return;
                task = workQueue.removeFirst();
                workQueueSize--;
            } finally {
                workLock.unlock();
            }

            try {
                task.run();
            } catch (RuntimeException e) {
                System.err.println("[ai-room " + name() + "] worker error: " + e.getMessage());
            }
        }
    }

    private void enqueueWork(Runnable task) {
        workLock.lock();
        try {
            if (shutdown) return;
            if (workQueueSize >= WORK_QUEUE_CAPACITY) return; // drop if overloaded
            workQueue.addLast(task);
            workQueueSize++;
            workAvailable.signal();
        } finally {
            workLock.unlock();
        }
    }

    /**
     * Shuts down the AI worker.  Called if the room is ever removed
     * (currently rooms are permanent, but this is good hygiene).
     */
    public void shutdown() {
        workLock.lock();
        try {
            shutdown = true;
            workAvailable.signalAll();
        } finally {
            workLock.unlock();
        }
    }

    // ── AI processing ──────────────────────────────────────────────────

    private void processAIRequest() {
        // Check error backoff
        if (System.currentTimeMillis() < backoffUntilMillis) {
            return; // silently skip — error notice was already posted
        }

        List<OllamaClient.ChatMessage> context = buildContext();
        if (context.isEmpty()) return;

        try {
            String reply = ollama.chat(systemPrompt, context);
            consecutiveErrors = 0;
            backoffUntilMillis = 0;

            if (reply == null || reply.isBlank()) return;
            reply = sanitizeReply(reply);
            if (reply.isEmpty()) return;

            postUserMessage(BOT_AUTHOR, reply);
        } catch (IOException e) {
            handleOllamaError(e);
        }
    }

    private List<OllamaClient.ChatMessage> buildContext() {
        // Fetch only a small tail of history.  We ask for a few extra to
        // compensate for system messages that will be skipped, but we
        // never pull the full 200-entry history buffer.
        int fetch = CONTEXT_MESSAGE_LIMIT + 10; // headroom for system msgs
        List<RoomMessage> recent = recentHistory(fetch);

        List<OllamaClient.ChatMessage> context = new ArrayList<>(CONTEXT_MESSAGE_LIMIT);
        for (int i = recent.size() - 1; i >= 0 && context.size() < CONTEXT_MESSAGE_LIMIT; i--) {
            RoomMessage msg = recent.get(i);
            if (msg.system()) continue;
            boolean isBot = BOT_AUTHOR.equals(msg.author());
            context.add(new OllamaClient.ChatMessage(
                    isBot ? "assistant" : "user",
                    isBot ? msg.text() : msg.author() + ": " + msg.text()));
        }

        // Reverse so oldest is first (Ollama expects chronological order)
        java.util.Collections.reverse(context);
        return context;
    }

    private void handleOllamaError(IOException e) {
        consecutiveErrors++;
        long backoff = Math.min(INITIAL_BACKOFF_MS * (1L << (consecutiveErrors - 1)), MAX_BACKOFF_MS);
        backoffUntilMillis = System.currentTimeMillis() + backoff;

        String notice = "Bot is temporarily unavailable (" + briefError(e) + ")";
        System.err.println("[ai-room " + name() + "] Ollama error #" + consecutiveErrors
                + ", backoff " + backoff + "ms: " + e.getMessage());

        // Only post error notice on first failure or every 5th consecutive failure
        if (consecutiveErrors == 1 || consecutiveErrors % 5 == 0) {
            try {
                postSystemMessage(notice);
            } catch (RuntimeException ignored) {
                // If posting fails too, there's nothing more we can do.
            }
        }
    }

    private static String sanitizeReply(String reply) {
        // Replace newlines with spaces — our frame protocol is newline-delimited.
        // Also trim and cap length.
        String cleaned = reply.replace('\n', ' ').replace('\r', ' ').trim();
        if (cleaned.length() > MAX_REPLY_LENGTH) {
            cleaned = cleaned.substring(0, MAX_REPLY_LENGTH) + "...";
        }
        return cleaned;
    }

    private static String briefError(IOException e) {
        String msg = e.getMessage();
        if (msg == null) return "unknown error";
        if (msg.length() > 80) return msg.substring(0, 80) + "...";
        return msg;
    }
}
