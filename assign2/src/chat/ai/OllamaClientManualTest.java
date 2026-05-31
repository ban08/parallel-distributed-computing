package chat.ai;

import java.io.IOException;

/** Executable checks for Ollama JSON encoding and response parsing. */
public final class OllamaClientManualTest {
    public static void main(String[] args) throws Exception {
        expect(OllamaClient.extractContent("{\"message\":{\"content\":\"hello\"}}"), "hello");
        expect(OllamaClient.extractContent(
                "{\"model\":\"llama3\",\"message\":{\"role\":\"assistant\",\"content\":\"line\\n\\u263a\"},\"done\":true}"),
                "line\n\u263a");
        expect(OllamaClient.jsonString("quote \" slash \\ newline\n"), "\"quote \\\" slash \\\\ newline\\n\"");
        expectParseFailure("{\"message\":{\"content\":\"\\uZZZZ\"}}");
        expectParseFailure("{\"message\":{\"content\":\"\\q\"}}");

        System.out.println("PASS OllamaClientManualTest");
    }

    private static void expect(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private static void expectParseFailure(String json) throws Exception {
        try {
            OllamaClient.extractContent(json);
            throw new AssertionError("expected parse failure for " + json);
        } catch (IOException expected) {
            // expected path
        }
    }

    private OllamaClientManualTest() {}
}
