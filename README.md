<div align="center">

![banner](https://img.shields.io/badge/⚡%20AI--POWERED%20·%20TASK%20·%20QUEUE%20⚡-0f0c29?style=for-the-badge&labelColor=0f0c29&color=6366f1)

# 🧠 TaskQueue AI

### A Production-Grade Multi-Threaded Task Queue System with Real-Time AI Prioritization

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

[![Devanshu](https://img.shields.io/badge/👨‍💻%20Devanshu%20Sharma-TaskQueue%20AI%20Creator-1d4ed8?style=for-the-badge&logoColor=white)](https://github.com/)

[![Sanmati](https://img.shields.io/badge/👩‍💻%20Sanmati%20Jain-TaskQueue%20AI%20Creator-7c3aed?style=for-the-badge&logoColor=white)](https://github.com/)

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

<div align="center">

**Built with raw Java and a genuine love for systems programming.**

*No frameworks were harmed in the making of this project.*

---

## ⚡ A DevSan Original

> *Two creators. One system. Zero frameworks.*

[![DS](https://img.shields.io/badge/DS-Devanshu%20Sharma-1d4ed8?style=for-the-badge&logoColor=white)](https://github.com/)
&nbsp;
[![SJ](https://img.shields.io/badge/SJ-Sanmati%20Jain-7c3aed?style=for-the-badge&logoColor=white)](https://github.com/)

![](https://img.shields.io/badge/-DevSan%202024%20%C2%A9%20All%20Rights%20Reserved-0f172a?style=flat-square)

</div>
