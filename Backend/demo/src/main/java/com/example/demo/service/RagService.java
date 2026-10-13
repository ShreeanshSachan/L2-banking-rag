package com.example.demo.service;

import com.example.demo.config.RagProperties;
import com.example.demo.dto.ChatDtos.ChatRequest;
import com.example.demo.dto.ChatDtos.ChatResponse;
import com.example.demo.dto.ChatDtos.Citation;
import com.example.demo.store.InMemoryVectorStore;
import com.example.demo.store.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Orchestrates the RAG chat flow:
 *   classify intent -> (POLICY_DOC) vector retrieval + grounded generation
 *                   -> (USER_SQL)   guarded user-scoped SQL-RAG
 *                   -> (REFUSE)     safe fallback for operational / unsafe asks
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private static final String POLICY_SYSTEM_PROMPT = """
            You are the Secure Bank policy assistant. Answer the customer's question using ONLY the
            provided policy context. Rules:
              - If the context does not contain the answer, say you don't have enough information
                and suggest contacting the branch. Do NOT invent details.
              - Cite the sources you used inline as [Source: <file>, page <n>].
              - Never give personalised financial advice and never claim to perform account actions.
              - Be concise, factual and friendly.
            """;

    private static final String REFUSAL_MESSAGE =
            "I can only provide information about Secure Bank policies and your own accounts. "
            + "I can't perform operational actions such as increasing limits, transferring funds, "
            + "blocking cards or closing accounts. Please visit a branch or call customer care for those requests.";

    private static final List<String> OPERATIONAL_VERBS = List.of(
            "increase my", "raise my", "reduce my", "lower my", "transfer", "send money",
            "close my", "block my", "unblock my", "reset my", "delete my", "change my",
            "open a new account", "open an account", "apply for", "cancel my", "renew my");

    private static final List<String> USER_SQL_TERMS = List.of(
            "my balance", "my account", "account balance", "balance in my", "my cards", "my card",
            "card outstanding", "credit card outstanding", "my loans", "my loan", "loan outstanding",
            "my outstanding", "my emi", "how much do i", "how much money", "my transactions",
            "my savings", "my statements", "outstanding on my");

    private final GeminiClient geminiClient;
    private final InMemoryVectorStore vectorStore;
    private final RagProperties ragProperties;
    private final SqlRagService sqlRagService;
    private final AuditService auditService;

    public RagService(GeminiClient geminiClient,
                      InMemoryVectorStore vectorStore,
                      RagProperties ragProperties,
                      SqlRagService sqlRagService,
                      AuditService auditService) {
        this.geminiClient = geminiClient;
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
        this.sqlRagService = sqlRagService;
        this.auditService = auditService;
    }

    public ChatResponse ask(ChatRequest request, Long userId) {
        long start = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        String message = request.message().trim();
        String intent = classify(message);

        ChatResponse response;
        try {
            response = switch (intent) {
                case "REFUSE" -> new ChatResponse(REFUSAL_MESSAGE, "REFUSE", List.of(),
                        System.currentTimeMillis() - start, false, true);
                case "USER_SQL" -> answerFromSql(message, userId, start);
                default -> answerFromPolicy(message, request.topK(), start);
            };
        } catch (Exception ex) {
            log.error("Chat handling failed: {}", ex.getMessage(), ex);
            response = new ChatResponse(
                    "Sorry, something went wrong while processing your request. Please try again.",
                    intent, List.of(), System.currentTimeMillis() - start, false, false);
        }

        auditService.chatEvent(response.intent(), userId, response.latencyMs(),
                !response.answer().startsWith("Sorry,"), response.masked(), traceId);
        return response;
    }

    private ChatResponse answerFromSql(String message, Long userId, long start) {
        SqlRagService.SqlAnswer answer = sqlRagService.answer(message, userId);
        return new ChatResponse(answer.answer(), "USER_SQL", List.of(),
                System.currentTimeMillis() - start, answer.masked(), true);
    }

    private ChatResponse answerFromPolicy(String message, Integer requestedTopK, long start) {
        int topK = requestedTopK != null && requestedTopK > 0 ? requestedTopK : ragProperties.topKOrDefault();

        double[] queryEmbedding = null;
        boolean semantic = false;
        if (geminiClient.isConfigured() && vectorStore.hasEmbeddings()) {
            try {
                queryEmbedding = geminiClient.embed(message);
                semantic = true;
            } catch (GeminiUnavailableException ex) {
                log.warn("Query embedding failed ({}). Using keyword retrieval.", ex.getMessage());
            }
        }

        List<ScoredChunk> hits = semantic
                ? vectorStore.search(queryEmbedding, topK)
                : vectorStore.keywordSearch(message, topK);

        if (hits.isEmpty()) {
            return new ChatResponse(
                    "I couldn't find anything relevant in the Secure Bank policy documents for that question. "
                    + "Could you rephrase it, or ask about loans, KYC, cards, deposits or account policies?",
                    "POLICY_DOC", List.of(), System.currentTimeMillis() - start, false, false);
        }

        if (semantic && hits.get(0).score() < ragProperties.minScore()) {
            return new ChatResponse(
                    "I don't have enough grounded information in the policy documents to answer that confidently. "
                    + "Please refine your question or contact your branch for clarification.",
                    "POLICY_DOC", List.of(), System.currentTimeMillis() - start, false, false);
        }

        List<Citation> citations = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        int maxChars = ragProperties.maxContextCharsOrDefault();
        for (ScoredChunk hit : hits) {
            String text = hit.chunk().getText();
            if (context.length() + text.length() > maxChars && context.length() > 0) {
                break;
            }
            context.append("[Source: ").append(hit.chunk().getSource())
                    .append(", page ").append(hit.chunk().getPage()).append("]\n")
                    .append(text).append("\n\n");
            citations.add(new Citation(
                    hit.chunk().getSource(),
                    hit.chunk().getPage(),
                    round(hit.score()),
                    snippet(text, 300)));
        }

        boolean grounded = true;
        String answer;
        if (geminiClient.isConfigured()) {
            String userPrompt = "Policy context:\n" + context
                    + "\nCustomer question: " + message
                    + "\n\nAnswer using only the context above, with citations:";
            try {
                answer = geminiClient.generate(POLICY_SYSTEM_PROMPT, userPrompt);
                if (answer == null || answer.isBlank()) {
                    answer = fallbackExcerptAnswer(citations, hits);
                    grounded = false;
                }
            } catch (GeminiUnavailableException ex) {
                log.warn("Generation failed ({}). Returning retrieved excerpts.", ex.getMessage());
                answer = fallbackExcerptAnswer(citations, hits);
                grounded = false;
            }
        } else {
            answer = fallbackExcerptAnswer(citations, hits);
            grounded = false;
        }

        return new ChatResponse(answer, "POLICY_DOC", citations,
                System.currentTimeMillis() - start, false, grounded);
    }

    private String fallbackExcerptAnswer(List<Citation> citations, List<ScoredChunk> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("I couldn't generate a summarized answer right now, so here are the most ")
                .append("relevant policy excerpts I retrieved for you:\n\n");
        int limit = Math.min(hits.size(), 3);
        for (int i = 0; i < limit; i++) {
            ScoredChunk hit = hits.get(i);
            sb.append("• [").append(hit.chunk().getSource()).append(", page ")
                    .append(hit.chunk().getPage()).append("] ")
                    .append(snippet(hit.chunk().getText(), 400)).append("\n\n");
        }
        return sb.toString().trim();
    }

    private String classify(String message) {
        String q = message.toLowerCase(Locale.ROOT);
        for (String verb : OPERATIONAL_VERBS) {
            if (q.contains(verb)) {
                return "REFUSE";
            }
        }
        for (String term : USER_SQL_TERMS) {
            if (q.contains(term)) {
                return "USER_SQL";
            }
        }
        return "POLICY_DOC";
    }

    private String snippet(String text, int max) {
        String trimmed = text.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
