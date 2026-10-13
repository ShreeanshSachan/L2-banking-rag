# Build Prompts — What Worked, What Didn't, and Why

This document is the **prompting playbook** used to build Secure Bank. It records the prompts
that produced good results and, just as importantly, the prompts that *look* reasonable but
produce broken, insecure or unverifiable systems — with an explanation of each.

It's deliberately concrete: every example maps to real code in this repository
(see [`SYSTEM_DESIGN.md`](SYSTEM_DESIGN.md)).

---

## 0. Why prompt quality dominates outcome

Five habits separate prompts that work from prompts that don't:

1. **Constrain the stack and versions.** "Use Spring Boot **3.3.5**, Java **17**, and call the
   Gemini **REST API directly**" beats "use Spring Boot".
2. **Build one component at a time and verify.** A prompt that asks for the entity layer *and*
   lets you run the tests is reviewable; a prompt that asks for the whole app is not.
3. **State the security model, not just the feature.** "Text-to-SQL **validated by a guard**" is
   different from "text-to-SQL".
4. **Name the failure behaviour.** "Fall back to keyword search if embeddings fail" produces
   resilient code; leaving it unspecified produces `500`s.
5. **Ask for a machine-checkable result.** "Add tests / a script that exits non-zero on failure"
   turns opinions into signals.

---

## Part A — Prompts that worked

### A1. Framing the project (stack + boundaries)

> **Prompt**
> ```
> Build the backend for a banking "RAG" support assistant in a new Maven project.
> Constraints:
> - Spring Boot 3.3.5 on Java 17 (do NOT use a 4.x preview/BOM).
> - Spring Web, Spring Security, Spring Data JPA, Validation, MySQL driver.
> - Call the Google Gemini REST API directly with RestClient (NO LangChain/SDK).
> - Vector store is in-memory and rebuilt from the policy PDF on startup.
> - Package root com.example.demo.
> ```
> **Why it worked:** it pins the two things that drift (framework version, LLM integration style)
> and fixes the package root so every later prompt can refer to `com.example.demo`.

### A2. Data model with explicit columns

> **Prompt**
> ```
> Create JPA entities User, Account, Card, Loan in com.example.demo.entity.
> - users(id PK auto, username UNIQUE, password, roles comma-separated, created_at)
> - accounts(id, user_id, account_number UNIQUE, currency, balance DECIMAL(18,2), updated_at)
> - cards(id, user_id, type, last4, credit_limit, outstanding, status, updated_at)
> - loans(id, user_id, product_type, principal, interest_rate, outstanding, status, updated_at)
> Use BigDecimal for money. Add a matching JpaRepository per entity with findByUserId.
> ```
> **Why it worked:** naming the exact columns and types removes ambiguity, so the schema in
> `db/init.sql` and the JPA entities agree.

### A3. Security with explicit status semantics

> **Prompt**
> ```
> Configure Spring Security for a stateless JWT API.
> - Permit only /auth/**, /health, /error, /actuator/health, /actuator/info and OPTIONS.
> - Require role ADMIN for /api/admin/**; authenticate everything else.
> - Return 401 when unauthenticated and 403 when authenticated-but-forbidden.
> - Register the JWT filter ONLY inside the security chain (disable its servlet auto-registration).
> ```
> **Why it worked:** it specifies the **observable contract** (401 vs 403), which is exactly what
> tests assert. The last line pre-empts a subtle Spring Boot bug (see B11).

### A4. Ingestion pipeline pinned to events

> **Prompt**
> ```
> Add DocumentIngestionService. On ApplicationReadyEvent: locate the policy PDF, parse it
> page-by-page, chunk each page (size 1000, overlap 150), embed in batches of 50 via
> gemini batchEmbedContents, and load the chunks into the in-memory store.
> If the PDF is missing or embedding fails, log and continue with text-only chunks.
> ```
> **Why it worked:** "on ApplicationReadyEvent" and the batch size are decisions, not vibes;
> "log and continue" makes the failure path explicit and testable.

### A5. Retrieval with a fallback

