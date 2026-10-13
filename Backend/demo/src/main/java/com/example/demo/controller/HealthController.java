package com.example.demo.controller;

import com.example.demo.dto.ApiDtos.HealthStatus;
import com.example.demo.service.GeminiClient;
import com.example.demo.store.InMemoryVectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final GeminiClient geminiClient;
    private final InMemoryVectorStore vectorStore;

    public HealthController(GeminiClient geminiClient, InMemoryVectorStore vectorStore) {
        this.geminiClient = geminiClient;
        this.vectorStore = vectorStore;
    }

    @GetMapping("/health")
    public HealthStatus health() {
        return new HealthStatus(
                "UP",
                "Google Gemini",
                geminiClient.isConfigured(),
                vectorStore.size(),
                geminiClient.embeddingModel(),
                geminiClient.chatModel());
    }
}
