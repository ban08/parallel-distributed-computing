package chat.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Minimal Ollama REST client using only Java SE classes.
 *
 * Calls POST /api/chat with {@code stream: false} and parses the JSON response
 * with a hand-written recursive-descent parser that correctly handles escaped
 * quotes, unicode escapes, and nested structures. Keeping this adapter inside
 * Java SE avoids adding a JSON or HTTP dependency to the assignment.
 */
public final class OllamaClient {
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 120_000; // LLMs can be slow
    private static final int MAX_GENERATE_TOKENS = 512;  // cap reply length

    private final String baseUrl;
    private final String model;

    public OllamaClient(String baseUrl, String model) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(model, "model");
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
    }

    public String baseUrl() { return baseUrl; }
    public String model() { return model; }

    /**
     * Sends a chat completion request and returns the assistant's reply text.
     *
     * @param systemPrompt the system prompt (may be null)
     * @param messages     conversation history as (role, content) pairs
     * @return the assistant's reply content
     * @throws IOException on network or parsing errors
     */
    public String chat(String systemPrompt, List<ChatMessage> messages) throws IOException {
        String json = buildRequestJson(systemPrompt, messages);
        String response = post("/api/chat", json);
        return extractContent(response);
    }

    // ── request builder ────────────────────────────────────────────────

    private String buildRequestJson(String systemPrompt, List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder(256 + messages.size() * 200);
        sb.append("{\"model\":").append(jsonString(model));
        // Non-streaming mode gives one complete JSON object, which keeps the
        // small response parser and room worker lifecycle straightforward.
        sb.append(",\"stream\":false");
        sb.append(",\"options\":{\"num_predict\":").append(MAX_GENERATE_TOKENS).append('}');
        sb.append(",\"messages\":[");

        boolean first = true;
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            sb.append(chatMessageJson("system", systemPrompt));
            first = false;
        }
        for (ChatMessage m : messages) {
            if (!first) sb.append(',');
            sb.append(chatMessageJson(m.role(), m.content()));
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String chatMessageJson(String role, String content) {
        return "{\"role\":" + jsonString(role) + ",\"content\":" + jsonString(content) + "}";
    }

    // ── HTTP transport ─────────────────────────────────────────────────

    private String post(String path, String body) throws IOException {
        URL url = URI.create(baseUrl + path).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            // Fixed length lets HttpURLConnection send an ordinary request body
            // without chunked transfer encoding.
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }

            int status = conn.getResponseCode();
            if (status != 200) {
                var errorStream = conn.getErrorStream();
                try (BufferedReader errorReader = errorStream == null
                        ? null
                        : new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                    throw new IOException("Ollama HTTP " + status + ": " + readStream(errorReader));
                }
            }

            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                return readStream(in);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String readStream(BufferedReader reader) throws IOException {
        if (reader == null) return "";
        StringBuilder sb = new StringBuilder(1024);
        char[] buf = new char[1024];
        int n;
        while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
        return sb.toString();
    }

    // ── JSON string encoding ───────────────────────────────────────────

    private static String jsonString(String value) {
        if (value == null) return "null";
        StringBuilder sb = new StringBuilder(value.length() + 8);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u").append(String.format("%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // ── Robust JSON response parser ────────────────────────────────────
    //
    // Extracts the value at path  message -> content  from the Ollama
    // /api/chat response.  Uses a proper recursive-descent approach that
    // handles escaped quotes, unicode escapes, nested objects, arrays,
    // numbers, booleans, and nulls.

    private static String extractContent(String json) throws IOException {
        try {
            JsonParser parser = new JsonParser(json);
            return parser.extractMessageContent();
        } catch (JsonParser.ParseException e) {
            throw new IOException("Failed to parse Ollama response: " + e.getMessage(), e);
        }
    }

    /**
     * Minimal recursive-descent JSON parser. Only extracts the string value
     * at {@code $.message.content} but correctly skips over arbitrary JSON
     * structures so it is not tripped by escaped characters or nesting.
     */
    private static final class JsonParser {
        private final String src;
        private int pos;

        JsonParser(String src) {
            this.src = Objects.requireNonNull(src);
            this.pos = 0;
        }

        String extractMessageContent() throws ParseException {
            skipWhitespace();
            expect('{');
            String content = null;
            boolean first = true;
            while (true) {
                skipWhitespace();
                if (peek() == '}') { pos++; break; }
                if (!first) { expect(','); skipWhitespace(); }
                first = false;
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                if ("message".equals(key)) {
                    // Ollama replies place assistant text at $.message.content.
                    content = parseMessageObject();
                } else {
                    // Ignore model metadata while still validating its JSON shape.
                    skipValue();
                }
            }
            if (content == null) throw new ParseException("missing 'message' field in response");
            return content;
        }

        private String parseMessageObject() throws ParseException {
            expect('{');
            String content = null;
            boolean first = true;
            while (true) {
                skipWhitespace();
                if (peek() == '}') { pos++; break; }
                if (!first) { expect(','); skipWhitespace(); }
                first = false;
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                if ("content".equals(key)) {
                    content = readString();
                } else {
                    skipValue();
                }
            }
            if (content == null) throw new ParseException("missing 'content' in message object");
            return content;
        }

        // ── primitives ────────────────────────────────────────────────

        private String readString() throws ParseException {
            skipWhitespace();
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= src.length()) throw new ParseException("unexpected end of escape");
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"'  -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/'  -> sb.append('/');
                        case 'b'  -> sb.append('\b');
                        case 'f'  -> sb.append('\f');
                        case 'n'  -> sb.append('\n');
                        case 'r'  -> sb.append('\r');
                        case 't'  -> sb.append('\t');
                        case 'u'  -> {
                            if (pos + 4 > src.length()) throw new ParseException("incomplete unicode escape");
                            int cp;
                            try {
                                cp = Integer.parseInt(src.substring(pos, pos + 4), 16);
                            } catch (NumberFormatException e) {
                                throw new ParseException("invalid unicode escape");
                            }
                            sb.append((char) cp);
                            pos += 4;
                        }
                        default -> throw new ParseException("invalid escape '\\" + esc + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new ParseException("unterminated string");
        }

        private void skipValue() throws ParseException {
            skipWhitespace();
            char c = peek();
            switch (c) {
                case '"' -> readString();
                case '{' -> skipObject();
                case '[' -> skipArray();
                case 't', 'f' -> skipLiteral();
                case 'n' -> skipLiteral();
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) skipNumber();
                    else throw new ParseException("unexpected char '" + c + "' at pos " + pos);
                }
            }
        }

        private void skipObject() throws ParseException {
            expect('{');
            boolean first = true;
            while (true) {
                skipWhitespace();
                if (peek() == '}') { pos++; return; }
                if (!first) { expect(','); skipWhitespace(); }
                first = false;
                readString(); // key
                skipWhitespace();
                expect(':');
                skipValue();
            }
        }

        private void skipArray() throws ParseException {
            expect('[');
            boolean first = true;
            while (true) {
                skipWhitespace();
                if (peek() == ']') { pos++; return; }
                if (!first) { expect(','); skipWhitespace(); }
                first = false;
                skipValue();
            }
        }

        private void skipNumber() {
            if (pos < src.length() && src.charAt(pos) == '-') pos++;
            while (pos < src.length() && isDigit(src.charAt(pos))) pos++;
            if (pos < src.length() && src.charAt(pos) == '.') {
                pos++;
                while (pos < src.length() && isDigit(src.charAt(pos))) pos++;
            }
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
                while (pos < src.length() && isDigit(src.charAt(pos))) pos++;
            }
        }

        private void skipLiteral() {
            while (pos < src.length() && Character.isLetter(src.charAt(pos))) pos++;
        }

        // ── helpers ───────────────────────────────────────────────────

        private void skipWhitespace() {
            while (pos < src.length() && src.charAt(pos) <= ' ') pos++;
        }

        private char peek() throws ParseException {
            if (pos >= src.length()) throw new ParseException("unexpected end of JSON");
            return src.charAt(pos);
        }

        private void expect(char expected) throws ParseException {
            char actual = peek();
            if (actual != expected) {
                throw new ParseException("expected '" + expected + "' but got '" + actual + "' at pos " + pos);
            }
            pos++;
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        @SuppressWarnings("serial")
        static final class ParseException extends Exception {
            ParseException(String msg) { super(msg); }
        }
    }

    /** Immutable chat message. */
    public record ChatMessage(String role, String content) {
        public ChatMessage {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(content, "content");
        }
    }
}
