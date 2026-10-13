package com.example.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "gemini")
public record GeminiProperties(
        String apiKey,
        String baseUrl,
        String chatModel,
        List<String> chatModelFallbacks,
        String embeddingModel,
        long timeoutMs
) {
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String chatModelOrDefault() {
        return (chatModel == null || chatModel.isBlank()) ? "gemini-flash-latest" : chatModel;
    }

    public String embeddingModelOrDefault() {
        return (embeddingModel == null || embeddingModel.isBlank()) ? "gemini-embedding-001" : embeddingModel;
    }

    /**
     * Ordered list of chat models to try: the primary model first, then any
     * configured fallbacks. Duplicates are removed. Lets the app survive
     * transient 503 "model overloaded" / 429 responses on a single model.
     */
    public List<String> chatModelChain() {
        List<String> chain = new java.util.ArrayList<>();
        chain.add(chatModelOrDefault());
        if (chatModelFallbacks != null) {
            for (String m : chatModelFallbacks) {
                if (m != null && !m.isBlank()) {
                    chain.add(m.trim());
                }
            }
        }
        return chain.stream().distinct().toList();
    }

    public String baseUrlOrDefault() {
        return (baseUrl == null || baseUrl.isBlank())
                ? "https://generativelanguage.googleapis.com/v1beta"
                : baseUrl;
    }
}
