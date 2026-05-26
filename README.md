<div align="center">

![header](https://readme-typing-svg.demolab.com?font=Fira+Code&size=13&duration=1&pause=999999&color=6366F1&center=true&vCenter=true&repeat=false&width=600&lines=⚡+AI-POWERED+·+TASK+·+QUEUE+⚡)

![title](https://readme-typing-svg.demolab.com?font=Orbitron&weight=900&size=52&duration=1&pause=999999&color=FFFFFF&center=true&vCenter=true&repeat=false&width=800&lines=TaskQueue+AI)

![subtitle](https://readme-typing-svg.demolab.com?font=Fira+Code&size=12&duration=1&pause=999999&color=8B5CF6&center=true&vCenter=true&repeat=false&width=700&lines=A+Production-Grade+Multi-Threaded+Task+Queue+System+with+Real-Time+AI+Prioritization)

<br/>

[![Java](https://img.shields.io/badge/Java-17-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/17/)
[![Maven](https://img.shields.io/badge/Maven-Build-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![Groq AI](https://img.shields.io/badge/Groq-AI%20Powered-6B48FF?style=for-the-badge&logo=openai&logoColor=white)](https://groq.com/)
[![JUnit 5](https://img.shields.io/badge/JUnit-5-25A162?style=for-the-badge&logo=junit5&logoColor=white)](https://junit.org/junit5/)
[![License](https://img.shields.io/badge/License-MIT-blue?style=for-the-badge)](LICENSE)

<br/>

> **Built from scratch. No Spring Boot. No shortcuts. Just raw Java engineering.**

<br/>

---

### 🧑‍💻 Crafted by

<br/>

<table>
<tr>
<td align="center">

[![Devanshu](https://img.shields.io/badge/-%F0%9F%91%A8%E2%80%8D%F0%9F%92%BB%20Devanshu%20Sharma-1d4ed8?style=for-the-badge&logoColor=white)](https://github.com/)

![role](https://img.shields.io/badge/⚙%20Backend%20Engineer%20%26%20Thread%20Pool%20Architect-0ea5e9?style=flat-square&logoColor=white)

</td>
<td align="center" width="60">

![and](https://img.shields.io/badge/%26-1e293b?style=for-the-badge)

</td>
<td align="center">

[![Sanmati](https://img.shields.io/badge/-%F0%9F%91%A9%E2%80%8D%F0%9F%92%BB%20Sanmati%20Jain-7c3aed?style=for-the-badge&logoColor=white)](https://github.com/)

![role](https://img.shields.io/badge/✦%20AI%20Integration%20%26%20Priority%20Engine-8b5cf6?style=flat-square&logoColor=white)

</td>
</tr>
</table>

</div>

---

## 📌 What Is This?

**TaskQueue AI** is not just another task scheduler demo. It is a fully functional, production-inspired backend system that combines **deep Java concurrency engineering** with a **live AI brain** that thinks about your tasks before they ever touch the queue.

Every piece of this system — the thread pool, the worker lifecycle, the priority engine, the REST API — was built by hand, using only Core Java. No frameworks. No magic. Pure engineering.

---

## 🚀 What Makes This Stand Out

> *"Anyone can call `Executors.newFixedThreadPool()`. We built what's inside it."*

### ✦ We Built the Thread Pool from Zero

Most Java projects use `ExecutorService` and call it a day. We didn't. We implemented every component from scratch:

- **`WorkerThread.java`** — extends `Thread`, continuously polls a `PriorityBlockingQueue`, handles its own retry logic and backoff delays
- **`ThreadPool.java`** — manages the lifecycle of N worker threads, tracks active count via `AtomicInteger`, supports graceful shutdown that finishes in-flight tasks before rejecting new ones
- **No `Executors`, no `ForkJoinPool`, no framework abstractions** — full visibility into every thread at every moment

This is the difference between *using* concurrency primitives and *understanding* them well enough to rebuild them.

---

### ✦ AI That Thinks Before the Queue Does

Before any task enters the queue, it passes through our **`AIPrioritizationService`** — a real-time AI decision engine powered by the Groq API:

- Sends task metadata (type, deadline, payload) + current system state (queue depth, active threads, recent completion rate) to an LLM
- Receives a structured JSON response: suggested priority (1–10), recommendation (`URGENT` / `NORMAL` / `DEFER`), estimated wait time, warnings
- Automatically applies the decision — URGENT tasks jump to priority 10 and front of queue; DEFER tasks get flagged with warnings
- Every decision is logged in an `AIDecisionLog` with full traceability

This is not a UI gimmick. The AI actually changes execution order.

---

### ✦ Smart Enough Not to Burn API Credits

We built a **cost-aware AI layer**:

- AI is called **once per task submission** — never in polling loops
- Responses are **cached for 30 seconds** per task-type + queue-state combo to avoid duplicate calls
- Prompts are kept **under 500 tokens** by design
- If the Groq API is unavailable, the system **falls back to a deterministic rule-based priority engine** so nothing breaks
- Every API call logs token usage to the console for full credit visibility

---

### ✦ Real Retry Logic with Exponential Backoff

Tasks don't just fail silently. Our execution engine:

- Catches exceptions during task execution
- Increments `retryCount`, stores the failure reason in the task object
- Re-queues the task with exponential backoff: delay = 2^retryCount seconds
- After `maxRetries` (3) attempts, marks the task as `FAILED` permanently

This matches how real production systems handle transient failures.

---

### ✦ Priority-Ordered Execution

The internal queue is a **`PriorityBlockingQueue<Task>`** with a custom comparator. Higher priority tasks always execute first — including when new high-priority tasks arrive while lower-priority ones are waiting. This is not a simple FIFO queue with a label. Priority is structurally enforced.

---

### ✦ Fully Thread-Safe — By Design

Every shared data structure was chosen and synchronized deliberately:

| Component | Thread-Safety Mechanism |
|---|---|
| Task storage | `ConcurrentHashMap<UUID, Task>` |
| Queue | `PriorityBlockingQueue<Task>` |
| Thread count | `AtomicInteger` |
| Task status updates | `synchronized` methods |
| AI decision log | `ConcurrentHashMap` |

No two threads can ever process the same task.

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         CLIENT (Browser)                            │
│                     Dashboard · REST API Calls                      │
└──────────────────────────────┬──────────────────────────────────────┘
                               │  HTTP :8080
┌──────────────────────────────▼──────────────────────────────────────┐
│                     Java HttpServer (Raw)                           │
│         TaskHandler · QueueHandler · AIInsightHandler               │
└──────┬───────────────────────┬───────────────────────┬─────────────┘
       │                       │                       │
┌──────▼──────┐    ┌───────────▼────────┐   ┌─────────▼────────────┐
│ TaskService │    │AIPrioritization    │   │  Queue Stats Engine  │
│             │    │Service             │   │                      │
│ CRUD · State│    │Groq API · Cache    │   │ Live metrics · Logs  │
│ Management  │    │Fallback Rules      │   │                      │
└──────┬──────┘    └───────────┬────────┘   └──────────────────────┘
       │                       │
       └───────────┬───────────┘
                   │
┌──────────────────▼───────────────────────────────────────────────┐
│              PriorityBlockingQueue<Task>                          │
│         [ P10 ]──[ P9 ]──[ P9 ]──[ P7 ]──[ P5 ]──[ P1 ]        │
└──────────────────┬───────────────────────────────────────────────┘
                   │  Poll (blocking)
┌──────────────────▼───────────────────────────────────────────────┐
│                   Custom Thread Pool                              │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌───────────┐  │
│  │ WorkerThread│ │ WorkerThread│ │ WorkerThread│ │WorkerThread│  │
│  │    #1 BUSY  │ │    #2 BUSY  │ │   #3 IDLE   │ │  #4 BUSY  │  │
│  └──────┬──────┘ └──────┬──────┘ └─────────────┘ └─────┬─────┘  │
└─────────│───────────────│──────────────────────────────│─────────┘
          │               │                              │
┌─────────▼───────────────▼──────────────────────────────▼─────────┐
│                     TaskExecutor                                  │
│   EMAIL(1-2s) · PAYMENT(2-3s) · REPORT(3-5s) · NOTIFY(0.5-1s)   │
│                     DATA_SYNC(2-4s)                               │
│              Retry → Exponential Backoff → FAILED                 │
└───────────────────────────────────────────────────────────────────┘
```

---

## 📁 Project Structure

```
queue-system/
├── pom.xml
├── README.md
└── src/
    └── main/
        └── java/
            └── com/taskqueue/
                ├── Main.java                          ← Entry point + sample data loader
                ├── server/
                │   └── TaskQueueServer.java           ← Raw Java HttpServer on :8080
                ├── handlers/
                │   ├── TaskHandler.java               ← POST/GET/DELETE /api/tasks
                │   ├── QueueHandler.java              ← GET /api/queue/stats
                │   └── AIInsightHandler.java          ← GET /api/tasks/ai-insights
                ├── services/
                │   ├── TaskService.java               ← Core CRUD + state management
                │   ├── TaskExecutor.java              ← Simulated execution + retry
                │   └── AIPrioritizationService.java  ← Groq API + caching + fallback
                ├── threadpool/
                │   ├── ThreadPool.java                ← Custom thread pool manager
                │   └── WorkerThread.java              ← Custom worker thread
                ├── models/
                │   ├── Task.java                      ← Full task model with all states
                │   ├── TaskType.java                  ← EMAIL/REPORT/PAYMENT/NOTIFY/SYNC
                │   ├── TaskStatus.java                ← PENDING/RUNNING/COMPLETED/FAILED
                │   └── AIDecisionLog.java             ← Full AI decision audit record
                ├── exceptions/
                │   ├── TaskQueueException.java        ← Base exception
                │   ├── TaskNotFoundException.java
                │   ├── QueueFullException.java
                │   ├── TaskExecutionException.java
                │   ├── InvalidTaskException.java
                │   └── ThreadPoolShutdownException.java
                └── utils/
                    ├── JsonUtils.java                 ← JSON marshalling helpers
                    └── ApiConfig.java                 ← API key from environment
```

---

## 🔌 API Reference

### Tasks

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/tasks` | Submit a new task (triggers AI prioritization) |
| `GET` | `/api/tasks` | List all tasks |
| `GET` | `/api/tasks/{id}` | Get a task by ID |
| `DELETE` | `/api/tasks/{id}` | Cancel a pending or running task |
| `GET` | `/api/tasks/status/{status}` | Filter tasks by status |
| `POST` | `/api/tasks/prioritize` | Run AI priority check on a task description |
| `GET` | `/api/tasks/ai-insights` | Retrieve full AI decision log |

### Queue

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/queue/stats` | Full dashboard stats snapshot |

---

### Example: Submit a Task

```bash
curl -X POST http://localhost:8080/api/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Payment Reconciliation",
    "type": "PAYMENT",
    "payload": "{\"batch\": \"Q4-2024\"}",
    "deadline": "2024-12-31T23:59:59"
  }'
```

**Response:**
```json
{
  "taskId": "a3f8e2d1-...",
  "name": "Payment Reconciliation",
  "type": "PAYMENT",
  "priority": 9,
  "status": "PENDING",
  "aiDecision": {
    "suggestedPriority": 9,
    "reasoning": "Payment tasks near deadline require immediate execution to avoid SLA breach.",
    "recommendation": "URGENT",
    "estimatedWaitTime": 4,
    "warnings": []
  }
}
```

---

### Example: Queue Stats

```bash
curl http://localhost:8080/api/queue/stats
```

```json
{
  "totalTasks": 42,
  "pendingTasks": 8,
  "runningTasks": 4,
  "completedTasks": 28,
  "failedTasks": 2,
  "cancelledTasks": 0,
  "activeThreads": 4,
  "queueSize": 8,
  "avgCompletionTimeSeconds": 2.4,
  "tasksByType": {
    "PAYMENT": 12,
    "EMAIL": 15,
    "REPORT": 8,
    "NOTIFICATION": 5,
    "DATA_SYNC": 2
  },
  "aiDecisionsMade": 42,
  "aiUrgentOverrides": 7
}
```

---

### Example: AI Prioritize

```bash
curl -X POST http://localhost:8080/api/tasks/prioritize \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Database Backup",
    "type": "DATA_SYNC",
    "deadline": "2024-12-30T06:00:00"
  }'
```

```json
{
  "suggestedPriority": 6,
  "reasoning": "Backup task with 12hr deadline; moderate urgency given current queue load.",
  "recommendation": "NORMAL",
  "estimatedWaitTime": 45,
  "queuePosition": 3,
  "warnings": ["High queue depth detected — estimated wait may increase"]
}
```

---

## ⚙️ Setup & Installation

### Prerequisites

- Java 17+
- Maven 3.8+
- A free Groq API key → [console.groq.com](https://console.groq.com)

### 1. Clone the Repository

```bash
git clone https://github.com/your-org/taskqueue-ai.git
cd taskqueue-ai/queue-system
```

### 2. Set Your API Key

**Linux / macOS:**
```bash
export GROQ_API_KEY=your_key_here
```

**Windows (PowerShell):**
```powershell
$env:GROQ_API_KEY="your_key_here"
```

> Your API key is never hardcoded anywhere in the source. It is read exclusively from the environment variable `GROQ_API_KEY` via `ApiConfig.java`.

### 3. Build

```bash
mvn clean package
```

### 4. Run

```bash
java -jar target/taskqueue-ai-1.0.jar
```

The server starts on **http://localhost:8080** and automatically loads 5 sample tasks across all types so the queue is live immediately.

### 5. Open the Dashboard

Navigate to **http://localhost:8080** in your browser to open the real-time web dashboard.

---

## 🧪 Running Tests

```bash
mvn test
```

Tests cover task lifecycle, thread pool behavior, retry logic, priority ordering, and API endpoint responses using **JUnit 5**.

---

## 🧠 AI Prioritization — How It Works

```
New Task Submitted
       │
       ▼
┌─────────────────────────────────────────┐
│         Cache Check (30s window)        │
│  Same task type + queue state? → Reuse  │
└──────────────────┬──────────────────────┘
                   │ Miss
                   ▼
┌─────────────────────────────────────────┐
│           Build Prompt                  │
│  Task: { type, deadline, payload }      │
│  Queue: { depth, types, priorities }    │
│  System: { activeThreads, completion/m }│
│  < 500 tokens, strict JSON output       │
└──────────────────┬──────────────────────┘
                   │
                   ▼
         ┌─────────────────┐
         │    Groq API     │
         └────────┬────────┘
    ┌─────────────┴───────────────┐
    │ Success                     │ Failure
    ▼                             ▼
Apply AI decision          Rule-based fallback:
  URGENT → P10             PAYMENT=9, REPORT=7
  NORMAL → as-is           EMAIL=5, NOTIFY=3
  DEFER  → P1 + warn       DATA_SYNC=2
    │
    ▼
Log to AIDecisionLog
(decisionId, taskId, reasoning,
 originalPriority, suggestedPriority,
 recommendation, timestamp)
```

---

## 📊 Dashboard Features

The built-in web dashboard (served at `/`) provides:

- **Live stat cards** — total, pending, running, completed, failed, AI decisions (auto-refresh every 3s)
- **Queue Activity chart** — visual throughput over time
- **Tasks by Type** — animated donut chart
- **Active Threads panel** — per-worker status, current task, progress percentage
- **Task Registry** — full table with priority bars, animated status badges, cancel buttons
- **Queue Monitor** — real-time thread lane visualization + system log
- **AI Insights page** — decision log, on-demand AI analysis, reasoning display
- **Theme switcher** — Default and Obsidian themes

---

## 🧩 Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 17 |
| Build | Maven |
| HTTP Server | `com.sun.net.httpserver.HttpServer` (raw, no framework) |
| Concurrency | Custom `ThreadPool` + `WorkerThread` (no `Executors`) |
| Queue | `PriorityBlockingQueue<Task>` |
| Storage | `ConcurrentHashMap` (in-memory, no database) |
| AI | Groq API (LLM inference) via Java `HttpClient` |
| JSON | `org.json` |
| Frontend | Vanilla HTML, CSS, JavaScript |
| Testing | JUnit 5 |

---

## 🏆 Key Differentiators at a Glance

| Feature | Typical Demo App | TaskQueue AI |
|---------|-----------------|--------------|
| Thread pool | `Executors.newFixedThreadPool()` | Built from scratch with `Thread` |
| Priority queue | FIFO or basic sorting | `PriorityBlockingQueue` + AI override |
| AI integration | None or UI-only | Changes actual execution order |
| Failure handling | None | Retry + exponential backoff + FAILED state |
| API cost control | N/A | Caching + token budgeting + fallback |
| Thread safety | Assumed | `AtomicInteger` + `synchronized` + `ConcurrentHashMap` |
| Shutdown | Kill process | Graceful: finish running tasks, reject new |
| Observability | None | Per-thread status, AI decision log, full stats |

---

## 📬 Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `GROQ_API_KEY` | Yes | Your Groq API key for AI prioritization |

---

## 📄 License

MIT License — see [LICENSE](LICENSE) for details.

---

<div align="center">

**Built with raw Java and a genuine love for systems programming.**

*No frameworks were harmed in the making of this project.*

---

<!-- DevSan footer banner -->
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 500 90" width="500">
  <defs>
    <linearGradient id="footerBg" x1="0%" y1="0%" x2="100%" y2="0%">
      <stop offset="0%" style="stop-color:#0f172a"/>
      <stop offset="100%" style="stop-color:#1e1b4b"/>
    </linearGradient>
    <linearGradient id="devGrad" x1="0%" y1="0%" x2="100%" y2="0%">
      <stop offset="0%" style="stop-color:#3b82f6"/>
      <stop offset="100%" style="stop-color:#8b5cf6"/>
    </linearGradient>
  </defs>
  <rect width="500" height="90" rx="12" fill="url(#footerBg)"/>
  <rect width="500" height="90" rx="12" fill="none" stroke="#334155" stroke-width="1"/>
  <rect x="0" y="0" width="500" height="2.5" rx="1.5" fill="url(#devGrad)"/>
  <rect x="0" y="87.5" width="500" height="2.5" rx="1.5" fill="url(#devGrad)"/>

  <!-- D circle -->
  <circle cx="170" cy="45" r="20" fill="#1d4ed8" opacity="0.9"/>
  <text x="170" y="51" font-family="'Segoe UI',Arial,sans-serif" font-size="16" font-weight="800"
        fill="white" text-anchor="middle">D</text>

  <!-- DevSan text -->
  <text x="250" y="38" font-family="'Segoe UI',Arial,sans-serif" font-size="22" font-weight="900"
        fill="url(#devGrad)" text-anchor="middle" letter-spacing="1">DevSan</text>
  <text x="250" y="58" font-family="'Segoe UI',Arial,sans-serif" font-size="10"
        fill="#64748b" text-anchor="middle" letter-spacing="2">DEVANSHU  ·  SANMATI</text>

  <!-- S circle -->
  <circle cx="330" cy="45" r="20" fill="#6d28d9" opacity="0.9"/>
  <text x="330" y="51" font-family="'Segoe UI',Arial,sans-serif" font-size="16" font-weight="800"
        fill="white" text-anchor="middle">S</text>
</svg>

<br/>

<sub>© 2024 <b>DevSan</b> — Devanshu Sharma & Sanmati Jain. All rights reserved.</sub>

</div>
