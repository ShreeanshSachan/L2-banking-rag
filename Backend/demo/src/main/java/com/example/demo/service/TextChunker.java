package com.example.demo.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-ish character chunker with overlap. Keeps sentences intact where
 * possible and hard-splits overly long sentences. Chunk sizes are approximate
 * (character based) which is adequate for the policy manual.
 */
@Component
public class TextChunker {

    public List<String> chunk(String text, int size, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null) {
            return chunks;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return chunks;
        }
        if (size <= 0) {
            size = 1000;
        }
        if (overlap < 0 || overlap >= size) {
            overlap = Math.max(0, size / 6);
        }

        List<String> sentences = splitSentences(normalized);
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            for (String piece : hardSplit(sentence, size)) {
                if (current.length() > 0 && current.length() + piece.length() + 1 > size) {
                    String completed = current.toString().trim();
                    chunks.add(completed);
                    current = new StringBuilder(tail(completed, overlap));
                }
                current.append(piece).append(' ');
            }
        }
        if (!current.toString().isBlank()) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    private List<String> splitSentences(String text) {
        List<String> result = new ArrayList<>();
        for (String s : text.split("(?<=[.!?;:])\\s+")) {
            if (!s.isBlank()) {
                result.add(s.trim());
            }
        }
        return result;
    }

    private List<String> hardSplit(String sentence, int size) {
        List<String> parts = new ArrayList<>();
        if (sentence.length() <= size) {
            parts.add(sentence);
            return parts;
        }
        int start = 0;
        while (start < sentence.length()) {
            int end = Math.min(start + size, sentence.length());
            parts.add(sentence.substring(start, end).trim());
            start = end;
        }
        return parts;
    }

    private String tail(String text, int overlap) {
        if (overlap <= 0 || text.length() <= overlap) {
            return overlap <= 0 ? "" : text;
        }
        String slice = text.substring(text.length() - overlap);
        int firstSpace = slice.indexOf(' ');
        return firstSpace > 0 ? slice.substring(firstSpace + 1) : slice;
    }
}
