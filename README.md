# ⚡ JoltVM

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![CI](https://github.com/lucientong/joltvm/actions/workflows/ci.yml/badge.svg)](https://github.com/lucientong/joltvm/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/lucientong/joltvm/graph/badge.svg)](https://codecov.io/gh/lucientong/joltvm)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.lucientong/joltvm-agent)](https://central.sonatype.com/artifact/io.github.lucientong/joltvm-agent)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Build](https://img.shields.io/badge/build-Gradle-02303A.svg)](https://gradle.org/)

**Like a jolt of electricity — precisely and instantly diagnose running JVMs.**

JoltVM is a JVM online diagnostics and hot-fix framework. Attach via Java Agent, browse and decompile classes, apply hot-fixes with one click, visualize flame graphs, and trace method call chains — all from a browser-based Web IDE with safety audit and one-click rollback.

[中文文档](README_zh.md) · [Architecture](docs/en/architecture.md) · [架构文档](docs/zh/architecture.md)

---

## ✨ Features

> JoltVM is under active development. Phase 1 through Phase 9 are complete. See the [Roadmap](#-roadmap) for the full plan.

### 🖥️ Browser-Based Web IDE
No more memorizing 50+ CLI commands. Point-and-click interface with Monaco Editor, interactive flame graphs (d3-flame-graph), class/method tree navigation, Spring Boot bean browser, and audit dashboard. Edit code and apply hot-fixes visually — all served from the embedded Netty server at `http://localhost:7758`.

### 🔧 One-Click Hot-Fix
Edit code in Web IDE → auto-compile → instant class swap via `Instrumentation.redefineClasses()`. No manual `jad` → `mc` → `retransform` workflow. Built-in rollback with original bytecode preservation.

### 🔥 Interactive Flame Graphs
Zoomable, searchable flame graphs in the browser (d3-flame-graph). Toggle between CPU time and wall time views. Allocation view and side-by-side comparison are **planned**.

### 🌱 Spring Boot Awareness
List all Spring beans with filtering and pagination. Parse `@RequestMapping` endpoints with URL → method mappings. Analyze `@Controller → @Service → @Repository` dependency injection chains with circular dependency detection. Zero compile-time Spring dependencies — works via reflection with Spring Boot 2.x/3.x.

### 🔒 Security & Audit
HMAC-SHA256 token-based authentication with three-tier RBAC (Viewer / Operator / Admin). Authentication middleware enforces permissions on every API request. Every hot-fix generates an audit entry with timestamp, operator, reason, and diff. Immutable audit logs with JSON Lines and CSV export. Passwords secured with PBKDF2-SHA256 (310,000 iterations). Security can be disabled for development use.

### 🧵 Thread Diagnostics
List all JVM threads with state, CPU time, and lock info. Identify top-N CPU-consuming threads via two-sample delta analysis. Detect deadlocks (object monitor and ownable synchronizer). Export jstack-compatible thread dumps. All accessible from the browser-based Threads tab with color-coded state badges and click-to-view stack traces.

---

## 🚀 Quick Start

### Prerequisites

- **JDK 17+** (full JDK, not JRE — required for Attach API and dynamic compilation)
- **Gradle 8.x** (or use the included Gradle Wrapper)

### Build from Source

```bash
git clone https://github.com/lucientong/joltvm.git
cd joltvm
./gradlew build
```

### Attach to a Running JVM

```bash
# List all running JVM processes
java -jar joltvm-cli/build/libs/joltvm-cli-*-all.jar list

# Attach JoltVM agent to target PID
java -jar joltvm-cli/build/libs/joltvm-cli-*-all.jar attach <pid>

# Or use -javaagent for startup attachment
java -javaagent:joltvm-agent/build/libs/joltvm-agent-*-all.jar -jar your-app.jar

# With custom configuration (comma-separated key=value pairs)
java -javaagent:joltvm-agent/build/libs/joltvm-agent-*-all.jar=port=7758,security=true,adminPassword=MySecret,auditFile=/var/log/joltvm-audit.jsonl -jar your-app.jar
```

### Agent Arguments

| Argument        | Default                            | Description                                                  |
|-----------------|-------------------------------------|--------------------------------------------------------------|
| `port`          | `7758`                              | TCP port for the embedded web server                        |
| `security`      | `false`                             | Enable authentication (`true` / `false`)                   |
| `adminPassword` | `joltvm`                            | Initial admin password (stored as salted SHA-256 hash). When using the default, you will be prompted to change it on first login. |
| `auditFile`     | `$TMPDIR/joltvm-audit.jsonl`        | Path for the persistent JSON Lines audit log               |
| `tlsCert`       | *(none)*                            | Path to TLS certificate (PEM) — enables HTTPS when set    |
| `tlsKey`        | *(none)*                            | Path to TLS private key (PEM) — required when `tlsCert` is set |

---

## 🏗️ Architecture

JoltVM consists of four modules (see [Architecture Doc](docs/en/architecture.md) for details):

```
┌─────────────────────────────────────────────────┐
│                  Target JVM Process              │
│                                                  │
│  ┌──────────┐    ┌──────────────┐               │
│  │  Your App │◄──│ joltvm-agent │               │
│  └──────────┘    │  (bytecode   │               │
│                  │   engine)    │               │
│                  └──────┬───────┘               │
│                         │                        │
│                  ┌──────┴───────┐               │
│                  │joltvm-server │               │
│                  │ (Netty HTTP/ │               │
│                  │  WebSocket)  │               │
│                  └──────┬───────┘               │
│                         │ :7758                  │
└─────────────────────────┼───────────────────────┘
                          │
              ┌───────────┴───────────┐
              │     Browser / CLI      │
              │  ┌─────────────────┐  │
              │  │   joltvm-ui     │  │
              │  │  (Monaco Editor │  │
              │  │  + Flame Graph) │  │
              │  └─────────────────┘  │
              └───────────────────────┘
```

| Module | Description | Status |
|--------|-------------|--------|
| `joltvm-agent` | Java Agent core — premain/agentmain entry, Instrumentation management, Attach API | ✅ Phase 1 |
| `joltvm-server` | Embedded Netty HTTP server with REST APIs (class list, detail, decompile, hot-swap, tracing, Spring awareness, security & audit) + Web UI | ✅ Phase 2–7 |
| `joltvm-cli` | Command-line tool for attaching agent to running JVM processes | ✅ Phase 1 |
| `joltvm-ui` | Browser-based Web IDE (embedded in joltvm-server) | ✅ Phase 6 |

---

## 📦 Installation

### Maven Central

```xml
<dependency>
    <groupId>io.github.lucientong</groupId>
    <artifactId>joltvm-agent</artifactId>
    <version>0.7.0</version>
</dependency>
```

```kotlin
// Gradle Kotlin DSL
implementation("io.github.lucientong:joltvm-agent:0.7.0")
```

---

## 🐳 Docker

### Pull from Docker Hub

```bash
docker pull lucientong/joltvm
```

### List JVM Processes

```bash
# Within a container or with PID namespace sharing
docker run --pid=host lucientong/joltvm list
```

### Attach to a Running JVM

```bash
# Share PID namespace and use host networking
docker run --pid=host --network=host lucientong/joltvm attach <pid>
```

### Copy Agent JAR to Shared Volume (Sidecar Pattern)

```bash
docker run -v agent-vol:/export lucientong/joltvm \
  cp /opt/joltvm/joltvm-agent.jar /export/
```

### Use as -javaagent

```dockerfile
FROM eclipse-temurin:17-jdk
COPY --from=lucientong/joltvm /opt/joltvm/joltvm-agent.jar /opt/joltvm/
CMD ["java", "-javaagent:/opt/joltvm/joltvm-agent.jar", "-jar", "your-app.jar"]
```

---

## 🗺️ Roadmap

- [x] **Phase 1**: Agent skeleton (premain/agentmain) + Attach API + CLI
- [x] **Phase 2**: Netty Web Server + basic APIs (list classes, decompile source)
- [x] **Phase 3**: Hot-swap (compile → redefineClasses) + rollback
- [x] **Phase 4**: Method tracing (Byte Buddy Advice) + flame graph data
- [x] **Phase 5**: Spring Boot awareness (Bean list, URL mapping, dependency chains)
- [x] **Phase 6**: Web UI (Monaco Editor + flame graph + dashboard + Spring panel)
- [x] **Phase 7**: Security & Audit (RBAC + token auth + audit log + export)
- [x] **Phase 8**: Security hardening (PBKDF2 password hashing, bug fixes, thread safety)
- [x] **Phase 9**: Thread diagnostics (thread list, CPU top-N, deadlock detection)
- [ ] **Phase 10**: JVM dashboard enhancement (GC stats, system properties, classpath)
- [ ] **Phase 11**: ClassLoader analysis + Logger dynamic level adjustment
- [ ] **Phase 12**: OGNL expression engine (runtime object inspection)
- [ ] **Phase 13**: Watch command (conditional method observation with OGNL filters)
- [ ] **Phase 14**: async-profiler integration (CPU/Alloc/Lock profiling)
- [ ] **Phase 15**: WebSocket real-time push
- [ ] **Phase 16**: Plugin/SPI extension mechanism
- [ ] **Phase 17**: Tunnel server for remote diagnostics → v1.0.0 GA

---

## 🤝 Contributing

Contributions are welcome! Please read our contributing guidelines before submitting PRs.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

---

## 📄 License

This project is licensed under the Apache License 2.0 — see the [LICENSE](LICENSE) file for details.

---

## 🙏 Acknowledgments

- [Arthas](https://github.com/alibaba/arthas) — Inspiration for JVM diagnostics tooling
- [Byte Buddy](https://bytebuddy.net/) — Bytecode manipulation library
- [CFR](https://www.benf.org/other/cfr/) — Java decompiler
- [Netty](https://netty.io/) — Async event-driven network framework
- [Monaco Editor](https://microsoft.github.io/monaco-editor/) — Code editor for the Web IDE
