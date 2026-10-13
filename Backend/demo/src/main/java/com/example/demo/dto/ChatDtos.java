package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class ChatDtos {

    private ChatDtos() {
    }

    public record ChatRequest(
            @NotBlank @Size(max = 2000) String message,
            Integer topK
    ) {
    }

    public record Citation(
            String source,
            int page,
            double score,
            String snippet
    ) {
    }

    public record ChatResponse(
            String answer,
            String intent,
            List<Citation> citations,
            long latencyMs,
            boolean masked,
            boolean grounded
    ) {
    }
}
