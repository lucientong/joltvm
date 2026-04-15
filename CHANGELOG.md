# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.10.0] - 2026-04-15

### Added
- **Thread Diagnostics** (`ThreadDiagnosticsService`) — Full thread inspection via `ThreadMXBean`: list all threads with state/lock/metrics, get detailed thread info with full stack traces and locked monitors/synchronizers.
- **CPU Top-N Analysis** — Two-sample CPU time delta measurement to identify the most CPU-consuming threads. Results cached for 5 seconds to avoid excessive sampling. Configurable sample count and interval.
- **Deadlock Detection** — Detects both object monitor deadlocks and ownable synchronizer deadlocks via `findDeadlockedThreads()` and `findMonitorDeadlockedThreads()`.
- **Thread Dump Export** — Generates jstack-compatible plain-text thread dump including lock info, monitors, and synchronizers. Returned as `text/plain` with download header.
- **REST API: `GET /api/threads`** — List all live threads with optional `?state=BLOCKED` filter. Returns thread summaries with CPU time, block/wait counts.
- **REST API: `GET /api/threads/top`** — Top-N CPU threads. Query params: `?n=10&interval=1000` (sampling interval in ms).
- **REST API: `GET /api/threads/{id}`** — Detailed thread info with full stack trace, locked monitors at each frame, and locked synchronizers.
- **REST API: `GET /api/threads/deadlocks`** — Deadlock detection results with involved threads and lock cycle info.
- **REST API: `GET /api/threads/dump`** — Plain-text thread dump download (`Content-Type: text/plain`).
- **Web UI: Threads Tab** — New "Threads" tab with thread list table (state badges, CPU time), click-to-view stack trace panel, Top CPU button, deadlock check, and thread dump export.
- **Docker Support** — Multi-stage Dockerfile with `eclipse-temurin:17-jdk`, multi-platform builds (amd64/arm64), convenience wrapper script. Docker Hub CI/CD via `docker-publish.yml` workflow.
- **27 new tests** — ThreadDiagnosticsService (14), ThreadListHandler (3), ThreadDetailHandler (4), ThreadDeadlockHandler (1), ThreadDumpHandler (3), ThreadTopHandler (3)

### Changed
- `APIRoutes` now registers 26 routes (was 21): added 5 thread diagnostics endpoints
- `RoutePermissions` updated with thread endpoint permissions (VIEWER role)
- Updated project version to 0.10.0

## [0.9.0] - 2026-04-15

### Security
- **P0.1 PBKDF2 password hashing** — Replaced single-pass SHA-256 with `PBKDF2WithHmacSHA256` (310,000 iterations, 256-bit key, 16-byte salt) in `SecurityConfig`. New self-describing hash format: `$pbkdf2-sha256$iterations$base64(salt)$base64(hash)`. Transparent migration: legacy SHA-256 hashes are auto-upgraded to PBKDF2 on next successful login.
- **P0.2 Token validation in hot-swap operators** — `HotSwapHandler` and `RollbackHandler` now delegate operator extraction to `TokenService.extractUsername()` with full HMAC verification and expiration checks. Previously, `extractOperator()` decoded the token payload without any signature validation, allowing forged operator names in audit logs. Both handlers now accept `TokenService` via constructor injection.

### Robustness
- **P1.1 Atomic validate counter** — `TokenService.validateCallCount` changed from `int` to `AtomicInteger`. The previous non-atomic `++` could lose increments under concurrent request load, causing revocation cleanup to fire unpredictably.
- **P1.2 Bounded history deque** — `HotSwapService` history replaced `CopyOnWriteArrayList` with `LinkedBlockingDeque` (capacity 200). The previous `remove(0)` in a while-loop triggered a full array copy per removal — O(n) per eviction. The deque provides O(1) bounded insertion with `pollFirst()` / `offerLast()`.
- **P1.3 Trace start race fix** — `MethodTraceService.startTrace()` now uses `tracing.compareAndSet(false, true)` instead of separate `get()` + `set()` calls. This eliminates a race window where two concurrent `startTrace()` calls could both succeed, corrupting the global `activeCollector`.
- **P1.4 Atomic service publication** — `APIRoutes` volatile fields `traceServiceInstance` and `auditLogServiceInstance` consolidated into an immutable `ServiceHolder` record with a single volatile write. Prevents handlers from seeing inconsistent service state during registration.

