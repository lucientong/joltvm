# =============================================================================
# JoltVM Docker Image — Multi-stage build
# =============================================================================
# Produces a slim image containing both the agent and CLI fat JARs.
#
# Usage modes:
#   1. CLI tool:
#      docker run lucientong/joltvm list
#      docker run lucientong/joltvm attach <pid>
#
#   2. Sidecar / init container (copy agent JAR to shared volume):
#      docker run -v agent-vol:/export lucientong/joltvm \
#        cp /opt/joltvm/joltvm-agent.jar /export/
#
# Build:
#   docker build -t lucientong/joltvm .
#   docker build --build-arg JOLTVM_VERSION=0.9.0 -t lucientong/joltvm:0.9.0 .
# =============================================================================

# ---------------------------------------------------------------------------
# Stage 1: Build
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk AS builder

WORKDIR /build

# Cache Gradle wrapper & dependencies before copying source
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
RUN chmod +x gradlew && ./gradlew --no-daemon --version

# Copy subproject build files to resolve dependencies
COPY joltvm-agent/build.gradle.kts joltvm-agent/
COPY joltvm-server/build.gradle.kts joltvm-server/
COPY joltvm-cli/build.gradle.kts joltvm-cli/
RUN ./gradlew dependencies --no-daemon || true

# Copy full source and build shadow JARs (tests skipped — run in CI)
COPY . .
RUN ./gradlew shadowJar -x test --no-daemon

# ---------------------------------------------------------------------------
# Stage 2: Runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk

ARG JOLTVM_VERSION=0.9.0

# OCI image labels
LABEL org.opencontainers.image.title="JoltVM" \
      org.opencontainers.image.description="A JVM online diagnostics and hot-fix framework" \
      org.opencontainers.image.version="${JOLTVM_VERSION}" \
      org.opencontainers.image.url="https://github.com/lucientong/joltvm" \
      org.opencontainers.image.source="https://github.com/lucientong/joltvm" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.vendor="lucientong"

ENV JOLTVM_HOME=/opt/joltvm \
    JOLTVM_VERSION=${JOLTVM_VERSION}

RUN mkdir -p ${JOLTVM_HOME}

# Copy fat JARs from builder stage
COPY --from=builder /build/joltvm-agent/build/libs/joltvm-agent-${JOLTVM_VERSION}-all.jar \
                    ${JOLTVM_HOME}/joltvm-agent.jar
COPY --from=builder /build/joltvm-cli/build/libs/joltvm-cli-${JOLTVM_VERSION}-all.jar \
                    ${JOLTVM_HOME}/joltvm-cli.jar

# Install convenience wrapper script
COPY <<'SCRIPT' ${JOLTVM_HOME}/joltvm.sh
#!/usr/bin/env bash
# =============================================================================
# joltvm.sh — Convenience wrapper for JoltVM CLI
# =============================================================================
# Usage:
#   joltvm.sh <command> [options]
#
# Examples:
#   joltvm.sh list                  — list running JVM processes
#   joltvm.sh attach <pid>          — attach the agent to a JVM
#   joltvm.sh --help                — show help
# =============================================================================
set -euo pipefail

JOLTVM_HOME="${JOLTVM_HOME:-/opt/joltvm}"
CLI_JAR="${JOLTVM_HOME}/joltvm-cli.jar"

if [ ! -f "${CLI_JAR}" ]; then
  echo "ERROR: CLI JAR not found at ${CLI_JAR}" >&2
  exit 1
fi

exec java ${JOLTVM_JAVA_OPTS:-} -jar "${CLI_JAR}" "$@"
SCRIPT

RUN chmod +x ${JOLTVM_HOME}/joltvm.sh && \
    ln -s ${JOLTVM_HOME}/joltvm.sh /usr/local/bin/joltvm

# Expose the embedded Netty HTTP server port
EXPOSE 7758

WORKDIR ${JOLTVM_HOME}

ENTRYPOINT ["java", "-jar", "/opt/joltvm/joltvm-cli.jar"]
CMD ["--help"]
