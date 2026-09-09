#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERSION="$(awk -F= '$1 == "projectVersion" { print $2 }' "$ROOT_DIR/gradle.properties")"

AGENT_JAR="$ROOT_DIR/joltvm-distribution/build/libs/joltvm-agent-${VERSION}-all.jar"
CLI_JAR="$ROOT_DIR/joltvm-cli/build/libs/joltvm-cli-${VERSION}-all.jar"
TUNNEL_JAR="$ROOT_DIR/joltvm-tunnel/build/libs/joltvm-tunnel-${VERSION}-all.jar"

for artifact in "$AGENT_JAR" "$CLI_JAR" "$TUNNEL_JAR"; do
    if [[ ! -f "$artifact" ]]; then
        echo "Missing release artifact: $artifact" >&2
        exit 1
    fi
done

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/joltvm-smoke.XXXXXX")"
P1=""
P2=""
cleanup() {
    local status=$?
    [[ -z "$P1" ]] || kill "$P1" 2>/dev/null || true
    [[ -z "$P2" ]] || kill "$P2" 2>/dev/null || true
    if [[ $status -ne 0 ]]; then
        for log in "$WORK_DIR"/*.log; do
            [[ ! -f "$log" ]] || { echo "===== $log =====" >&2; cat "$log" >&2; }
        done
    fi
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

wait_for_url() {
    local url="$1"
    local output="$2"
    for _ in $(seq 1 30); do
        if curl -fsS "$url" >"$output" 2>/dev/null; then
            return 0
        fi
        sleep 1
    done
    echo "Timed out waiting for $url" >&2
    return 1
}

# Smoke 1: the canonical distribution must start the embedded Web IDE server.
java -javaagent:"$AGENT_JAR"=port=17758 \
    -jar "$TUNNEL_JAR" --port=18800 >"$WORK_DIR/javaagent.log" 2>&1 &
P1=$!
wait_for_url "http://127.0.0.1:17758/api/health" "$WORK_DIR/javaagent-health.json"
grep -q "\"version\":\"$VERSION\"" "$WORK_DIR/javaagent-health.json"
kill "$P1"
wait "$P1" || true
P1=""

# Smoke 2: the CLI must extract and attach its embedded distribution JAR.
java -jar "$TUNNEL_JAR" --port=18801 >"$WORK_DIR/attach-target.log" 2>&1 &
P2=$!
wait_for_url "http://127.0.0.1:18801/api/tunnel/health" "$WORK_DIR/tunnel-health.json"
java -jar "$CLI_JAR" attach "$P2" port=17759 >"$WORK_DIR/attach.log" 2>&1
wait_for_url "http://127.0.0.1:17759/api/health" "$WORK_DIR/attach-health.json"
grep -q "\"version\":\"$VERSION\"" "$WORK_DIR/attach-health.json"
kill "$P2"
wait "$P2" || true
P2=""

echo "Release smoke passed for JoltVM $VERSION"
