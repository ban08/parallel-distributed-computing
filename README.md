# parallel-distributed-computing

Two systems assignments: one on getting real speed-up out of multiple cores, one on building a chat server that survives dropped connections.

## What it does

**Assignment 1 — Parallel matrix multiplication.** The same computation in single-threaded C++ and in Java, plus parallel variants, benchmarked across matrix sizes and thread counts to measure cache behaviour and speed-up. Results and analysis are in a notebook.

**Assignment 2 — Distributed chat.** A TCP chat server in Java 21 with:

- **Authentication** and chat rooms (`CREATE`, `JOIN`, `list`), created on demand.
- **Token-based reconnect** — a dropped connection can resume the same logical session and room with a token, without logging in again.
- **AI rooms** — a room backed by a sequential Ollama worker with bounded, retained context.
- **TLS** for encrypted connections.

## Stack

Java 21 (virtual threads), C++, Python (benchmark notebook), Ollama, TLS sockets. No `java.util.concurrent` collections in the chat — the concurrency primitives are hand-built.

## How to run

Assignment 2:

```bash
cd assign2
./scripts/run.sh build
./scripts/run.sh test
java -cp out chat.server.ChatServer 8443 data/users.txt
java -cp out chat.client.ChatClient localhost 8443
```

Demo users are in `assign2/data/users.txt` (passwords are PBKDF2-hashed).

## What I built

Group project for the Parallel and Distributed Computing course (2025/26). I built the **distributed chat**: the virtual-thread connection model, the custom lock-based concurrency primitives (`BoundedQueue`, `LockedMap`) that replace the standard thread-safe collections, the room and session protocol, the token-based reconnect, TLS, and the AI rooms. Teammates focused on the parallel matrix-multiplication assignment and its benchmarking.

## What I would do differently

Replace the line-based text protocol with a small framed binary protocol — it would remove a class of parsing edge cases — and add a proper integration test that starts a server and drives several clients through reconnects, rather than relying on manual and stress tests.
