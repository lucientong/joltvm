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

> JoltVM is under active development. Phase 1 (Agent skeleton + Attach API), Phase 2 (Netty Web Server + REST APIs), and Phase 3 (Hot-Swap + Rollback) are complete. See the [Roadmap](#-roadmap) for the full plan.

### 🖥️ Browser-Based Web IDE
No more memorizing 50+ CLI commands. Point-and-click interface with Monaco Editor, real-time log streaming, and class/method tree navigation. Edit code and apply hot-fixes visually.

### 🔧 One-Click Hot-Fix
Edit code in Web IDE → auto-compile → instant class swap via `Instrumentation.redefineClasses()`. No manual `jad` → `mc` → `retransform` workflow. Built-in rollback with original bytecode preservation.

### 🔥 Interactive Flame Graphs
Zoomable, searchable flame graphs in the browser (d3-flame-graph). Toggle between CPU time, wall time, and allocation views. Side-by-side comparison for before/after optimization.

### 🌱 Spring Boot Awareness
List all `@RestController` endpoints with URL mappings. Trace complete call chains from URL to database. Inspect any Spring Bean's current field values. Hot-fix Spring components without restart.

### 🔒 Security Audit
Role-based access control (viewer/operator/admin). Every hot-fix generates a diff with timestamp, user, and reason. Optional approval workflow. Immutable, exportable audit logs.

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
```

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
| `joltvm-server` | Embedded Netty HTTP server with REST APIs (class list, detail, decompile, hot-swap, tracing) | ✅ Phase 2–4 |
| `joltvm-cli` | Command-line tool for attaching agent to running JVM processes | ✅ Phase 1 |
| `joltvm-ui` | React + TypeScript Web IDE frontend | 📋 Phase 6 |

---

## 📦 Installation

### Maven Central

```xml
<dependency>
    <groupId>io.github.lucientong</groupId>
    <artifactId>joltvm-agent</artifactId>
    <version>0.2.0</version>
</dependency>
```

```kotlin
// Gradle Kotlin DSL
implementation("io.github.lucientong:joltvm-agent:0.2.0")
```

---

## 🗺️ Roadmap

- [x] **Phase 1**: Agent skeleton (premain/agentmain) + Attach API + CLI
- [x] **Phase 2**: Netty Web Server + basic APIs (list classes, decompile source)
- [x] **Phase 3**: Hot-swap (compile → redefineClasses) + rollback
- [x] **Phase 4**: Method tracing (Byte Buddy Advice) + flame graph data
- [ ] **Phase 5**: Spring Boot awareness (Bean list, URL mapping)
- [ ] **Phase 6**: Web UI (Monaco Editor + flame graph + real-time logs)

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
