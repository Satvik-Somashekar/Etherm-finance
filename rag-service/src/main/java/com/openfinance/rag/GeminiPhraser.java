package com.openfinance.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openfinance.core.compute.ComputedAnswer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Same contract as {@link ClaudePhraser} with Gemini as the model: it is told what each placeholder
 * means but never a value, and its sentence is rejected (template used instead) if it contains a
 * digit of its own or an unknown placeholder. The key is read from the environment by the caller.
 */
public class GeminiPhraser implements AnswerPhraser {

    private static final Logger log = LoggerFactory.getLogger(GeminiPhraser.class);
    static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final TemplatePhraser fallback;

    public GeminiPhraser(String baseUrl, String apiKey, String model, TemplatePhraser fallback) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.fallback = fallback;
    }

    @Override
    public Phrasing phrase(String question, ComputedAnswer answer) {
        if (answer.empty()) {
            return fallback.phrase(question, answer);
        }
        FactSheet facts = FactSheet.of(answer);
        try {
            String draft = complete(ClaudePhraser.systemFor(answer), ClaudePhraser.userMessage(question, facts, answer));
            if (draft != null && ClaudePhraser.isSafe(draft, facts, answer)) {
                return new Phrasing(facts.fill(draft), model);
            }
            // Never the draft itself: it describes a customer's money.
            log.warn("Model phrasing rejected, using template");
        } catch (IOException e) {
            log.warn("Model phrasing failed ({}), using template", e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return fallback.phrase(question, answer);
    }

    private String complete(String system, String userMessage) throws IOException, InterruptedException {
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", java.util.List.of(Map.of("text", system))),
                "contents", java.util.List.of(Map.of("role", "user", "parts", java.util.List.of(Map.of("text", userMessage)))),
                "generationConfig", Map.of("maxOutputTokens", 1024, "temperature", 0));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1beta/models/" + model + ":generateContent"))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            // The body of an error can echo request details, so only the status is logged.
            throw new IOException("Gemini returned HTTP " + response.statusCode());
        }
        JsonNode parts = mapper.readTree(response.body()).at("/candidates/0/content/parts");
        StringBuilder text = new StringBuilder();
        parts.forEach(p -> text.append(p.path("text").asText("")));
        return text.toString().trim();
    }
}
