package com.example.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        String pdfPath,
        int chunkSize,
        int chunkOverlap,
        int topK,
        double minScore,
        int maxContextChars
) {
    public int chunkSizeOrDefault() {
        return chunkSize > 0 ? chunkSize : 1000;
    }

    public int chunkOverlapOrDefault() {
        return chunkOverlap > 0 ? chunkOverlap : 150;
    }

    public int topKOrDefault() {
        return topK > 0 ? topK : 5;
    }

    public int maxContextCharsOrDefault() {
        return maxContextChars > 0 ? maxContextChars : 9000;
    }
}
