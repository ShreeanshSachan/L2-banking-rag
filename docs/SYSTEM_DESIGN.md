# System Design Document — Secure Bank AI Assistant (RAG)

> Version 1.0 · Applies to the `L2-banking-rag` repository
> Audience: engineers, reviewers and anyone maintaining or extending the system.

---

## 1. Purpose & scope

Secure Bank is a **Retrieval-Augmented Generation (RAG)** assistant for a fictitious retail
bank. It answers two very different classes of question against two different data sources:

| Question class | Example | Data source | Mechanism |
|---|---|---|---|
| **Policy / knowledge** | "What documents are needed for KYC?" | Secure Bank policy PDF | Vector retrieval + grounded LLM answer **with citations** |
| **Personal / structured** | "What is my account balance?" | MySQL (`accounts`, `cards`, `loans`) | Text-to-SQL, validated by a guard, executed scoped to the caller |
| **Operational / unsafe** | "Transfer ₹50,000 to account 999" | — | Politely **refused** |

The system is intentionally self-contained: a single Spring Boot service, an Angular SPA, one
MySQL database, and outbound calls to the Google Gemini API. It is a **learning / reference**
project — it must not be used with real customer data.

---

## 2. Architecture overview

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                                   Browser (Angular 21 SPA)                     │
│   /login  ·  / (dashboard)  ·  /chat          JWT kept in AuthService signal   │
└───────────────────────────────────┬──────────────────────────────────────────┘
                                    │ HTTP (JSON)  Authorization: Bearer <JWT>
                                    ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│  nginx (frontend container :80)                                                │
│    /            → Angular static bundle (SPA fallback → index.html)            │
│    /auth/ /chat/ /api/ /actuator/  → proxy_pass to backend:8080                │
└───────────────────────────────────┬──────────────────────────────────────────┘
                                    ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                         Spring Boot 3.3.5 backend (:8080)                      │
│                                                                                │
│   SecurityFilterChain ─ JwtAuthFilter ─ Controllers ─ Services                 │
│                                                                                │
│   ┌──────────────┐   ┌────────────────────┐   ┌───────────────────────────┐    │
│   │ RagService    │   │ SqlRagService       │   │ DocumentIngestionService  │    │
│   │ (intent)      │   │ (text-to-SQL)       │   │ (startup + admin ingest)  │    │
│   └──────┬────────┘   └─────────┬──────────┘   └─────────────┬─────────────┘    │
│          │                      │                            │                 │
│   ┌──────▼────────┐   ┌─────────▼──────────┐   ┌─────────────▼─────────────┐    │
│   │InMemoryVector  │   │ SqlGuard (allow-    │   │ GeminiClient              │    │
│   │Store (cosine / │   │ list, scope, LIMIT) │   │ (embed + generate + retry)│    │
│   │keyword)        │   └─────────┬──────────┘   └─────────────┬─────────────┘    │
│   └────────────────┘             │                            │                 │
│            ┌─────────────────────┼────────────────────────────┘                 │
│            ▼                     ▼                                              │
│     MaskingUtil / AuditService   │                                              │
└──────────┬───────────────────────┼────────────────────────────────────────────-┘
           ▼                       ▼
   ┌───────────────┐      ┌────────────────────┐
   │ MySQL 8       │      │ Google Gemini REST │
   │ users/accounts│      │ generateContent    │
   │ cards/loans   │      │ embedContent /batch│
   └───────────────┘      └────────────────────┘
```

### Deployment topology (docker-compose)

| Service | Image / build | Container | Host port | Notes |
|---|---|---|---|---|
| `db` | `mysql:8.4` | `banking-db` | **3307 → 3306** | healthcheck via `mysqladmin ping` |
| `backend` | `Backend/demo/Dockerfile` | `banking-backend` | 8080 | waits for `db` to be healthy |
| `frontend` | `Frontend/.../Dockerfile` (nginx) | `banking-frontend` | 4200 → 80 | proxies API to `backend:8080` |

---

## 3. Technology stack & rationale

| Layer | Choice | Why |
|---|---|---|
| Backend framework | **Spring Boot 3.3.5** (Java 17) | Stable, widely known, large ecosystem. Pinned to 3.3.x because the default start.spring.io output at build time targeted a newer/preview line. |
| Security | **Spring Security + JWT (HS256)** | Stateless, fits an SPA; roles (`USER`, `ADMIN`) map cleanly to method security. |
| Persistence | **Spring Data JPA + MySQL 8** | Simple entities, `ddl-auto=update` for demo convenience; `init.sql` provides the base schema. |
| LLM / embeddings | **Gemini REST API via `RestClient`** | Direct HTTP avoids heavy SDK/LangChain dependency churn; full control over retries and fallbacks. |
| Vector store | **In-memory (`CopyOnWriteArrayList`)** | The corpus is one PDF (69 chunks). No external vector DB needed; re-ingested on startup. |
| Frontend | **Angular 21 standalone + signals** | Modern, no NgModules; signals for lightweight local state. |
| Packaging | **Docker + docker-compose**, nginx | One command brings up the whole stack; nginx serves the SPA and proxies the API (no CORS pain in prod). |

---

## 4. Backend component design

Package root: `com.example.demo`

```
config/     AppConfig, RagProperties, GeminiProperties, JwtProperties, CorsProperties,
            DataInitializer, GlobalExceptionHandler
