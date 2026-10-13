package com.example.demo.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextChunkerTest {

    private final TextChunker chunker = new TextChunker();

    @Test
    void splitsLongTextIntoMultipleChunks() {
        String text = ("Secure Bank offers home loans at competitive interest rates. ").repeat(60);
        List<String> chunks = chunker.chunk(text, 300, 50);
        assertTrue(chunks.size() > 1, "expected multiple chunks");
        assertFalse(chunks.get(0).isBlank());
    }

    @Test
    void handlesEmptyText() {
        assertTrue(chunker.chunk("", 300, 50).isEmpty());
        assertTrue(chunker.chunk(null, 300, 50).isEmpty());
    }

    @Test
    void keepsShortTextAsSingleChunk() {
        List<String> chunks = chunker.chunk("KYC is mandatory for all accounts.", 300, 50);
        assertTrue(chunks.size() == 1);
    }
}
