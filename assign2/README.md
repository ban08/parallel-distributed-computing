# CPD Assignment 2 - Distributed Chat

Java 21 TCP chat with authentication, normal rooms, AI rooms, and token-based
reconnect after broken connections. The implementation is intentionally small
so its concurrency decisions are easy to explain and verify.

## Demo

[![Open the captioned distributed chat demonstration](doc/demo/distributed-chat-demo-preview.jpg)](doc/demo/distributed-chat-demo.mp4)

[Play the captioned demonstration](doc/demo/distributed-chat-demo.mp4)

![Captioned distributed chat demonstration](doc/demo/distributed-chat-demo.mp4)

The English subtitles are burned into the MP4, so the video is ready to present
without loading a separate subtitle file. GitLab renders the MP4 above as an
inline player; local Markdown previews can use the poster or direct link.

## Main Design Decisions

- **Simple TCP protocol.** `ServerSocket`, `Socket`, and UTF-8 line frames keep
  the network layer explicit and easy to inspect.
- **Virtual threads with bounded output queues.** Each accepted connection and
  authenticated session writer uses a virtual thread. Room broadcasts only
  enqueue frames, so a slow client cannot stall the room.
- **Custom lock-based primitives.** `BoundedQueue` and `LockedMap` replace
  thread-safe collections from `java.util.concurrent`, as required by the
  brief. A room lock preserves its message order.
- **Small reconnect contract.** A broken transport can resume the same logical
  session and room with a token. Missed messages are not replayed. `/quit`
  invalidates the token, so the user must log in again.
- **Explicit room lifecycle.** `CREATE` creates rooms and `JOIN` only joins
  existing rooms. Room names use `[A-Za-z0-9_-]+`.
- **Isolated AI rooms.** `CREATE_AI <room> -- <prompt>` creates a room with one
  sequential Ollama worker and retained bounded context. Bot replies use the
  normal room timeline.

Optional features outside the required delivery are deliberately omitted:
runtime registration mutation, TLS, application-level periodic keepalive, and
multi-line payloads.

## Build, Verify, and Demo

From `assign2/`:

```bash
./scripts/run.sh build
./scripts/run.sh test
./scripts/run.sh demo
```

`test` runs the two focused concurrency stress harnesses: `BoundedQueueStress`
and `LockedMapStress`. `demo` prints a guided three-terminal walkthrough.

Committed demo users:

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

Plain text entered after joining a room is sent as a chat message.

## Presentation Notes

- [`doc/ALIGNMENT.md`](doc/ALIGNMENT.md): requirement-by-requirement brief check.
- [`doc/CONCURRENCY.md`](doc/CONCURRENCY.md): locks, invariants, and deadlock
  argument.
