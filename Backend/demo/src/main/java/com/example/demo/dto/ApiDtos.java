package com.example.demo.dto;

import java.time.Instant;

public final class ApiDtos {

    private ApiDtos() {
    }

    public record ApiError(
            Instant timestamp,
            int status,
            String error,
            String message,
            String path
    ) {
    }

    public record IngestResponse(
            String status,
            int pagesParsed,
            int chunksIndexed,
            boolean embeddingsCreated,
            String message
    ) {
    }

    public record HealthStatus(
            String status,
            String llmProvider,
            boolean llmConfigured,
            int indexedChunks,
            String embeddingModel,
            String chatModel
    ) {
    }
}
