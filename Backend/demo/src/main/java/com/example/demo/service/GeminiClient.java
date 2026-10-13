package com.example.demo.service;

import com.example.demo.config.GeminiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin client over the Google Gemini REST API (generativelanguage.googleapis.com).
 * Handles text embeddings (:embedContent / :batchEmbedContents) and content
 * generation (:generateContent) using the configured API key.
 */
@Service
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public GeminiClient(GeminiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrlOrDefault())
                .build();
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    public String chatModel() {
        return properties.chatModelOrDefault();
    }

    public String embeddingModel() {
        return properties.embeddingModelOrDefault();
    }

    public double[] embed(String text) {
        requireKey();
        Map<String, Object> body = Map.of(
                "model", "models/" + properties.embeddingModelOrDefault(),
                "content", Map.of("parts", List.of(Map.of("text", text))));
        try {
            JsonNode response = restClient.post()
                    .uri("/models/{model}:embedContent?key={key}", properties.embeddingModelOrDefault(), properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return extractEmbedding(response);
        } catch (Exception ex) {
            throw new GeminiUnavailableException("Gemini embedding request failed: " + ex.getMessage(), ex);
        }
    }

    public List<double[]> embedBatch(List<String> texts) {
        requireKey();
        if (texts.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> requests = texts.stream()
                .map(t -> Map.<String, Object>of(
                        "model", "models/" + properties.embeddingModelOrDefault(),
                        "content", Map.of("parts", List.of(Map.of("text", t)))))
                .toList();
        Map<String, Object> body = Map.of("requests", requests);
        try {
            JsonNode response = restClient.post()
                    .uri("/models/{model}:batchEmbedContents?key={key}", properties.embeddingModelOrDefault(), properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            List<double[]> results = new ArrayList<>();
            JsonNode embeddings = response.get("embeddings");
            if (embeddings != null) {
                for (JsonNode node : embeddings) {
                    results.add(extractEmbedding(node));
                }
            }
            if (results.size() != texts.size()) {
                throw new GeminiUnavailableException(
                        "Gemini returned " + results.size() + " embeddings for " + texts.size() + " inputs");
            }
            return results;
        } catch (GeminiUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new GeminiUnavailableException("Gemini batch embedding request failed: " + ex.getMessage(), ex);
        }
    }

    private static final int MAX_ATTEMPTS_PER_MODEL = 2;

    public String generate(String systemInstruction, String userPrompt) {
        requireKey();
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction))),
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", userPrompt)))),
                "generationConfig", Map.of("temperature", 0.2, "maxOutputTokens", 1024));

        GeminiUnavailableException last = null;
        for (String model : properties.chatModelChain()) {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS_PER_MODEL; attempt++) {
                try {
                    JsonNode response = restClient.post()
                            .uri("/models/{model}:generateContent?key={key}", model, properties.apiKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .body(JsonNode.class);
                    return extractText(response);
                } catch (Exception ex) {
                    last = new GeminiUnavailableException(
                            "Gemini generateContent failed [model=" + model + ", attempt=" + attempt + "]: "
                                    + ex.getMessage(), ex);
                    if (!isRetryable(ex)) {
                        break; // e.g. 400/404: no point retrying this model, move to the next one
                    }
                    sleepBackoff(attempt);
                }
            }
            log.warn("Chat model '{}' unavailable; trying next fallback model (if any).", model);
        }
        throw last != null ? last : new GeminiUnavailableException("Gemini generateContent failed for all models");
    }

    private boolean isRetryable(Exception ex) {
        if (ex instanceof RestClientResponseException rce) {
            int code = rce.getStatusCode().value();
            return code == 429 || code >= 500;
        }
        return true; // network / IO errors are treated as transient
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(400L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void requireKey() {
        if (!properties.isConfigured()) {
            throw new GeminiUnavailableException("GEMINI_API_KEY is not configured");
        }
    }

    private double[] extractEmbedding(JsonNode node) {
        JsonNode values = node.path("embedding").path("values");
        if (values.isMissingNode() || !values.isArray()) {
            // batch response nodes are {"values": [...]} directly
            values = node.path("values");
        }
        if (!values.isArray()) {
            log.warn("Unexpected embedding response shape: {}", node);
            throw new GeminiUnavailableException("Malformed embedding response from Gemini");
        }
        double[] result = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i).asDouble();
        }
        return result;
    }

    private String extractText(JsonNode response) {
        JsonNode candidates = response.path("candidates");
        if (candidates.isArray() && candidates.size() > 0) {
            JsonNode parts = candidates.get(0).path("content").path("parts");
            if (parts.isArray() && parts.size() > 0) {
                return parts.get(0).path("text").asText("");
            }
        }
        return "";
    }

    public ObjectMapper mapper() {
        return objectMapper;
    }
}
