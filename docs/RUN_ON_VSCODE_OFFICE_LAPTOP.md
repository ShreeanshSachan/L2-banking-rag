# Running the Project on an Office Laptop (VS Code)

This guide takes you from a clean office laptop to a running Secure Bank RAG stack, using
**Visual Studio Code** as the primary editor. It covers two paths:

- **Option A — Docker (recommended):** one command runs everything. Nothing but Docker is
  installed on your machine.
- **Option B — Local toolchain:** run the database, backend and frontend natively (better for
  breakpoint debugging with the VS Code Java debugger).

> Replace `<repo-url>` below with `https://github.com/ShreeanshSachan/L2-banking-rag.git`.

---

## 0. What you need (summary)

| Path | You must install | You do **not** need |
|---|---|---|
| **Option A (Docker)** | Docker Desktop | Java, Node, Maven, MySQL |
| **Option B (Local)** | JDK 17, Node 20/22, MySQL 8 (or the DB container) | Maven (the `mvnw` wrapper is bundled) |

Both paths need: **VS Code**, **Git**, and a **Google Gemini API key**.

---

## 1. Prerequisites

### 1.1 Git

```powershell
git --version        # expect 2.x
```

If missing: <https://git-scm.com/downloads> (or `winget install Git.Git`).

### 1.2 Docker Desktop (Option A)

1. Download and install Docker Desktop: <https://www.docker.com/products/docker-desktop/>.
2. On first launch it will ask to enable **WSL 2**. Accept it.
3. If WSL is not installed, run in an **admin** PowerShell once, then reboot:
   ```powershell
   wsl --install --no-distribution
   ```
4. Verify:
   ```powershell
   docker --version
   docker compose version
   ```

> **Office machines:** Docker Desktop needs **admin rights** and **hardware virtualization**
> enabled in BIOS/Windows Features (Virtual Machine Platform + WSL). If IT blocks this, use
> **Option B**.

### 1.3 Local toolchain (Option B)

| Tool | Version | Install (Windows) |
|---|---|---|
| JDK 17 | Temurin 17 | `winget install EclipseAdoptium.Temurin.17.JDK` |
| Node.js | 20 or 22 LTS | `winget install OpenJS.NodeJS.LTS` |
| MySQL 8 | 8.x | `winget install Oracle.MySQL` **or** run just the DB container |

Verify:

```powershell
java -version         # must print 17.x
node -v               # v20.x or v22.x
npm -v
```

### 1.4 A Gemini API key

Create one at <https://aistudio.google.com/app/apikey>. Copy it — you'll put it in `.env`.
(Without a key the app still runs, but answers degrade to keyword search + excerpts.)

---

## 2. Get the code

```powershell
git clone <repo-url>
cd L2
code .
```

The last command opens the folder in VS Code (there is **no** workspace file — open the repo
root so relative paths like `db/init.sql` and `./Backend/demo` resolve).

---

## 3. Configure secrets

```powershell
Copy-Item .env.example .env
```

Edit `.env` and set at least:

```dotenv
GEMINI_API_KEY=your-real-key-here
JWT_SECRET=change-me-to-a-random-string-of-at-least-32-characters
```

`.env` is git-ignored — never commit it. Both Docker Compose and the local run read it.

---

## 4. VS Code extensions

Install from the Extensions view (`Ctrl+Shift+X`):

| Extension | ID | Needed for |
|---|---|---|
| Extension Pack for Java | `vscjava.vscode-java-pack` | Backend editing, run/debug |
| Spring Boot Extension Pack | `vmware.vscode-boot-dev-pack` | Spring Boot Dashboard, `application.yml` support |
| Angular Language Service | `angular.ng-template` | Angular templates |
| Docker | `ms-azuretools.vscode-docker` | Manage containers from VS Code |
| ESLint (optional) | `dbaeumer.vscode-eslint` | Linting |
| Prettier (optional) | `esbenp.prettier-vscode` | Formatting |
| MySQL (optional) | `cweijan.vscode-mysql-client2` | Browse the DB visually |

---

## 5. Option A — Run everything with Docker (recommended)

From the repo root:

```powershell
docker compose up --build
```

- First run builds images (Maven + npm downloads) — a few minutes.
- When done, open **<http://localhost:4200>** and sign in.

Useful commands:

```powershell
docker compose ps               # status
docker compose logs -f backend  # follow backend logs
docker compose down             # stop (keep DB data)
docker compose down -v          # stop and WIPE the database volume
```

Confirm the backend:

```powershell
curl http://localhost:8080/health
# {"status":"UP","llmConfigured":true,"indexedChunks":69,...}
```

---

## 6. Option B — Run locally in VS Code

### 6.1 Start the database

Easiest — reuse the containerised MySQL (exposed on host **3307**):

```powershell
docker compose up -d db
```

Or use a locally installed MySQL 8 on the default **3306**.

> **Port gotcha:** `docker-compose.yml` maps MySQL to host **3307** (to avoid clashing with a
> local MySQL on 3306), but the backend's default `DB_URL` points at `localhost:3306`. When
> running the backend **locally** against the DB container, override `DB_URL` (see below).

### 6.2 Run the backend

**Easiest:** open the **Spring Boot Dashboard** in the activity bar, expand `demo`, click ▷ Run.
The dashboard uses the env vars from your current shell.

**From the integrated terminal:**

```powershell
cd Backend\demo
$env:GEMINI_API_KEY = (Get-Content ..\..\.env | Where-Object { $_ -match '^GEMINI_API_KEY=' }) -replace '^GEMINI_API_KEY=',''
$env:DB_URL = "jdbc:mysql://localhost:3307/banking?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
.\mvnw.cmd spring-boot:run
```

