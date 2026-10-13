package com.example.demo.controller;

import com.example.demo.dto.ApiDtos.IngestResponse;
import com.example.demo.service.DocumentIngestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Admin-only endpoint to (re)build the policy RAG index from an uploaded PDF.
 * Security: /api/admin/** requires ROLE_ADMIN.
 */
@RestController
@RequestMapping("/api/admin")
public class IngestController {

    private final DocumentIngestionService ingestionService;

    public IngestController(DocumentIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<IngestResponse> ingest(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        String name = file.getOriginalFilename() == null ? "uploaded.pdf" : file.getOriginalFilename();
        return ResponseEntity.ok(ingestionService.ingest(file.getBytes(), name));
    }
}
