package chat.ai;

import chat.concurrent.BoundedQueue;
import chat.room.Room;
import chat.room.RoomKind;
import chat.room.RoomMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
 *   <li>Every non-system message still retained by the room's bounded
 *       history is sent to Ollama, matching the assignment's whole-context
 *       requirement while keeping memory usage bounded.</li>
 *   <li>Ollama errors post a short system notice instead of stopping the room
 *       worker.</li>
 * </ul>
 */
public final class AIRoom extends Room {
    /** Special author name used for LLM replies. */
    public static final String BOT_AUTHOR = "Bot";

    /** Maximum characters of a single bot reply posted to the room. */
    private static final int MAX_REPLY_LENGTH = 4096;

    /** Maximum pending AI work items (prevents unbounded memory growth). */
    private static final int WORK_QUEUE_CAPACITY = 64;

    private final String systemPrompt;
    private final OllamaClient ollama;

    /** One shared queue implementation is enough for both session and AI work. */
    private final BoundedQueue<Runnable> workQueue = new BoundedQueue<>(WORK_QUEUE_CAPACITY);
    private final Thread worker;
    private volatile boolean shutdown;

    public AIRoom(String name, String systemPrompt, OllamaClient ollama) {
        super(name, RoomKind.AI);
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
        this.ollama = Objects.requireNonNull(ollama, "ollama");
        worker = Thread.ofVirtual()
                .name("ai-room-worker-" + name())
                .start(this::workerLoop);
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

        if (!enqueueWork(this::processAIRequest)) {
            // Overload is visible to users instead of silently losing a Bot
            // request that can never receive a reply.
            postSystemMessage("Bot is overloaded; please try again later");
        }
    }

    // ── sequential worker ──────────────────────────────────────────────

    private void workerLoop() {
        while (!shutdown) {
            try {
                workQueue.take().run();
            } catch (InterruptedException e) {
                if (!shutdown) Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                System.err.println("[ai-room " + name() + "] worker error: " + e.getMessage());
            }
        }
    }

    private boolean enqueueWork(Runnable task) {
        return !shutdown && workQueue.offer(task);
    }

    /**
     * Shuts down the AI worker.  Called if the room is ever removed
     * (currently rooms are permanent, but this is good hygiene).
     */
    public void shutdown() {
        shutdown = true;
        worker.interrupt();
    }

    // ── AI processing ──────────────────────────────────────────────────

    private void processAIRequest() {
        // Build context at execution time, not enqueue time, so each sequential
        // request sees the newest retained room timeline.
        List<OllamaClient.ChatMessage> context = buildContext();
        if (context.isEmpty()) return;

        try {
            String reply = ollama.chat(systemPrompt, context);
            if (reply == null || reply.isBlank()) return;
            reply = sanitizeReply(reply);
            if (reply.isEmpty()) return;

            postUserMessage(BOT_AUTHOR, reply);
        } catch (IOException e) {
            System.err.println("[ai-room " + name() + "] Ollama error: " + e.getMessage());
            postSystemMessage("Bot is temporarily unavailable");
        }
    }

    private List<OllamaClient.ChatMessage> buildContext() {
        // The assignment requests whole context. Room history is itself bounded,
        // so forwarding its complete retained non-system suffix is safe.
        List<RoomMessage> recent = recentHistory();

        List<OllamaClient.ChatMessage> context = new ArrayList<>(recent.size());
        for (RoomMessage msg : recent) {
            if (msg.system()) continue;
            boolean isBot = BOT_AUTHOR.equals(msg.author());
            context.add(new OllamaClient.ChatMessage(
                    isBot ? "assistant" : "user",
                    isBot ? msg.text() : msg.author() + ": " + msg.text()));
        }

        return context;
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
}
