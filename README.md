# ⚡ Multi-Threaded Task Queue System with AI Smart Prioritization

A custom, multi-threaded Task Queue System built from the ground up in **pure Core Java (Java 17)** with zero external web or concurrency frameworks (NO Spring Boot, NO ExecutorService). 

On top of a thread-safe producer-consumer queue sits an **Anthropic Claude AI (claude-sonnet-4-20250514)** prioritization engine. The AI dynamically reviews task parameters, current queue wait-times, and thread workloads to optimize task order in real-time. It is managed by a beautiful, glassmorphic **Real-Time Web Dashboard** served directly from the native Java HTTP server.

---

## 🏗️ System Architecture

```text
                  +-----------------------------------------+
                  |            REST API Clients             |
                  |     (PowerShell Curl / HTML Web UI)      |
                  +--------------------+--------------------+
                                       |
                                POST /api/tasks
                                       v
                  +--------------------+--------------------+
                  |               TaskService               |
                  |        (In-Memory Store: CHM)           |
                  +--------------------+--------------------+
                                       |
                        Fetch Dynamic Dynamic Priority
                                       v
                  +--------------------+--------------------+          +-----------------------+
                  |      AIPrioritizationService (Claude)   |<-------->|  Anthropic Claude API |
                  |   - Cache (30s Type + Size Signature)   |          | (claude-sonnet-4)     |
                  |   - Fallback (Deterministic Rules)      |          +-----------------------+
                  +--------------------+--------------------+
                                       |
                             Suggested Priority Applied
                                       v
                  +--------------------+--------------------+
                  |    PriorityBlockingQueue (Max-Heap)     |
                  +--------------------+--------------------+
                      |            |            |          |
                    take()       take()       take()     take()
                      v            v            v          v
                  +---+----+   +---+----+   +---+----+   +-+------+
                  | Worker |   | Worker |   | Worker |   | Worker |  <--- 4 Threads in ThreadPool
                  |   #1   |   |   #2   |   |   #3   |   |   #4   |       (Built from Scratch!)
                  +---+----+   +---+----+   +---+----+   +-+------+
                      |            |            |          |
                      +------------+----+-------+----------+
                                        |
                                        v
                  +---------------------+-------------------+
                  |               TaskExecutor              |
                  |     - Sleep (Simulates Work Type)       |
                  |     - Failures Trigger Exponential      |
                  |       Backoff Retries (Timer Task)      |
                  +-----------------------------------------+
```

---

## 🌟 Standout Core Features

1. **Custom Thread Pool (`ThreadPool` / `WorkerThread`)**: Built entirely from scratch by extending `java.lang.Thread` and polling a shared priority queue. No standard Java `Executors` are used.
2. **AI-Powered Smart Prioritization (`AIPrioritizationService`)**: Connects to the Anthropic Messages API using standard built-in `HttpClient` to review queue capacity and deadline pressures, automatically adjusting execution priorities (e.g., overriding to priority 10 for urgent payments, or deferring slow data syncs to priority 1).
3. **Graceful Failures & Fallbacks**:
   - **Caching Layer**: Prevents rate-limits and token costs by caching AI prioritization decisions for 30 seconds using task type + queue signatures.
   - **Deterministic Fallbacks**: If the Claude API key is absent or the network drops, it immediately implements a hardcoded priority fallback schema (`PAYMENT=9`, `REPORT=7`, `EMAIL=5`, `NOTIFICATION=3`, `DATA_SYNC=2`) to guarantee 100% service uptime.
4. **Exponential Backoff Retries**: Tasks that throw execution exceptions (e.g., intermittent connections) increment their retry counter, wait an exponentially increasing period ($2^{\text{retryCount}}$ seconds), and are re-submitted to the queue thread-safely via a background `Timer`.
5. **Interactive Glassmorphic Dashboard**: A deep-slate dark mode web dashboard served directly at `http://localhost:8080/` with a live 1.5s refresh cycle, tracking active workers, queue lengths, task status logs, and a scrolling window of real-time AI decision rationales.

---

## 🛠️ Getting Started & Setup

