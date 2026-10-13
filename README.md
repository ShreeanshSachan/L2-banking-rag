# Secure Bank - AI-Powered Banking Support System (RAG)

A full-stack **Retrieval-Augmented Generation (RAG)** assistant for a fictitious bank.
It answers customer questions grounded in the official **Secure Bank policy manual**, and
can also answer personal questions ("what is my balance?") by translating intent into a
**safe, user-scoped SQL query**.

- **Backend:** Spring Boot 3.3.5 (Java 17), Spring Security + JWT, Spring Data JPA, MySQL
- **Frontend:** Angular 21 (standalone components, signals)
- **LLM / Embeddings:** Google **Gemini** REST API (`gemini-flash-latest` + `gemini-embedding-001`)
- **Vector store:** in-memory, rebuilt from the policy PDF on startup
- **Packaging:** Docker + docker-compose (MySQL, backend, nginx-served frontend)

> This is a learning/reference project. Do not run it with real customer data.

---

## Architecture

```
Browser (Angular)
   │  JWT
   ▼
nginx (frontend container)  ──proxy /auth,/chat,/api──▶  Spring Boot backend
                                                             │
                                       ┌─────────────────────┼─────────────────────┐
                                       ▼                     ▼                     ▼
                                  MySQL (JPA)        InMemoryVectorStore      Gemini API
                                  users/accounts      (policy chunks)      (chat + embeddings)
                                  cards/loans
```

**Request flow for `POST /chat/ask`:**

1. Authenticate via JWT; resolve the caller's `userId`.
2. **Intent routing** (`RagService`):
   - *Policy question* → embed query → cosine search over policy chunks → Gemini answer **with citations**.
   - *Personal data question* → Gemini proposes a SELECT → `SqlGuard` validates it → execute scoped to the user.
   - *Unsafe / out-of-scope request* → politely refused.
3. **Masking** (`MaskingUtil`) scrubs account-number-like tokens from the outgoing answer.
4. **Audit** (`AuditService`) writes an entry (masked) to `logs/audit.log`.

### Safety: `SqlGuard`
Only `SELECT` statements are allowed, restricted to an allow-list of tables
(`users, accounts, cards, loans`), must reference `user_id = <caller>`, comments/semicolons
are stripped, and results are capped with `LIMIT 100`. Anything else is rejected.

---

## Repository layout

```
L2/
├─ Backend/demo/            Spring Boot application (Maven, mvnw wrapper)
│  ├─ docs/                 policy PDF used as the RAG knowledge base
│  └─ src/main/java/...     config / entity / repository / dto / security / store / service / controller
├─ Frontend/Banking_Frontend/   Angular 21 app
├─ db/init.sql              MySQL schema (runs on first DB start)
├─ docker-compose.yml       db + backend + frontend
├─ .env.example             template for secrets/config
└─ README.md
```

---

## Quick start (Docker - recommended)

Requirements: **Docker Desktop**.

```powershell
# 1. Configure secrets
Copy-Item .env.example .env
#    then edit .env and set GEMINI_API_KEY (and ideally JWT_SECRET)

# 2. Build & run everything
docker compose up --build
```

Open **http://localhost:4200** and sign in with a demo account (below).

| Service   | URL                              |
|-----------|----------------------------------|
| Frontend  | http://localhost:4200            |
| Backend   | http://localhost:8080            |
| Health    | http://localhost:8080/health     |
| MySQL     | localhost:3306 (`banking`)       |

---

## Local development (without Docker)

You still need a MySQL 8 instance. Easiest: start only the database.

```powershell
docker compose up -d db
```

### Backend

```powershell
cd Backend\demo
$env:GEMINI_API_KEY = "your-key"
.\mvnw.cmd spring-boot:run
```

- Runs on http://localhost:8080.
- Defaults: DB `jdbc:mysql://localhost:3306/banking`, user `bankuser` / `bankpass`.
- Policy PDF path defaults to `./docs/Secure_Bank_policy_Manual_input-docs.pdf`
  (override with `POLICY_PDF`).
- **If `GEMINI_API_KEY` is empty**, the app still starts: retrieval and answers degrade
  gracefully to keyword search + document excerpts, and personal-data replies explain
  that the language model is disabled.

Run tests:

```powershell
cd Backend\demo
.\mvnw.cmd test
```

### Frontend

```powershell
cd Frontend\Banking_Frontend
npm install
npm run start
```

- Runs on http://localhost:4200 and calls the backend at http://localhost:8080
  (`src/environments/environment.ts`).
- Production build: `npm run build` → `dist/Banking_Frontend/browser`.

---

## Demo credentials

| Username | Password      | Role       |
|----------|---------------|------------|
| `alice`  | `password123` | ROLE_USER  |
| `admin`  | `admin123`    | ROLE_ADMIN |

