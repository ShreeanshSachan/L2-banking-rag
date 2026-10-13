package com.example.demo.service;

import com.example.demo.entity.Account;
import com.example.demo.entity.Card;
import com.example.demo.entity.Loan;
import com.example.demo.repository.AccountRepository;
import com.example.demo.repository.CardRepository;
import com.example.demo.repository.LoanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SQL-RAG: translates a natural-language question about the caller's own accounts
 * into a guarded, user-scoped SELECT, executes it, and masks the results.
 *
 * When Gemini is unavailable the service falls back to deterministic, parameterised
 * queries so structured answers still work end-to-end.
 */
@Service
public class SqlRagService {

    private static final Logger log = LoggerFactory.getLogger(SqlRagService.class);

    private static final String SCHEMA = """
            Tables (MySQL):
              users(id, username, password, roles, created_at)
              accounts(id, user_id, account_number, currency, balance, updated_at)
              cards(id, user_id, type, credit_limit, last4, outstanding, status, updated_at)
              loans(id, user_id, product_type, principal, interest_rate, outstanding, status, updated_at)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final GeminiClient geminiClient;
    private final SqlGuard sqlGuard;
    private final MaskingUtil maskingUtil;
    private final AuditService auditService;
    private final AccountRepository accountRepository;
    private final CardRepository cardRepository;
    private final LoanRepository loanRepository;

    public SqlRagService(JdbcTemplate jdbcTemplate,
                         GeminiClient geminiClient,
                         SqlGuard sqlGuard,
                         MaskingUtil maskingUtil,
                         AuditService auditService,
                         AccountRepository accountRepository,
                         CardRepository cardRepository,
                         LoanRepository loanRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.geminiClient = geminiClient;
        this.sqlGuard = sqlGuard;
        this.maskingUtil = maskingUtil;
        this.auditService = auditService;
        this.accountRepository = accountRepository;
        this.cardRepository = cardRepository;
        this.loanRepository = loanRepository;
    }

    public record SqlAnswer(String answer, boolean masked, String sql) {
    }

    public SqlAnswer answer(String query, Long userId) {
        if (geminiClient.isConfigured()) {
            long start = System.currentTimeMillis();
            try {
                String generated = generateSql(query, userId);
                String safeSql = sqlGuard.sanitize(generated, userId);
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(safeSql);
                auditService.sqlEvent(userId, safeSql, System.currentTimeMillis() - start, true);
                return new SqlAnswer(formatRows(rows), true, safeSql);
            } catch (Exception ex) {
                log.warn("Text-to-SQL path failed ({}). Falling back to deterministic query.", ex.getMessage());
                auditService.sqlEvent(userId, "GUARDED/FAILED: " + ex.getMessage(),
                        System.currentTimeMillis() - start, false);
            }
        }
        return deterministic(query, userId);
    }

    private String generateSql(String query, Long userId) {
        String system = """
                You are a MySQL query generator for a banking assistant.
                %s
                Rules:
                  - Output ONLY a single SELECT statement, nothing else.
                  - Always restrict rows to the current user with the literal condition: user_id = %d
                  - Never select the password column.
                  - Include LIMIT 100.
                  - No comments, no semicolons, no DDL/DML.
                """.formatted(SCHEMA, userId);
        String user = "UserId: " + userId + "\nQuestion: " + query + "\nSQL:";
        String raw = geminiClient.generate(system, user);
        return extractSelect(raw);
    }

    private String extractSelect(String raw) {
        if (raw == null) {
            throw new SqlGuardException("No SQL generated");
        }
        String cleaned = raw.replaceAll("(?s)```[a-zA-Z]*", "").replace("```", "").trim();
        int idx = cleaned.toLowerCase(Locale.ROOT).indexOf("select");
        if (idx < 0) {
            throw new SqlGuardException("Model did not return a SELECT statement");
        }
        String sql = cleaned.substring(idx);
        int newline = sql.indexOf('\n');
        if (newline > 0) {
            sql = sql.substring(0, newline);
        }
        return sql.trim();
    }

    private SqlAnswer deterministic(String query, Long userId) {
        String q = query.toLowerCase(Locale.ROOT);
        boolean wantsCards = containsAny(q, "card", "credit", "outstanding", "limit", "spend");
        boolean wantsLoans = containsAny(q, "loan", "emi", "mortgage", "borrowed", "principal");
        boolean wantsAccounts = containsAny(q, "balance", "account", "savings", "deposit", "money", "funds");

        StringBuilder sb = new StringBuilder();
        boolean masked = false;

        if (wantsCards && !wantsAccounts && !wantsLoans) {
            List<Card> cards = cardRepository.findByUserId(userId);
            if (cards.isEmpty()) {
                return new SqlAnswer("You have no cards on record.", false, "FALLBACK");
            }
            sb.append("Here are your cards:\n");
            for (Card c : cards) {
                sb.append("• ").append(c.getType()).append(" card ending ")
                        .append(c.getLast4())
                        .append(" — outstanding ").append(money(c.getOutstanding()))
                        .append(" of limit ").append(money(c.getCreditLimit()))
                        .append(" (").append(c.getStatus()).append(")\n");
            }
            return new SqlAnswer(sb.toString().trim(), false, "FALLBACK");
        }

        if (wantsLoans && !wantsAccounts && !wantsCards) {
            List<Loan> loans = loanRepository.findByUserId(userId);
            if (loans.isEmpty()) {
                return new SqlAnswer("You have no loans on record.", false, "FALLBACK");
            }
            sb.append("Here are your loans:\n");
            for (Loan l : loans) {
                sb.append("• ").append(l.getProductType())
                        .append(" — outstanding ").append(money(l.getOutstanding()))
                        .append(" at ").append(l.getInterestRate()).append("% p.a. (")
                        .append(l.getStatus()).append(")\n");
            }
            return new SqlAnswer(sb.toString().trim(), false, "FALLBACK");
        }

        // Default: accounts / balances
        List<Account> accounts = accountRepository.findByUserId(userId);
        if (accounts.isEmpty()) {
            return new SqlAnswer("I couldn't find any accounts linked to your profile.", false, "FALLBACK");
        }
        sb.append("Here are your account balances:\n");
        for (Account a : accounts) {
            sb.append("• ").append(maskingUtil.maskAccountNumber(a.getAccountNumber()))
                    .append(" (").append(a.getCurrency()).append("): ")
                    .append(money(a.getBalance())).append("\n");
        }
        masked = true;
        return new SqlAnswer(sb.toString().trim(), masked, "FALLBACK");
    }

    private String formatRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "I couldn't find any matching records on your profile.";
        }
        StringBuilder sb = new StringBuilder("Here is what I found:\n");
        for (Map<String, Object> row : rows) {
            Map<String, Object> maskedRow = maskingUtil.maskRow(row);
            maskedRow.forEach((k, v) -> sb.append("• ").append(k).append(": ").append(formatValue(v)).append("\n"));
            if (rows.size() > 1) {
                sb.append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String formatValue(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        return String.valueOf(value);
    }

    private String money(BigDecimal value) {
        if (value == null) {
            return "N/A";
        }
        return "₹" + value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