### 1. Retrieve Your Free Anthropic API Key (Highly Recommended)
- Go to the [Anthropic Console](https://console.anthropic.com/).
- Sign up with your email or Google account to get **$5 of free API credits** automatically.
- Navigate to the **API Keys** section and generate a new key.
- *Note: If you choose not to configure a key, the system will run perfectly using the built-in static priority fallbacks!*

### 2. Configure Your API Key

**Option A — Web dashboard (easiest)**  
1. Start the server and open http://localhost:8080/  
2. Click **API Key Settings**  
3. Paste your key from [console.anthropic.com/settings/keys](https://console.anthropic.com/settings/keys) (starts with `sk-ant-`)  
4. Click **Validate key**, then **Save & use**

**Option B — Environment variable**  
Set the key in your shell before launching:

#### For Windows PowerShell (Recommended):
```powershell
$env:CLAUDE_API_KEY="your_actual_api_key_here"
```

#### For Windows CMD (Command Prompt):
```cmd
set CLAUDE_API_KEY=your_actual_api_key_here
```

#### For Linux / macOS Terminal:
```bash
export CLAUDE_API_KEY="your_actual_api_key_here"
```

---

## 🚀 Building & Running the Project

The project is structured with standard Maven and compiles directly using a clean, standard shade configuration.

### Step 1: Clean and Package the JAR
Use the Maven wrapper (or your global Maven command) to compile and build the package:
```bash
mvn clean package
```

### Step 2: Run the Executable
Start the standalone shaded JAR:
```bash
java -jar target/queue-system-1.0-SNAPSHOT.jar
```

On startup, you will see a glowing custom ASCII banner and console messages as the thread pool initializes. **No sample tasks are pre-loaded** — submit tasks via the dashboard or API.

---

## 📈 Estimated API Credit Usage & Cost Metrics

The dynamic prioritization system is highly optimized to protect your free credits:
- **Claude Model**: `claude-sonnet-4-20250514`
- **Cost Rates**: Input tokens = `$0.003 / 1K tokens` | Output tokens = `$0.015 / 1K tokens`
- **Input Prompt size**: ~380 tokens (compact and instruction-focused)
- **Output response size**: ~95 tokens (highly structured JSON payload)
- **Calculated Cost per Task**: `(0.380 * $0.003) + (0.095 * $0.015) = $0.00114 + $0.00142 = $0.00256`
- **Cost Value**: You get **~390 API calls for every single Dollar ($1)**. Your **$5 free credit** covers **~1,950 tasks**!
- **30-Second Caching**: Limits duplicate calls for similar workloads.

---

## 📡 REST API Endpoint Documentation

Here are operational `curl` commands for testing the application via terminal or PowerShell:

### 1. Submit a New Task (Triggers AI Prioritization)
Submits a task context, which the AI analyzes to assign a dynamic priority:
```bash
curl -X POST http://localhost:8080/api/tasks \
     -H "Content-Type: application/json" \
     -d '{"name": "Invoice Billing Process", "type": "PAYMENT", "payload": "amount=$4500, region=EU", "priority": 5, "deadlineSeconds": 90}'
```
*PowerShell Equivalent:*
```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/tasks" -Method Post -ContentType "application/json" -Body '{"name": "Invoice Billing Process", "type": "PAYMENT", "payload": "amount=$4500, region=EU", "priority": 5, "deadlineSeconds": 90}'
```

### 2. List All Active Tasks
Retrieves a JSON list of all tasks, their statuses, times, and AI priority reasoning:
```bash
curl http://localhost:8080/api/tasks
```

### 3. Retrieve Task Details by ID
```bash
curl http://localhost:8080/api/tasks/8fa24c96-3b68-4ad0-b88a-36fb1c49bf9b
```

### 4. Cancel a Pending or Active Task
Marks a task as `CANCELLED` and safely removes it from the Priority Queue:
```bash
curl -X DELETE http://localhost:8080/api/tasks/8fa24c96-3b68-4ad0-b88a-36fb1c49bf9b
```

### 5. Filter Tasks by Status
Allowed values: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`.
```bash
curl http://localhost:8080/api/tasks/status/PENDING
```
*PowerShell:*
```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/tasks/status/RUNNING"
```

### 6. Get Real-Time Queue Statistics
```bash
curl http://localhost:8080/api/queue/stats
```
Example response:
```json
{
  "totalTasks": 5,
  "pendingTasks": 1,
  "runningTasks": 2,
  "completedTasks": 2,
  "failedTasks": 0,
  "cancelledTasks": 0,
  "activeThreads": 2,
  "queueSize": 1,
  "avgCompletionTimeSeconds": 2.14,
  "tasksByType": { "PAYMENT": 1, "EMAIL": 1 },
  "aiDecisionsMade": 5,
  "aiUrgentOverrides": 0
}
```

### 7. AI Priority Check (preview or re-prioritize)
Preview priority for a hypothetical task (not enqueued):
```bash
curl -X POST http://localhost:8080/api/tasks/prioritize \
  -H "Content-Type: application/json" \
  -d '{"name": "Hypothetical Payment", "type": "PAYMENT", "payload": "amount=1200", "priority": 5, "deadlineSeconds": 90}'
```
Re-prioritize an existing pending task:
```bash
curl -X POST http://localhost:8080/api/tasks/prioritize \
  -H "Content-Type: application/json" \
  -d '{"taskId": "YOUR-TASK-UUID-HERE"}'
```

### 8. Retrieve AI Prioritization Log Decisions
```bash
curl http://localhost:8080/api/tasks/ai-insights
```

### 9. API Key Configuration (dashboard backend)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/config/ai` | Key status (`active`/`inactive`), source, partial hint |
| POST | `/api/config/api-key` | Set runtime key: `{"api_key":"sk-ant-..."}` |
| POST | `/api/config/api-key/validate` | Test key against Claude Messages API |
| DELETE | `/api/config/api-key` | Clear runtime key (falls back to env) |

```bash
curl http://localhost:8080/api/config/ai

curl -X POST http://localhost:8080/api/config/api-key \
  -H "Content-Type: application/json" \
  -d '{"api_key":"sk-ant-api03-YOUR_KEY"}'

curl -X POST http://localhost:8080/api/config/api-key/validate \
  -H "Content-Type: application/json" \
  -d '{"api_key":"sk-ant-api03-YOUR_KEY"}'
```

---

## 🛠️ Testing Retries

Submit a task whose payload contains `fail` or `error` to trigger simulated failures and exponential backoff retries (`2^retryCount` seconds between attempts, max 3 retries).

---

## 🎯 What Makes This Interview-Ready?

When an interviewer asks: *"What is unique or challenging about this project?"*

You can confidently say:
> "I built a custom multithreaded task queue system from scratch in Core Java, implementing the Producer-Consumer pattern using a PriorityBlockingQueue and custom worker threads instead of built-in executors. 
> To solve real-world scheduling bottlenecks, I integrated Claude AI using Java's HttpClient. Before enqueuing tasks, the AI dynamically analyzes queue capacity, worker threads, and deadlines, overriding priorities as needed. 
> To make it production-ready, I built a 30-second token caching system, created a robust deterministic fallback schema on network failure, implemented timed exponential backoff retries, and designed a glassmorphic dashboard to monitor stats in real-time."