> **Prompt**
> ```
> Implement InMemoryVectorStore with:
> - addAll/clear/size and hasEmbeddings().
> - search(queryEmbedding, topK) using cosine similarity.
> - keywordSearch(query, topK) using stopword-filtered term overlap.
> Retrieval uses semantic search when embeddings exist, otherwise keyword search.
> ```
> **Why it worked:** two retrieval modes was the resilience strategy; asking for both up front
> avoided a rewrite when the API key was absent.

### A6. Gemini client with retry + model chain

> **Prompt**
> ```
> Wrap Gemini in GeminiClient with embed(), embedBatch() and generate().
> generate() should try a primary model then an ordered fallback list. Retry up to 2 times per
> model on 429/5xx/IO errors with backoff; on 400/404 skip to the next model immediately.
> Surface failures as GeminiUnavailableException, never a 500.
> ```
> **Why it worked:** it encodes *which* errors are transient vs terminal. That distinction is
> why the app survives free-tier `503`s and retired-model `404`s.

### A7. Intent routing as ordered rules

> **Prompt**
> ```
> In RagService classify(message) into REFUSE | USER_SQL | POLICY_DOC using ordered rules:
> operational verbs (transfer, increase my, block my, close my, apply for…) → REFUSE first;
> personal terms (my balance, my cards, loan outstanding…) → USER_SQL; otherwise POLICY_DOC.
> ```
> **Why it worked:** "ordered rules" is the key word — refusing operational actions must win over
> matching any data keyword.

### A8. Text-to-SQL gated by a guard

> **Prompt**
> ```
> Add SqlRagService: ask Gemini for a single SELECT over the schema, then pass it to
> SqlGuard.sanitize(sql, userId) which enforces: SELECT-only, one statement, word-boundary
> keyword block-list (insert/update/delete/drop/.../union/password/outfile), substring
> block-list (into outfile, information_schema...), table allow-list (users,accounts,cards,loans),
> a mandatory user_id = <userId> scope, and LIMIT capped at 100. Execute with JdbcTemplate.
> On any failure, fall back to deterministic parameterised queries.
> ```
> **Why it worked:** security is stated as **mechanisms with rules**, not as a wish ("be safe").
> The deterministic fallback keeps personal answers working with no LLM.

### A9. Masking + audit

> **Prompt**
> ```
> Add MaskingUtil that reduces account/card numbers to ****<last4> for the keys account_number,
> card_no, pan, etc., plus maskRow(map). Add AuditService that appends masked auth/chat/SQL
> events to logs/audit.log.
> ```
> **Why it worked:** it lists the exact key names to match, so masking is deterministic.

### A10. Frontend with the right Angular primitives

> **Prompt**
> ```
> Angular 21 standalone app with signals. Pages: login, dashboard, chat. Add:
> - an HTTP interceptor that attaches Authorization: Bearer <token> and, on 401, logs out and
>   redirects to /login;
> - an authGuard for protected routes;
> - environment files where dev points at http://localhost:8080 and production uses '' (nginx proxy).
> ```
> **Why it worked:** "standalone + signals + interceptor + guard + env files" is the whole shape;
> it maps 1:1 to the files that exist and avoids NgModule boilerplate.

### A11. Docker with a health-gated dependency

> **Prompt**
> ```
> Write docker-compose.yml with db (mysql:8.4, healthcheck), backend (build ./Backend/demo,
> SPRING_PROFILES_ACTIVE=docker, depends_on db: condition service_healthy) and frontend
> (nginx serving the built SPA, proxying /auth,/chat,/api,/actuator to backend:8080). Map MySQL
> to host 3307 to avoid clashing with a local 3306.
> ```
> **Why it worked:** the healthcheck + `condition: service_healthy` eliminates the classic
> "backend starts before MySQL is ready" race; the port remap avoids a real conflict.

### A12. A verifiable end-to-end script

> **Prompt**
> ```
> Write scripts/e2e.ps1 that runs against a live stack and checks: health, login, bad-password 401,
> no-token 401, register, empty accounts for a new user, balance/cards/loans, a POLICY question
> (grounded + citations), a USER_SQL question (masked), a REFUSE case, cross-user scoping,
> admin-forbidden 403, and admin ingest. Print PASS/FAIL per check and exit non-zero on any failure.
> ```
> **Why it worked:** an explicit checklist + non-zero exit makes the whole system continuously
> verifiable and CI-friendly.

---

## Part B — Prompts that would NOT work (and why)

