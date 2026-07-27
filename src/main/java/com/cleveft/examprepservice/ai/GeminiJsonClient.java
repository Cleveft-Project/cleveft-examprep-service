package com.cleveft.examprepservice.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls Google GenAI and parses a JSON object out of the reply.
 *
 * <p>Everything this service asks the model for — quizzes, exam summaries — is
 * structured data, never prose, so JSON parsing is built into the call rather
 * than repeated at each site.
 */
@Component
public class GeminiJsonClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiJsonClient.class);
    private static final String API_KEY_HEADER = "x-goog-api-key";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    public GeminiJsonClient(
            ObjectMapper objectMapper,
            @Value("${cleveft.gemini.api-key:}") String apiKey,
            @Value("${cleveft.gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl,
            @Value("${cleveft.gemini.model:gemini-3.5-flash}") String model,
            @Value("${cleveft.gemini.timeout-ms:120000}") int timeoutMs) {

        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(timeoutMs);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public JsonNode generateJson(String systemInstruction, String userPrompt) {
        if (!isConfigured()) {
            throw new AiServiceException(
                    "GOOGLE_API_KEY is not configured, so Cleveft cannot generate quizzes yet.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", userPrompt)))));
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction))));
        body.put("generationConfig", Map.of(
                "temperature", 0.4,
                "maxOutputTokens", 16384,
                // Ask the API itself for JSON rather than relying on the prompt
                // alone; it removes an entire class of parse failure.
                "responseMimeType", "application/json"));

        try {
            JsonNode response = restClient.post()
                    .uri("/v1beta/models/" + model + ":generateContent")
                    .header(API_KEY_HEADER, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            return parse(extractText(response));

        } catch (RestClientException e) {
            log.error("Generation request failed", e);
            throw new AiServiceException("Cleveft could not reach the AI model. Please try again.", e);
        }
    }

    private String extractText(JsonNode response) {
        if (response == null) {
            throw new AiServiceException("The AI model returned an empty response.");
        }

        JsonNode candidates = response.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            throw new AiServiceException("The AI model returned no result.");
        }

        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidates.get(0).path("content").path("parts")) {
            if (part.hasNonNull("text")) {
                text.append(part.get("text").asText());
            }
        }

        if (text.isEmpty()) {
            throw new AiServiceException("The AI model returned an empty result.");
        }
        return text.toString();
    }

    private JsonNode parse(String raw) {
        try {
            return objectMapper.readTree(stripFences(raw));
        } catch (Exception e) {
            log.error("Model returned unparseable JSON: {}", abbreviate(raw));
            throw new AiServiceException("The AI model returned a malformed result. Please try again.");
        }
    }

    private String stripFences(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private static String abbreviate(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500) + "…";
    }
}
