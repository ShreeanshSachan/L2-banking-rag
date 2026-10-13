package com.example.demo.service;

import com.example.demo.config.RagProperties;
import com.example.demo.dto.ApiDtos.IngestResponse;
import com.example.demo.store.DocumentChunk;
import com.example.demo.store.InMemoryVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Document RAG ingestion pipeline:
 * PDF -> per-page text -> chunks -> embeddings -> in-memory vector store.
 *
 * Runs automatically on startup so the policy knowledge base is always fresh,
 * and can be re-run on demand (e.g. via the admin /ingest endpoint).
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);
    private static final int EMBED_BATCH_SIZE = 50;

    private final RagProperties ragProperties;
    private final PdfParser pdfParser;
    private final TextChunker textChunker;
    private final GeminiClient geminiClient;
    private final InMemoryVectorStore vectorStore;

    private volatile boolean indexed = false;

    public DocumentIngestionService(RagProperties ragProperties,
                                    PdfParser pdfParser,
                                    TextChunker textChunker,
                                    GeminiClient geminiClient,
                                    InMemoryVectorStore vectorStore) {
        this.ragProperties = ragProperties;
        this.pdfParser = pdfParser;
        this.textChunker = textChunker;
        this.geminiClient = geminiClient;
        this.vectorStore = vectorStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ingestOnStartup() {
        Path path = Path.of(ragProperties.pdfPath() == null ? "" : ragProperties.pdfPath());
        File file = path.toFile();
        if (!file.exists()) {
            log.warn("Policy PDF not found at '{}'. Document RAG index will be empty. "
                    + "Copy the policy manual there or set POLICY_PDF.", file.getAbsolutePath());
            return;
        }
        try {
            IngestResponse response = ingest(file);
            log.info("Policy index ready: {} pages, {} chunks, embeddings={}",
                    response.pagesParsed(), response.chunksIndexed(), response.embeddingsCreated());
        } catch (Exception ex) {
            log.error("Failed to ingest policy PDF: {}", ex.getMessage(), ex);
        }
    }

    public synchronized IngestResponse ingest(File file) throws IOException {
        List<PdfParser.PageText> pages = pdfParser.parse(file);
        return index(file.getName(), pages);
    }

    public synchronized IngestResponse ingest(byte[] bytes, String sourceName) throws IOException {
        List<PdfParser.PageText> pages = pdfParser.parse(bytes);
        return index(sourceName, pages);
    }

    public boolean isIndexed() {
        return indexed;
    }

    private IngestResponse index(String source, List<PdfParser.PageText> pages) {
        int chunkSize = ragProperties.chunkSizeOrDefault();
        int overlap = ragProperties.chunkOverlapOrDefault();

        List<DocumentChunk> chunks = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        int counter = 0;
        for (PdfParser.PageText page : pages) {
            for (String piece : textChunker.chunk(page.text(), chunkSize, overlap)) {
                String id = source + "#" + page.page() + "#" + (counter++);
                chunks.add(new DocumentChunk(id, piece, source, page.page(), null));
                texts.add(piece);
            }
        }

        boolean createdEmbeddings = applyEmbeddings(chunks, texts);

        vectorStore.clear();
        vectorStore.addAll(chunks);
        indexed = true;

        return new IngestResponse(
                "INDEXED",
                pages.size(),
                chunks.size(),
                createdEmbeddings,
                createdEmbeddings
                        ? "Indexed with semantic embeddings."
                        : "Indexed without embeddings (GEMINI_API_KEY missing or embedding call failed). "
                          + "Falling back to keyword retrieval.");
    }

    private boolean applyEmbeddings(List<DocumentChunk> chunks, List<String> texts) {
        if (texts.isEmpty() || !geminiClient.isConfigured()) {
            return false;
        }
        try {
            for (int start = 0; start < texts.size(); start += EMBED_BATCH_SIZE) {
                int end = Math.min(start + EMBED_BATCH_SIZE, texts.size());
                List<String> batch = texts.subList(start, end);
                List<double[]> vectors = geminiClient.embedBatch(batch);
                for (int i = 0; i < vectors.size(); i++) {
                    DocumentChunk original = chunks.get(start + i);
                    chunks.set(start + i, new DocumentChunk(
                            original.getId(), original.getText(), original.getSource(),
                            original.getPage(), vectors.get(i)));
                }
            }
            return true;
        } catch (GeminiUnavailableException ex) {
            log.warn("Embedding generation failed ({}). Continuing with keyword retrieval.", ex.getMessage());
            return false;
        }
    }
}