Backend → <http://localhost:8080>. Run the tests with:

```powershell
.\mvnw.cmd test
```

### 6.3 Run the frontend

Open a second terminal:

```powershell
cd Frontend\Banking_Frontend
npm install
npm run start
```

Frontend → <http://localhost:4200>, calling the backend at <http://localhost:8080>
(`src/environments/environment.ts`).

---

## 7. Recommended VS Code workspace files (optional)

Create a `.vscode/` folder to make running one-click.

**`.vscode/launch.json`** — debug the backend and attach to the Angular dev server:

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "java",
      "name": "Spring Boot: demo",
      "request": "launch",
      "mainClass": "com.example.demo.DemoApplication",
      "projectName": "demo",
      "envFile": "${workspaceFolder}/.env",
      "env": {
        "DB_URL": "jdbc:mysql://localhost:3307/banking?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
      }
    },
    {
      "type": "chrome",
      "name": "Angular: Chrome",
      "request": "launch",
      "url": "http://localhost:4200",
      "webRoot": "${workspaceFolder}/Frontend/Banking_Frontend"
    }
  ]
}
```

**`.vscode/tasks.json`** — helper tasks:

```json
{
  "version": "2.0.0",
  "tasks": [
    { "label": "db: up",        "type": "shell", "command": "docker compose up -d db" },
    { "label": "backend: run",  "type": "shell", "command": "cd Backend/demo; .\\mvnw.cmd spring-boot:run" },
    { "label": "backend: test", "type": "shell", "command": "cd Backend/demo; .\\mvnw.cmd test", "group": "test" },
    { "label": "frontend: run", "type": "shell", "command": "cd Frontend/Banking_Frontend; npm run start" },
    { "label": "docker: up",    "type": "shell", "command": "docker compose up --build" },
    { "label": "e2e",           "type": "shell", "command": "powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/e2e.ps1" }
  ]
}
```

---

## 8. Demo logins & sample data

| Username | Password | Role |
|---|---|---|
| `alice` | `password123` | USER (2 accounts, 2 cards, 2 loans) |
| `admin` | `admin123` | ADMIN (ops account; also seeded with sample data) |
| `rahul`, `priya`, `arjun`, `sneha`, `vikram`, `ananya`, `rohit`, `kavya`, `imran`, `neha`, `dev`, `meera` | `password123` | USER (dummy customers) |

Every **new registration** is auto-provisioned with starter accounts/cards/loans.

---

## 9. Verify the running stack

```powershell
# health
curl http://localhost:8080/health

# full end-to-end suite (16 checks)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e.ps1
```

The E2E script expects a running stack and prints `TOTAL: 16 passed, 0 failed`. It also writes
`logs/audit.log` entries as it exercises the API.

---

## 10. Common tasks

| Task | Command |
|---|---|
| Rebuild only the backend | `docker compose up -d --build backend` |
| Wipe DB and reseed fresh | `docker compose down -v; docker compose up -d --build` |
| Run backend tests | `cd Backend\demo; .\mvnw.cmd test` |
| Production frontend build | `cd Frontend\Banking_Frontend; npm run build` |
| View container logs | `docker compose logs -f backend` |
| Open a MySQL shell | `docker compose exec db mysql -ubankuser -pbankpass banking` |

---

## 11. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Dashboard shows "No accounts found" | Logged in as a user with no data | Log in as `alice`; or register a user (auto-provisioned) |
| `/health` says `llmConfigured:false` | `GEMINI_API_KEY` empty | Set it in `.env` and restart backend |
| Policy answers look like raw excerpts | Embeddings/generation failed (`429`/`503`) | Free-tier limit; wait ~1 min, or use a paid key |
| Backend can't reach MySQL locally | DB on 3307 but `DB_URL` says 3306 | Set `DB_URL` to `...localhost:3307...` |
| Port already in use | Something on 3306/8080/4200 | Change host ports in `docker-compose.yml`, or stop the other process |
| `db` container unhealthy at start | MySQL still booting | Wait; backend waits for the healthcheck |
| VS Code Java features not working | JDK/extension not picked up | Command Palette → "Java: Clean Java Language Server Workspace" |
| Clone/build blocked | Corporate proxy/firewall | Configure proxy for Git/Docker/npm; API calls need outbound HTTPS |

---

## 12. Office-laptop checklist

- [ ] Git installed
- [ ] Docker Desktop with **WSL 2** enabled — *or* JDK 17 + Node 20/22 + MySQL 8
- [ ] Hardware virtualization enabled (BIOS/Windows Features) for Docker or WSL
- [ ] VS Code + the extensions from §4
- [ ] Repo cloned and opened as the workspace root
- [ ] `.env` created with a real `GEMINI_API_KEY` and a ≥ 32-char `JWT_SECRET`
- [ ] Outbound HTTPS allowed to `generativelanguage.googleapis.com` (and image registries)
- [ ] `docker compose up --build` (Option A) starts 3 healthy containers
- [ ] <http://localhost:4200> loads and `alice/password123` shows data
- [ ] `scripts/e2e.ps1` reports 16/16

---

## 13. Where to look next

- **System internals** → [`SYSTEM_DESIGN.md`](SYSTEM_DESIGN.md)
- **How this was built / prompting** → [`BUILD_PROMPTS.md`](BUILD_PROMPTS.md)
- **Project overview & API** → [`../README.md`](../README.md)
