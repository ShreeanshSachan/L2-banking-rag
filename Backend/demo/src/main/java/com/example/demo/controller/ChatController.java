package com.example.demo.controller;

import com.example.demo.dto.ChatDtos.ChatRequest;
import com.example.demo.dto.ChatDtos.ChatResponse;
import com.example.demo.security.BankUserPrincipal;
import com.example.demo.service.RagService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private final RagService ragService;

    public ChatController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/ask")
    public ResponseEntity<ChatResponse> ask(@Valid @RequestBody ChatRequest request,
                                            @AuthenticationPrincipal BankUserPrincipal principal) {
        return ResponseEntity.ok(ragService.ask(request, principal.getUserId()));
    }
}
