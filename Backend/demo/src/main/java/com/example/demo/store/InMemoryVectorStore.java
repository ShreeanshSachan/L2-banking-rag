package com.example.demo.store;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Simple in-process vector store. Chunks and their embeddings are held in memory
 * and re-indexed on startup from the policy PDF (see DocumentIngestionService).
 *
 * Retrieval supports semantic (cosine) search when embeddings are available and
 * falls back to keyword overlap when they are not (e.g. no Gemini API key set).
 */
@Component
public class InMemoryVectorStore {

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "is", "are", "of", "to", "and", "or", "in", "on", "for", "with",
            "what", "which", "how", "do", "does", "my", "i", "me", "you", "your", "can", "could",
            "please", "tell", "about", "at", "be", "by", "as", "it", "this", "that");

    private final List<DocumentChunk> chunks = new CopyOnWriteArrayList<>();

    public void clear() {
        chunks.clear();
    }

    public void addAll(List<DocumentChunk> newChunks) {
        chunks.addAll(newChunks);
    }

    public int size() {
        return chunks.size();
    }

    public boolean hasEmbeddings() {
        return chunks.stream().anyMatch(DocumentChunk::hasEmbedding);
    }

    public List<ScoredChunk> search(double[] queryEmbedding, int topK) {
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            return List.of();
        }
        return chunks.stream()
                .filter(DocumentChunk::hasEmbedding)
                .map(c -> new ScoredChunk(c, cosine(queryEmbedding, c.getEmbedding())))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(Math.max(1, topK))
                .collect(Collectors.toList());
    }

    public List<ScoredChunk> keywordSearch(String query, int topK) {
        Set<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        return chunks.stream()
                .map(c -> new ScoredChunk(c, overlapScore(queryTerms, tokenize(c.getText()))))
                .filter(s -> s.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(Math.max(1, topK))
                .collect(Collectors.toList());
    }

    private static double overlapScore(Set<String> queryTerms, Set<String> docTerms) {
        if (docTerms.isEmpty()) {
            return 0;
        }
        long hits = queryTerms.stream().filter(docTerms::contains).count();
        return (double) hits / (double) queryTerms.size();
    }

    private static Set<String> tokenize(String text) {
        if (text == null) {
            return Set.of();
        }
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(t -> t.length() > 2 && !STOPWORDS.contains(t))
                .collect(Collectors.toSet());
    }

    private static double cosine(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public List<DocumentChunk> all() {
        return new ArrayList<>(chunks);
    }
}