### Changed
- `HotSwapHandler` and `RollbackHandler` constructors now accept optional `TokenService` parameter (backward compatible)
- `TokenService` exposes `extractUsername(String bearerToken)` convenience method
- Updated project version to 0.9.0

## [0.8.0] - 2026-04-14

### Security
- **P0.1 Unified config pipeline** — Agent arguments (`security`, `adminPassword`, `auditFile`) now flow through a single path: `JoltVMAgent` → `JoltVMServer(int, Map)` → `APIRoutes.registerAll(…, agentArgs)`. Eliminates split SecurityConfig instances.
- **P0.2 Password hashing** — `SecurityConfig` now stores passwords as salted SHA-256 hashes (`hex(salt)$hex(SHA-256(salt‖password))`). Plaintext is never stored. Default admin account is flagged `passwordChangeRequired=true`; `LoginHandler` surfaces this flag in the login response.
- **P0.3 Token revocation** — `TokenService` maintains a `revokedTokens` map. `invalidateToken()` adds tokens to the revocation list; `validateToken()` rejects revoked tokens before HMAC verification. Expired revocation entries are cleaned up lazily every 100 validation calls.
- **P0.4 Login rate limiting** — New `LoginRateLimiter` (IP-based sliding-window, 10 failures / 5 minutes). `LoginHandler` injects rate limiter; `HttpDispatcherHandler` injects client IP as synthetic `"_ip"` path parameter via `X-Forwarded-For` or `channel.remoteAddress()`. Blocked requests receive HTTP 429.

### Robustness
- **P1.1 HotSwap concurrency** — `HotSwapService` uses per-class `ReentrantLock` to serialize concurrent `hotSwap` and `rollback` calls on the same class name. `BytecodeBackupService` enforces a 100-class backup limit to prevent unbounded memory growth.
- **P1.2 Graceful shutdown** — `JoltVMServer.stop()` now calls `MethodTraceService.stopAll()` to stop sampling threads and release Byte Buddy transformers. `MethodTraceService.stopTrace()` uses the saved `ResettableClassFileTransformer` reference to properly remove Advice bytecode instead of just calling `retransformClasses`.
- **P1.3 Error sanitization** — `HttpDispatcherHandler` global exception handler returns a fixed `"Internal server error (id: <uuid>)"` message; details are only written to the server log. JSON parse errors in all request handlers are replaced with `"Invalid JSON in request body."`. Operational errors (`HotSwapHandler`, `RollbackHandler`, `ClassSourceHandler`, `TraceHandler`, `StaticFileHandler`) return generic messages without exposing stack traces or internal paths.

### Product Experience
- **P2.1 CDN offline fallback** — Bundled vendor files (`d3.min.js`, `d3-flamegraph.min.js`, `d3-flamegraph.css`) in `webui/vendor/`. CDN `<script>` and `<link>` tags use `onerror` to fall back to local vendor paths automatically. Works fully offline in air-gapped environments.
- **P2.2 README alignment** — Allocation view and side-by-side comparison marked as **planned**. Added comprehensive Agent argument reference table to both `README.md` and `README_zh.md`.
- **P2.3 Audit log persistence** — `AuditLogService()` (no-arg) now defaults to `$TMPDIR/joltvm-audit.jsonl` instead of memory-only. Automatic file rotation at 10 MB (max 3 rotated files). `exportAsJsonLines()` merges in-memory buffer with persisted file content.

## [0.7.0] - 2026-04-14