security/   SecurityConfig, JwtAuthFilter, JwtService, CustomUserDetailsService, BankUserPrincipal
entity/     User, Account, Card, Loan
repository/ UserRepository, AccountRepository, CardRepository, LoanRepository
dto/        AuthDtos, ChatDtos, UserDataDtos, ApiDtos
store/      DocumentChunk, ScoredChunk, InMemoryVectorStore
service/    AuthService, AccountProvisioningService, RagService, SqlRagService, SqlGuard,
            MaskingUtil, AuditService, DocumentIngestionService, PdfParser, TextChunker,
            GeminiClient, GeminiUnavailableException, SqlGuardException
controller/ AuthController, ChatController, UserDataController, IngestController, HealthController
```

### 4.1 Security

- **`SecurityConfig`** builds a stateless filter chain:
  - Public: `/auth/**`, `/health`, `/error`, `/actuator/health`, `/actuator/info`, `OPTIONS /**`.
  - `/api/admin/**` requires role `ADMIN`.
  - Everything else requires authentication.
  - **Custom entry point** returns `401`; **access-denied handler** returns `403`.
  - `JwtAuthFilter` is registered **only inside** the security chain; a `FilterRegistrationBean`
    with `setEnabled(false)` prevents Spring Boot from double-registering it (a duplicate
    registration caused authenticated-but-forbidden requests to be reported as `401`).
  - `/error` is explicitly permitted because error dispatches are re-checked by the filter
    chain — blocking them masks the real status code.
- **`JwtService`** issues/validates HS256 tokens (`sub`=userId, `username`, `roles`), TTL 24h.
- **`CustomUserDetailsService`** loads `BankUserPrincipal` (carries `userId`) from `users`.
- Passwords are hashed with **BCrypt**.

### 4.2 Document ingestion (indexing pipeline)

`DocumentIngestionService` runs on `ApplicationReadyEvent` **and** on demand:

```
policy.pdf ─► PdfParser (per-page text) ─► TextChunker (size 1000, overlap 150)
          ─► GeminiClient.embedBatch (batches of 50)  ─► InMemoryVectorStore
```

- If the PDF is missing → the index is empty and a warning is logged.
- If the API key is absent or an embedding call fails → the ingestion returns
  `embeddingsCreated=false`, and the store keeps **text-only chunks** for keyword retrieval.
- `POST /api/admin/ingest` re-runs this with an uploaded PDF (admin only).
- **Idempotent by rebuild:** `vectorStore.clear()` then `addAll(...)`.

### 4.3 Retrieval (`InMemoryVectorStore`)

- **Semantic path:** cosine similarity between the query embedding and chunk embeddings,
  top-K (default 5). A minimum score (default `0.30`) gates "I'm not confident" answers.
- **Keyword path:** term-overlap score (stopwords removed, tokens ≥ 3 chars) used when
  embeddings are unavailable or the query embed fails.
- Read-mostly structure (`CopyOnWriteArrayList`) safe under concurrent reads.
- `hasEmbeddings()` reports whether semantic search is possible.

### 4.4 Intent routing (`RagService`)

`classify()` is a deterministic, ordered rule check (cheap and predictable):

1. **`REFUSE`** if the message contains an *operational verb* ("transfer", "increase my",
   "block my", "close my", "apply for", …).
2. **`USER_SQL`** if it contains a *personal-data term* ("my balance", "my cards",
   "loan outstanding", "my emi", …).
3. Otherwise **`POLICY_DOC`**.

The **policy** path: embed query → retrieve top-K → build a context with `[Source, page]`
headers (bounded by `max-context-chars`, default 9000) → call `GeminiClient.generate` with a
strict system prompt that forbids inventing facts and requires inline citations. If generation
fails, it returns **retrieved excerpts** instead (`grounded=false`).

### 4.5 SQL-RAG + `SqlGuard` (the safety core)

`SqlRagService.answer()`:

1. If Gemini is configured, ask it to emit **one** `SELECT` using a fixed schema prompt that
   embeds `user_id = <caller>` and forbids `password`, comments, semicolons and DDL/DML.
2. Pass the SQL through **`SqlGuard.sanitize(sql, userId)`**, which enforces:
   - `SELECT`-only, exactly **one** statement (no `;`).
   - Strips code fences and SQL comments.
   - **Word-boundary** keyword block-list (`insert`, `update`, `delete`, `drop`, …, `union`,
     `password`, `outfile`) — word boundaries avoid false positives like `updated_at`.
   - Substring block-list (`into outfile`, `information_schema`, `sleep(`, …).
   - **Table allow-list:** only `users, accounts, cards, loans`.
   - **Mandatory scoping:** must contain `user_id = <caller>`.
   - **Mandatory `LIMIT`**, capped at 100.
3. Execute via `JdbcTemplate`; results are masked with `MaskingUtil.maskRow`.
4. On any failure (model unavailable, guard rejection, SQL error) fall back to a
   **deterministic, parameterised query** (`FALLBACK`) over the caller's rows — so personal
   answers still work with no LLM.

> The guard, not the prompt, is the security boundary. The prompt merely *asks* the model to
> behave; `SqlGuard` *enforces* it.

### 4.6 Masking, audit and provisioning

- **`MaskingUtil`** reduces account/card numbers to `****<last4>`; `maskRow` keys on names like
  `account_number`, `card_no`, `pan`. Applied to SQL-RAG output and in `UserDataController`.
- **`AuditService`** appends masked entries for auth events, chat turns and SQL executions to
  `logs/audit.log`.
- **`DataInitializer`** seeds `alice`/`admin`, 12 dummy customers, and starter records for
  `admin`; **`AccountProvisioningService`** gives every **new registration** 1–2 accounts, a
  card and often a loan (so dashboards are never empty). All seeding is idempotent.

### 4.7 Gemini client resilience

`GeminiClient.generate()` tries an **ordered model chain** — primary (`gemini-flash-latest`)
then fallbacks (`gemini-3.1-flash-lite`, `gemini-3.5-flash`, `gemini-3.8-flash`). For each model
it retries up to `MAX_ATTEMPTS_PER_MODEL = 2` on **transient** errors (`429`, `5xx`, IO), with
linear backoff; non-retryable errors (e.g. `400/404`) skip straight to the next model. Embeddings
use `:embedContent` / `:batchEmbedContents`. Failures raise `GeminiUnavailableException`, which
callers translate into graceful degradations rather than `500`s.

---

## 5. Data model

```
users (id PK, username UNIQUE, password, roles, created_at)
   │ 1
   │
   ├──< accounts (id PK, user_id FK, account_number UNIQUE, currency, balance, updated_at)
   ├──< cards    (id PK, user_id FK, type, last4, credit_limit, outstanding, status, updated_at)
   └──< loans    (id PK, user_id FK, product_type, principal, interest_rate, outstanding, status, updated_at)
```

- `roles` is a comma-separated string (e.g. `USER`, `ADMIN`).
- Money uses `DECIMAL(18,2)`; JPA maps to `BigDecimal`.
- Internal IDs (BIGINT) are **never** exposed where a scoped lookup will do; account numbers are
  masked in all user-facing output.

---

## 6. API contract

| Method | Path | Auth | Request | Response |
|---|---|---|---|---|
| POST | `/auth/register` | public | `{username, password}` | `AuthResponse` |
| POST | `/auth/login` | public | `{username, password}` | `AuthResponse` |
| POST | `/chat/ask` | USER | `{message, topK?}` | `ChatResponse` |
| GET | `/api/me/balance` | USER | — | `{accounts:[{accountNumber,currency,balance}]}` |
| GET | `/api/me/cards` | USER | — | `[{id,type,last4,creditLimit,outstanding,status}]` |
| GET | `/api/me/loans` | USER | — | `[{id,productType,principal,interestRate,outstanding,status}]` |
| POST | `/api/admin/ingest` | ADMIN | multipart `file` | `{status,pagesParsed,chunksIndexed,embeddingsCreated,message}` |
| GET | `/health` | public | — | `{status,llmProvider,llmConfigured,indexedChunks,embeddingModel,chatModel}` |

`ChatResponse = { answer, intent: POLICY_DOC|USER_SQL|REFUSE, citations[], latencyMs, masked, grounded }`

**Status codes:** `400` validation / bad multipart, `401` unauthenticated or bad credentials,
`403` authenticated but forbidden, `500` unexpected. (Hardened after the issues in
[`BUILD_PROMPTS.md`](BUILD_PROMPTS.md).)

---

## 7. Configuration

Everything is environment-overridable (see `.env.example` and `application.yml`).

| Variable | Default | Purpose |
|---|---|---|
| `GEMINI_API_KEY` | *(empty)* | Enables LLM answers + embeddings. Empty ⇒ graceful keyword mode. |
| `GEMINI_CHAT_MODEL` | `gemini-flash-latest` | Primary chat model |
| `GEMINI_CHAT_MODEL_FALLBACKS` | `gemini-3.1-flash-lite,gemini-3.5-flash,gemini-3.8-flash` | Ordered fallbacks |
| `GEMINI_EMBEDDING_MODEL` | `gemini-embedding-001` | 3072-dim embeddings |
| `JWT_SECRET` | dev placeholder | **≥ 32 chars** (HS256) |
| `JWT_EXPIRATION` | `86400000` | Token TTL (ms) |
| `DB_URL/DB_USER/DB_PASS` | localhost MySQL | `docker` profile uses host `db` |
| `POLICY_PDF` | `./docs/Secure_Bank_policy_Manual_input-docs.pdf` | RAG source |
| `RAG_CHUNK_SIZE` / `RAG_CHUNK_OVERLAP` | `1000` / `150` | Chunking |
| `RAG_TOP_K` / `RAG_MIN_SCORE` / `RAG_MAX_CONTEXT_CHARS` | `5` / `0.30` / `9000` | Retrieval |
| `SEED_ENABLED` | `true` | Master switch for demo data |
| `SEED_DUMMY_CUSTOMERS` | `12` | Number of dummy customers |
| `SEED_PROVISION_ON_REGISTER` | `true` | Auto-provision new users |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200,http://localhost` | Dev CORS (prod uses nginx proxy) |

---

## 8. Failure modes & graceful degradation

| Failure | Behaviour |
|---|---|
| No `GEMINI_API_KEY` | Retrieval degrades to keyword search; policy answers become retrieved excerpts; personal answers use deterministic queries. App still starts. |
| Embedding quota `429` during ingest | `embeddingsCreated=false`; store keeps text-only chunks; keyword retrieval. |
| Chat model `503 overloaded` | Retry, then walk the fallback chain; a lighter model answers. |
| PDF missing | Empty index; `/health` reports `indexedChunks=0`. |
| MySQL down at boot | Backend waits for the `db` healthcheck; container restarts until healthy. |
| Model returns unsafe SQL | `SqlGuard` rejects → deterministic fallback path. |

---

## 9. Testing

- **Backend unit tests** (`Backend/demo/src/test`): `SqlGuardTest` (9), `TextChunkerTest` (3),
  `MaskingUtilTest` (3), plus context-load test → **16 tests**.
- **End-to-end** (`scripts/e2e.ps1`): 16 checks over a running stack — health, auth, role
  enforcement, personal-data endpoints, `POLICY_DOC` grounding + citations, `USER_SQL` masking,
  `REFUSE`, cross-user scoping and admin ingest. Exits non-zero on failure (CI-friendly).
- **CI** (`.github/workflows/ci.yml`): builds/tests the backend (JDK 17) and builds the frontend
  (Node 22) on push/PR.

---

## 10. Security & privacy considerations

- **Scoping is enforced in SQL** (`user_id = <caller>`), not just in prompts.
- **Least privilege** table allow-list; SELECT-only; result cap.
- **Data masking** before any value leaves the service.
- **Secrets** live in `.env` (git-ignored); `.env.example` carries placeholders only.
- **Stateless JWT**, BCrypt password hashing, CORS limited to known origins.
- The app never performs **operational actions** (transfers, limit changes) — those are refused.

---

## 11. Known limitations & future work

- In-memory vector store ⇒ embeddings are recomputed on every restart; **persist** them
  (pgvector/Redis/file cache) to save quota and start-up time.
- Intent classification is rule-based; a **classifier or function-calling** approach would be
  more robust to paraphrasing.
- Single-writer seeding and `ddl-auto=update` are demo conveniences, not production practice
  (use **Flyway/Liquibase** migrations).
- Free-tier Gemini quotas/`503`s cause variable latency; a paid tier, request queuing or a
  local embedding model would stabilise it.
- No refresh tokens / token revocation yet.