Users, accounts, cards and loans are seeded automatically by the backend on first start.

### Dummy customers

On startup the backend also seeds **12 dummy customers** (each with 1–3 accounts, 0–2 cards and 0–2 loans)
so the dashboard and RAG examples have plenty of data:

| Usernames                                    | Password      | Role      |
|----------------------------------------------|---------------|-----------|
| `rahul`, `priya`, `arjun`, `sneha`, `vikram`, `ananya`, `rohit`, `kavya`, `imran`, `neha`, `dev`, `meera` | `password123` | ROLE_USER |

Seeding is **idempotent** (existing users are left untouched) and configurable:

```bash
SEED_ENABLED=true          # set false to start with an empty database
SEED_DUMMY_CUSTOMERS=12    # how many dummy customers, 0 to disable
SEED_PROVISION_ON_REGISTER=true   # new sign-ups get starter accounts/cards/loans
```

Registered users are **auto-provisioned** with starter data (1–2 accounts, a card and often a loan)
so the dashboard is never empty. Disable with `SEED_PROVISION_ON_REGISTER=false`.

---

## API reference

All endpoints except `/auth/**` and `/health` require `Authorization: Bearer <token>`.

| Method | Path                     | Auth   | Description                              |
|--------|--------------------------|--------|------------------------------------------|
| POST   | `/auth/register`         | public | Create user, returns JWT                 |
| POST   | `/auth/login`            | public | Login, returns JWT                       |
| POST   | `/chat/ask`              | user   | Ask the RAG assistant                    |
| GET    | `/api/me/balance`        | user   | Current user's accounts                  |
| GET    | `/api/me/cards`          | user   | Current user's cards                     |
| GET    | `/api/me/loans`          | user   | Current user's loans                     |
| POST   | `/api/admin/ingest`      | admin  | Re-ingest the policy PDF                 |
| GET    | `/health`                | public | Liveness + vector-store status           |

### Examples

```powershell
# Login
curl -X POST http://localhost:8080/auth/login `
  -H "Content-Type: application/json" `
  -d '{"username":"alice","password":"password123"}'
# → { "token": "eyJ...", "username": "alice", "userId": 1, "roles": "ROLE_USER", ... }

# Ask a policy question
curl -X POST http://localhost:8080/chat/ask `
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" `
  -d '{"message":"What is the KYC checklist for onboarding?"}'

# Ask about personal data (routed to safe, scoped SQL)
curl -X POST http://localhost:8080/chat/ask `
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" `
  -d '{"message":"What is my account balance?"}'
```

`/chat/ask` response:

```json
{
  "answer": "…",
  "intent": "POLICY_DOC | USER_SQL | REFUSE",
  "citations": [{ "source": "Secure_Bank_policy_Manual_input-docs.pdf", "page": 4, "score": 0.83, "snippet": "…" }],
  "latencyMs": 812,
  "masked": false,
  "grounded": true
}
```

---

## Configuration

All settings are environment-overridable (see `.env.example` and `application.yml`).

| Variable                | Default                        | Notes                                  |
|-------------------------|--------------------------------|----------------------------------------|
| `GEMINI_API_KEY`        | *(empty)*                      | Enables LLM answers + embeddings       |
| `GEMINI_CHAT_MODEL`     | `gemini-flash-latest`          | Primary chat model                     |
| `GEMINI_CHAT_MODEL_FALLBACKS` | `gemini-3.1-flash-lite,gemini-3.5-flash,gemini-3.8-flash` | Tried in order when the primary returns 503/429/404 |
| `GEMINI_EMBEDDING_MODEL`| `gemini-embedding-001`         | 3072-dim embeddings                    |
| `JWT_SECRET`            | dev placeholder                | **≥ 32 chars** in production           |
| `DB_URL` / `DB_USER` / `DB_PASS` | localhost MySQL        | docker profile uses the `db` host      |
| `POLICY_PDF`            | `./docs/Secure_Bank_policy_Manual_input-docs.pdf` | RAG source      |
| `RAG_TOP_K`             | `5`                            | Chunks retrieved per query             |
| `CORS_ALLOWED_ORIGINS`  | `http://localhost:4200,http://localhost` |                              |

---

## Troubleshooting

- **Dict/labeled answer says "language model disabled"** → `GEMINI_API_KEY` not set.
- **Frontend 401s then redirect to login** → token expired/invalid; log in again.
- **`db` unhealthy at startup** → give MySQL a few seconds; backend waits for the healthcheck.
- **Backend can't reach MySQL locally** → ensure `docker compose up -d db` is running and port 3306 is free.
- **Port already in use** → change the host ports in `docker-compose.yml`.

---

## License

For educational/demo use only.