### B1. "Build a banking app with AI."

- **What happens:** vague, un-actionable output; you can't tell when it's "done".
- **Why:** no stack, no scope, no acceptance criteria → the model guesses everything.
- **Instead:** A1–A12 style — pin stack, list components, state the acceptance test.

### B2. "Use LangChain4j with a Pinecone vector store."

- **What happens:** heavy dependency tree, an external service to sign up for and pay for, and
  version-specific SDK APIs that silently drift between releases.
- **Why:** the corpus is **one 39-page PDF (69 chunks)**. A managed vector DB and an LLM
  orchestration framework are orders of magnitude more machinery than the problem needs.
- **Instead:** direct Gemini REST + a tiny in-memory cosine store (A5/A6). Add pgvector/Pinecone
  only if the corpus grows and persistence is required.

### B3. "Use `gemini-2.5-flash` and `text-embedding-004`."

- **What happens:** `404 model not found` / `not available for new users`. Ingestion yields
  `embeddingsCreated=false`, and answers silently degrade.
- **Why:** Google **retires model IDs** and gates some behind account age/region. Hard-coding a
  model name from memory/older docs is fragile.
- **Instead:** discover available models (`ListModels` / AI Studio), make the model
  **configurable**, and add an ordered fallback chain (A6). In this repo:
  `gemini-flash-latest` + `gemini-embedding-001`.

### B4. "Let the assistant answer personal questions from the policy documents."

- **What happens:** hallucinated balances, and even if it works, it exposes nothing real — the
  PDF has no customer data.
- **Why:** personal data isn't in the knowledge corpus; it's in **MySQL**. Mixing the two
  retrieval paths invites hallucination and leakage.
- **Instead:** route personal questions to **scoped SQL** (A8); keep the document path for
  policies only.

### B5. "Generate SQL from the question and run it."

- **What happens:** arbitrary SQL executes — `DROP TABLE`, cross-user reads, `password` dumps,
  or multi-statement injection.
- **Why:** an LLM is not a security boundary; a prompt ("only SELECT") is a *request*, not a
  *guarantee*.
- **Instead:** validate **after** generation with a guard that enforces SELECT-only, table
  allow-list, mandatory `user_id = <caller>` and a LIMIT (A8).

### B6. "The prompt says use `user_id = <caller>`, so we don't need extra checks."

- **What happens:** a prompt-injected or garbled response omits the clause → another user's rows
  are returned.
- **Why:** same reason as B5 — **enforce, don't trust**.
- **Instead:** `SqlGuard` *rejects* any statement missing the scope. Defense in depth.

### B7. "Set `spring.jpa.hibernate.ddl-auto=create` so tables always exist."

- **What happens:** every restart **drops and recreates** tables → the seeded/registered data
  vanishes.
- **Why:** `create`/`create-drop` are destructive; fine for throwaway unit tests, disastrous for
  a running demo.
- **Instead:** `ddl-auto=update` + an explicit `db/init.sql` schema. In real deployments use
  **Flyway/Liquibase** migrations.

### B8. "Put the Gemini API key in the frontend so the browser can call Gemini."

- **What happens:** the key is shipped to every visitor; anyone can read it and burn your quota.
- **Why:** anything in the SPA bundle is public. Keys belong on a server you control.
- **Instead:** the browser only calls **your** backend; the backend holds the key and talks to
  Gemini. Keep secrets in `.env` (git-ignored).

### B9. "Return the account number and card number in the API/answers."

- **What happens:** PII leaks into logs, browser history and the LLM context.
- **Why:** full identifiers are rarely needed; last-4 is enough to disambiguate.
- **Instead:** `MaskingUtil` reduces to `****<last4>` before output (A9).

### B10. "Add `@CrossOrigin(origins = "*")` everywhere."

- **What happens:** broad, credentialed CORS; harder to reason about in production.
- **Why:** wildcard origins + credentials is a common security smell, and it papers over the
  deploy topology.
- **Instead:** in dev, allow the known origin (`http://localhost:4200`); in prod, let **nginx
  proxy** the API so the browser sees a single origin and CORS is irrelevant.

### B11. "Let Spring Security handle errors however it wants."