### Added
- **Security: RBAC** (`Role`) — Three-tier hierarchical role-based access control: Viewer (read-only) < Operator (hot-fix + trace) < Admin (full access). Higher roles inherit all lower-role permissions via `hasPermission()`.
- **Security: Token Authentication** (`TokenService`) — HMAC-SHA256 token-based authentication with `SecureRandom` 32-byte key generation, Base64 payload encoding, configurable expiration (default 24h), in-memory active token tracking, immediate invalidation support, and constant-time signature comparison to prevent timing attacks.
- **Security: Configuration** (`SecurityConfig`) — User management with `ConcurrentHashMap` storage, credential validation, default admin account (`admin/joltvm`), and runtime enable/disable toggle for authentication.
- **Security: Route Permissions** (`RoutePermissions`) — API endpoint to minimum role mapping with 19 permission rules. Supports exact match, prefix match (for parameterized routes), and auth-exempt endpoints (`/api/auth/login`, `/api/auth/status`, static files).
- **Security: Audit Log** (`AuditLogService`) — Persistent audit logging with in-memory `CopyOnWriteArrayList` (max 1000 entries) and optional JSON Lines file persistence. Records both hot-swap operations and security events (login, access denied, etc.). Supports JSON Lines and CSV export formats.
- **Security: Authentication Middleware** — Token-based authentication and RBAC enforcement integrated into `HttpDispatcherHandler.channelRead0()` as request middleware. Extracts Bearer token, validates via `TokenService`, checks role permissions, returns 401/403 on failure.
- **REST API: `POST /api/auth/login`** — Authenticate with username/password, returns HMAC token with role and expiration. Returns dummy admin token when security is disabled.
- **REST API: `GET /api/auth/status`** — Query current authentication state. Returns `authEnabled` flag and, if a valid Bearer token is provided, the authenticated user's info.
- **REST API: `GET /api/audit/export`** — Export audit logs in JSON Lines (default) or CSV format. Requires ADMIN role. Sets `Content-Disposition` for file download.
- **Audit Integration** — `HotSwapHandler` and `RollbackHandler` now extract `operator` (from Bearer token payload) and `reason` (from request body), pass them to `HotSwapService`, and record operations to `AuditLogService`.
- **`HotSwapRecord` Extended** — Added `operator`, `reason`, and `diff` fields with backward-compatible constructor overload.
- **53+ new tests** — Role (3), SecurityConfig (10), TokenService (15), AuditLogService (12), RoutePermissions (13), APIRoutes security endpoints (2), HttpDispatcherHandler authentication/authorization (11)

### Changed
- `APIRoutes` now registers 21 routes (was 18): added 3 security/audit endpoints
- `APIRoutes` now supports overloaded `registerAll()` with `SecurityConfig` and `TokenService` parameters
- `APIRoutes` maintains `AuditLogService` singleton accessible via `getAuditLogService()`
- `HttpDispatcherHandler` now accepts optional `SecurityConfig` and `TokenService` for authentication middleware (backward compatible, 4 constructor overloads)
- `HttpDispatcherHandler` CORS preflight now includes `Authorization` in `Access-Control-Allow-Headers`
- `JoltVMServer` creates and injects security services into the Netty pipeline
- Updated project version to 0.7.0

## [0.6.0] - 2026-04-11

### Added
- **Web UI** (`StaticFileHandler`) — Browser-based Web IDE served from embedded Netty, no separate frontend server required
- **Dashboard** — Real-time JVM health monitoring with memory usage bars, uptime, PID, and quick stats
- **Class Browser** — Paginated class list with package/search filtering, click-to-view detail panel (fields, methods, modifiers, interfaces)
- **Hot-Swap Editor** — Monaco Editor integration (loaded from CDN) for editing Java source code with syntax highlighting; one-click hot-swap and rollback buttons
- **Interactive Flame Graph** — d3-flame-graph visualization (loaded from CDN) with zoomable, searchable flame charts; supports both method tracing and stack sampling data
- **Spring Boot Panel** — Bean list, request mappings, and dependency graph views with stereotype badges and method badges
- **Audit Log View** — Hot-swap operation history with action type, status, timestamps, and class names
- **Static File Serving** — MIME type resolution for 18+ file types (HTML, CSS, JS, images, fonts, WASM, etc.), path traversal protection, cache control headers
- **Dark Theme** — Catppuccin Mocha-inspired dark theme with CSS custom properties, monospace code fonts, responsive layout
- **Toast Notifications** — Non-intrusive success/error/info notifications for user actions
- **Fallback Routing** — `HttpDispatcherHandler` now falls back to static file serving for unmatched GET requests, enabling SPA-style routing
- **18 new tests** — StaticFileHandler content type resolution (11), file serving (5), cache headers (2), content length (1)

### Changed
- `HttpDispatcherHandler` now accepts optional `StaticFileHandler` for Web UI fallback (backward compatible)
- `JoltVMServer` creates and injects `StaticFileHandler` into the Netty pipeline
- Web UI assets (HTML, CSS, JS) embedded in `src/main/resources/webui/` — packaged into the agent JAR
- Updated project version to 0.6.0

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
