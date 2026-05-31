# CPD Assignment 2 - Distributed Chat

Java 21 TCP chat with authentication, normal rooms, AI rooms, and token-based
reconnect after broken connections. T

## Demo

[![Chat demonstration]](doc/demo/distributed-chat-demo.mp4)

## Main Design Decisions

- **Virtual threads with bounded output queues.** Each accepted connection and
  authenticated session writer uses a virtual thread. Room broadcasts only
  enqueue frames, so a slow client cannot stall the room.
- **Custom lock-based primitives.** `BoundedQueue` and `LockedMap` replace
  thread-safe collections from `java.util.concurrent`. A room lock preserves its message order.
- **Reconnect contract.** A broken transport can resume the same logical
  session and room with a token. Missed messages are not replayed. `/quit`
  invalidates the token, so the user must log in again.
- **Explicit room lifecycle.** `CREATE` creates rooms and `JOIN` only joins
  existing rooms. Room names use `[A-Za-z0-9_-]+`.
- **Isolated AI rooms.** `CREATE_AI <room> -- <prompt>` creates a room with one
  sequential Ollama worker and retained bounded context.

## Build and run tests

From `assign2/`:

```bash
./scripts/run.sh build
./scripts/run.sh test
```

## Demo users:

```text
miguel / password123
bob    / bob123
```

## Client Commands

```text
/login <username> <password>
/resume <token>
/list
/create <room>
/create_ai <room> -- <prompt>
/join <room>
/msg <text>
/leave
/quit
```

