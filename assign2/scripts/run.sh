#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

OUT_DIR="${OUT_DIR:-out}"
JAVA="${JAVA:-java}"
JAVAC="${JAVAC:-javac}"
PORT="${PORT:-8443}"
USERS_FILE="${USERS_FILE:-data/users.txt}"

usage() {
  cat <<'USAGE'
Usage:
  ./scripts/run.sh build
  ./scripts/run.sh test
  ./scripts/run.sh demo
USAGE
}

build() {
  rm -rf "$OUT_DIR"
  mkdir -p "$OUT_DIR"
  "$JAVAC" -Xlint:all --release 21 -d "$OUT_DIR" $(find src -name '*.java' | sort)
}

run_class() {
  "$JAVA" -cp "$OUT_DIR" "$@"
}

test_all() {
  build
  run_class chat.concurrent.stress.BoundedQueueStress
  run_class chat.concurrent.stress.BoundedQueueTimeoutStress
  run_class chat.concurrent.stress.LockedMapStress 2
  run_class chat.auth.stress.UserRegistryStress
  run_class chat.session.SessionManualTest
  run_class chat.session.SessionRegistryManualTest
  run_class chat.session.stress.SessionRegistryStress
  run_class chat.session.stress.SessionOutboundStress
  run_class chat.room.stress.RoomBroadcastStress
  run_class chat.client.ClientCommandManualTest
  run_class chat.client.ConnectionManagerManualTest
  run_class chat.server.ConnectionHandlerManualTest
}

demo() {
  cat <<USAGE
From assign2/, first build:
  ./scripts/run.sh build

Optional second user setup:
  java -cp $OUT_DIR chat.auth.AddUser $USERS_FILE bob bob123

Open three terminals from assign2/:

Terminal 1:
  java -cp $OUT_DIR chat.server.ChatServer $PORT $USERS_FILE

Terminal 2:
  java -cp $OUT_DIR chat.client.ChatClient localhost $PORT

Terminal 3:
  java -cp $OUT_DIR chat.client.ChatClient localhost $PORT

Try these inside the clients:
  /login miguel password123
  /login bob bob123
  /list
  /create Library
  /join Library
  hello from this client
  /leave
  /quit

Resume/replay test:
  1. Login and join Library in client A.
  2. Copy the token printed by client A.
  3. Login and join Library in client B.
  4. Close client A.
  5. Send messages from client B.
  6. Reopen client A and type /resume <copied-token>.
USAGE
}

case "${1:-}" in
  build)
    build
    ;;
  test)
    test_all
    ;;
  demo)
    demo
    ;;
  *)
    usage
    exit 2
    ;;
esac