- **What happens:** a forbidden request returns **401 instead of 403** (or a generic error page).
- **Why:** two real issues surfaced here: (1) error responses are dispatched to `/error`, and if
  the security chain itself blocks `/error` it **masks the original status**; (2) a filter that's
  also auto-registered as a servlet filter runs **twice**, so the security context looks
  anonymous and forbidden requests degrade to 401.
- **Instead:** permit `/error`, set an explicit `accessDeniedHandler`, and disable the filter's
  duplicate registration (`FilterRegistrationBean#setEnabled(false)`). This is the exact fix in
  `SecurityConfig`.

### B12. "Block SQL keywords with `contains()` / substring matching."

- **What happens:** legitimate queries are rejected — `updated_at` contains "update",
  `created_at` contains "create".
- **Why:** naive substring matching produces **false positives** on identifiers.
- **Instead:** match keywords on **word boundaries** (`\bupdate\b`) and keep a separate
  substring list only for genuinely dangerous fragments (`into outfile`, `sleep(`).

### B13. "One mega-prompt: build the backend, frontend, Docker and tests."

- **What happens:** a huge, unreviewable blob; failures are hard to localise and the model's
  assumptions compound.
- **Why:** you can't **verify incrementally**, so you can't steer.
- **Instead:** build and test **one layer at a time** (A1→A12), keeping the app runnable
  throughout.

### B14. "Call a single model and skip retries — simpler is better."

- **What happens:** transient `503 (model overloaded)` / `429` → the user sees an error.
- **Why:** hosted models are not always available; treating a transient error as fatal is brittle.
- **Instead:** a **fallback chain + retry on transient codes** (A6). This is what upgraded the
  experience from "sometimes fails" to "answers via a lighter model".

### B15. "Use `@Autowired` field injection — it's shorter."

- **What happens:** harder to unit-test (fields can't be set in a test constructor) and it hides
  a class's dependencies.
- **Why:** field injection breaks the constructor-injection contract Spring recommends.
- **Instead:** **constructor injection** for every bean (as throughout this codebase).

### B16. "Store the JWT in localStorage and never expire it."

- **What happens:** a stolen token is valid forever; no server-side revocation.
- **Why:** unbounded token lifetime removes your only easy mitigation.
- **Instead:** set an expiry (`JWT_EXPIRATION`, default 24h), log out + redirect on `401`
  (interceptor), and treat the token as short-lived. (Refresh tokens are a future improvement.)

### B17. "Embed all 69 chunks in a single request."

- **What happens:** large payloads can hit request limits and are harder to retry partially.
- **Why:** one failure wastes the whole call, and free-tier quotas are per-request.
- **Instead:** **batch** embeddings (this repo uses batches of 50) so a failure is contained and
  progress is incremental.

### B18. "Skip the tests / never run the tests — we'll add them later."

- **What happens:** regressions ship unnoticed; the E2E suite is exactly what caught the 401/403
  and multipart bugs here.
- **Why:** tests are the only objective "it works" signal.
- **Instead:** keep unit tests (**16**) green and run the E2E script (**16 checks**) before
  calling anything done.

---

## Part C — Reusable prompt template

Copy this shape for each component you add:

```
Context: <stack + versions + where it lives>. 
Build: <one component, named>.
Contract: <inputs, outputs, error/status behaviour>.
Constraints: <security, performance, resilience rules>.
Failure behaviour: <what happens when a dependency is down / input is bad>.
Verify: <tests to add or the command/script that proves it works>.
```

**Example (a future feature — persisted embeddings):**

```
Context: Spring Boot 3.3.5 backend, in-memory vector store in com.example.demo.store.
Build: a persistent embedding cache so chunks are not re-embedded on every restart.
Contract: on startup, if cache is fresh (same PDF hash) load vectors; else embed and save.
Constraints: store init.sql-compatible rows; no new external service.
Failure behaviour: if the cache is corrupt, rebuild from the PDF.
Verify: add a unit test for cache hit/miss and assert /health still reports indexedChunks.
```

---

## Part D — The one-line summary

- **Prompts that worked:** specific, versioned, security-aware, resilient-by-design, and paired
  with a **verification step**.
- **Prompts that didn't:** vague scope, hard-coded model names, frameworks/infra far larger than
  the problem, "trust the model" security, destructive defaults, secrets in the client, and no
  way to test.
