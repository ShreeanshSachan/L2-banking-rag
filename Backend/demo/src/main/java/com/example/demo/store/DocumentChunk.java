package com.example.demo.store;

public class DocumentChunk {

    private final String id;
    private final String text;
    private final String source;
    private final int page;
    private final double[] embedding;

    public DocumentChunk(String id, String text, String source, int page, double[] embedding) {
        this.id = id;
        this.text = text;
        this.source = source;
        this.page = page;
        this.embedding = embedding;
    }

    public String getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public String getSource() {
        return source;
    }

    public int getPage() {
        return page;
    }

    public double[] getEmbedding() {
        return embedding;
    }

    public boolean hasEmbedding() {
        return embedding != null && embedding.length > 0;
    }
}
