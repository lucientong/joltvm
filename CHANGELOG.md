# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.0] - 2026-04-10

### Added
- **Embedded Netty HTTP Server** — lightweight web server (default port 7758) with start/stop lifecycle management
- **HTTP Router** — path pattern matching with `{paramName}` path parameter support
- **CORS support** — full cross-origin request handling for browser-based Web IDE access
- **REST API: `GET /api/health`** — JVM health check with status, PID, uptime, and memory info
- **REST API: `GET /api/classes`** — paginated listing of all loaded classes with `package`, `search`, `page`, `size` query parameters
- **REST API: `GET /api/classes/{className}`** — detailed class info including fields, methods, superclass, interfaces, and modifiers
- **REST API: `GET /api/classes/{className}/source`** — CFR-powered runtime class decompilation to Java source code
- **`DecompileService`** — in-memory bytecode decompilation via CFR with `BytecodeClassFileSource` adapter
- **Agent ↔ Server integration** — reflection-based server startup from Agent to avoid circular module dependency
- **Agent argument parsing** — `key=val,key2=val2` format with `port` parameter support
- **Shadow JAR relocation** — relocated Netty, CFR, Gson, ASM, Byte Buddy to avoid classpath conflicts

### Changed
- `JoltVMAgent` now starts embedded HTTP server during initialization
- `joltvm-agent` Shadow JAR includes relocated server dependencies
- `joltvm-server` module is now fully implemented (previously placeholder)

## [0.1.1] - 2026-04-10

### Added
- Gradle multi-module project structure (joltvm-agent, joltvm-server, joltvm-cli)
- Java Agent entry point with `premain()` and `agentmain()` support
- `InstrumentationHolder` — thread-safe singleton for Instrumentation instance
- `AttachHelper` — dynamic agent attachment via Attach API
- `JoltVMCli` — CLI tool with `attach <pid>` and `list` commands
- Maven Central publishing pipeline via Sonatype Central Portal
- GitHub Actions CI (Java 17/21, Ubuntu/macOS) and Release workflows
- Project documentation (English + Chinese)
- Architecture documentation (`docs/en/architecture.md`, `docs/zh/architecture.md`)
