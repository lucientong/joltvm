# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.5.0] - 2026-04-11

### Added
- **Spring Boot Awareness** (`SpringContextService`) — Reflection-based Spring ApplicationContext discovery with zero compile-time Spring dependencies, compatible with Spring Boot 2.x/3.x
- **Bean List** — List all Spring beans with pagination, package/search/stereotype filtering
- **Bean Detail** — Inspect individual bean metadata including methods, annotations, interfaces, implemented RequestMappings
- **Request Mapping** — Parse `@RequestMapping`/`@GetMapping`/`@PostMapping` etc. to display URL → method mappings, with HTTP method and search filtering
- **Dependency Chain** — Recursive dependency injection analysis for individual beans, detecting `@Autowired`/`@Inject`/`@Resource` annotated fields and constructor parameters, with circular dependency detection
- **Dependency Graph** — Full `@Controller → @Service → @Repository` dependency graph across all stereotyped beans
- **REST API: `GET /api/spring/beans`** — List all Spring beans (paginated, filterable by package, search, stereotype)
- **REST API: `GET /api/spring/beans/{beanName}`** — Spring bean detail (methods, annotations, interfaces, mappings)
- **REST API: `GET /api/spring/mappings`** — URL → method request mappings (filterable by HTTP method and search)
- **REST API: `GET /api/spring/dependencies`** — Full dependency graph (Controller→Service→Repository relationships)
- **REST API: `GET /api/spring/dependencies/{beanName}`** — Dependency chain for a specific bean (recursive, circular-aware)
- **`StubSpringContextService`** — Test helper subclass enabling comprehensive positive-path testing with injectable mock data
- **32 new tests** — BeanListHandler (11), BeanDetailHandler (8), RequestMappingHandler (11), DependencyChainHandler (5), DependencyGraphHandler (3), SpringContextService (6), APIRoutes Spring endpoints (1)

### Changed
- `APIRoutes` now registers 18 routes (was 13): added 5 Spring Boot awareness endpoints
- `JoltVMAgent` now initializes `SpringContextService` alongside other shared services
- Updated project version to 0.5.0

## [0.4.0] - 2026-04-11

### Added
- **Method Tracing** (`MethodTraceService`) — Byte Buddy Advice injection for non-invasive method enter/exit interception, capturing arguments, return values, exceptions, and execution time
- **Flame Graph Data** (`FlameGraphNode`, `FlameGraphCollector`) — Tree-structured data model compatible with d3-flame-graph JSON format (`{name, value, children}`)
- **Stack Sampling** — Periodic `Thread.getAllStackTraces()` sampling for CPU flame graph generation, with configurable interval (1–1000ms)
- **Trace Record** (`TraceRecord`) — Immutable record capturing method invocation details: id, className, methodName, parameterTypes, arguments, returnValue, exception info, duration, thread info, timestamp, and call depth
- **REST API: `POST /api/trace/start`** — Start method tracing (Byte Buddy Advice) or stack sampling with configurable duration (max 300s)
- **REST API: `POST /api/trace/stop`** — Stop active tracing/sampling (supports selective stop by type)
- **REST API: `GET /api/trace/records`** — Retrieve captured trace records with pagination (limit parameter)
- **REST API: `GET /api/trace/flamegraph`** — Get flame graph data in d3-flame-graph compatible JSON format
- **REST API: `GET /api/trace/status`** — Query current tracing/sampling state and statistics
- **`APIRoutes`** — Central route registration class for all 13 API endpoints (called reflectively by Agent)
- **40+ new tests** — TraceRecord (7), FlameGraphNode (8), FlameGraphCollector (13), MethodTraceService (15), TraceHandler (10), TraceListHandler (3), TraceFlameGraphHandler (3), TraceStatusHandler (2), APIRoutes (6)

### Changed
- `APIRoutes` now registers 13 routes (was 8): added 5 trace endpoints
- `joltvm-server` module now depends on `net.bytebuddy:byte-buddy` directly for method tracing
- Updated project version to 0.4.0

## [0.3.0] - 2026-04-10

### Added
- **In-Memory Java Compiler** (`InMemoryCompiler`) — compiles Java source code entirely in memory using `javax.tools.JavaCompiler`, no temporary files needed
- **Bytecode Backup Service** (`BytecodeBackupService`) — preserves original class bytecode in `ConcurrentHashMap` before hot-swap for safe rollback
- **Hot-Swap Service** (`HotSwapService`) — orchestrates the full hot-swap lifecycle: validate → backup → redefine → record history
- **Hot-Swap Record** (`HotSwapRecord`) — immutable audit trail entries for all hot-swap and rollback operations
- **REST API: `POST /api/compile`** — compile Java source code in memory and return success/failure with diagnostics
- **REST API: `POST /api/hotswap`** — full pipeline: compile source → backup original bytecode → apply hot-swap via `Instrumentation.redefineClasses()`
- **REST API: `POST /api/rollback`** — roll back a hot-swapped class to its original bytecode
- **REST API: `GET /api/hotswap/history`** — retrieve hot-swap operation history with rollbackable class list
- **Compile Result** (`CompileResult`) — structured compilation output with bytecode map and error diagnostics
- **37 new tests** — InMemoryCompiler (8), BytecodeBackupService (10), HotSwapRecord (3), CompileHandler (6), HotSwapHistoryHandler (3), APIRoutes update (1+)

### Changed
- `APIRoutes` now registers 8 routes (was 4): added compile, hotswap, rollback, and history endpoints
- `joltvm-server` module description updated to reflect hot-swap capabilities

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
