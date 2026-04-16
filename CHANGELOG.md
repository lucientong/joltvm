# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.17.0] - 2026-04-16

### Added
- **Plugin/SPI Extension Mechanism** — Full plugin system based on Java SPI (`ServiceLoader`).
- **JoltVMPlugin SPI Interface** — Defines plugin lifecycle: `onLoad(PluginContext)` → `onStart()` → `onStop()`. Plugins can contribute REST routes (auto-prefixed `/api/plugins/{id}/`) and web assets.
- **PluginContext** — Context object providing plugins access to the HTTP router and a namespaced logger.
- **RouteDefinition** — Record type for defining plugin-contributed REST routes with convenience `get()`/`post()` factory methods.
- **PluginLifecycleManager** — Discovers plugin JARs from `plugins/` directory, creates isolated `URLClassLoader` per JAR, loads via ServiceLoader, validates plugin IDs (rejects duplicates and invalid chars), registers routes, and manages lifecycle.
- **REST API: `GET /api/plugins`** — Lists all loaded plugins with id, name, version, source JAR, route count, and web asset count.
- **Codecov CI Fix (v2)** — Moved `CODECOV_TOKEN` from `env` to `with.token` parameter for `codecov-action@v5` compatibility. Added `codecov/test-results-action@v1` for Test Analytics integration (JUnit XML upload).
- **8 new tests** — PluginLifecycleManager (4), PluginContext (1), RouteDefinition (2), PluginListHandler (1)

### Changed
- `APIRoutes` now registers 46 routes (was 45): added plugin list endpoint. Plugin discovery adds dynamic routes at startup.
- `RoutePermissions` updated with plugin endpoint permission (VIEWER role)
- Updated project version to 0.17.0

## [0.16.0] - 2026-04-16

### Added
- **WebSocket Real-time Push** (`SubscriptionManager`) — Full WebSocket support for real-time data push to browser clients. Manages subscriptions per channel with automatic periodic push from registered data providers.
- **Netty Pipeline Upgrade** — Added `WebSocketServerProtocolHandler` at `/ws` path to the Netty pipeline. WebSocket and HTTP coexist on the same port. WebSocket handshake and frame handling are additive — existing HTTP API is unchanged.
- **WebSocket Frame Handler** (`WebSocketFrameHandler`) — Handles text frames (subscribe/unsubscribe/ping), ping/pong keep-alive, and graceful close. Delegates to SubscriptionManager.
- **Subscription Protocol** — JSON-based client-server protocol. Client sends `subscribe`/`unsubscribe`/`ping`, server pushes `data`/`subscribed`/`unsubscribed`/`pong`/`error`. Supported channels: `threads.top` (5s), `gc.stats` (10s), `jvm.memory` (5s).
- **WebSocket Client JS** (`websocket.js`) — Auto-reconnect with exponential backoff (1s → 30s max). Subscribe/unsubscribe API. REST fallback when WebSocket is unavailable. Token authentication via query parameter.
- **Web UI: Connection Status Indicator** — Green/red dot next to the status bar showing WebSocket connection state. Tooltip shows connection details.
- **12 new tests** — SubscriptionManager (12: connect/disconnect, subscribe/unsubscribe, ping/pong, invalid messages, supported channels)

### Changed
- `JoltVMServer` Netty pipeline now includes `WebSocketServerProtocolHandler` + `WebSocketFrameHandler` before `HttpDispatcherHandler`
- `JoltVMServer.stop()` now also shuts down `SubscriptionManager`
- Updated project version to 0.16.0

## [0.15.0] - 2026-04-16

### Added
- **async-profiler Integration** (`AsyncProfilerService`) — Reflection-based integration with async-profiler (no compile-time dependency). Auto-detects native library via system property, environment variable, and common installation paths. Falls back from Java API to command-line mode.
- **Platform Detection** — Automatically detects async-profiler availability on Linux/macOS. Auto-fallback from `cpu` to `itimer` event when `perf_event_paranoid` blocks hardware counters.
- **Profiling Modes** — CPU, Allocation, Lock, Wall-clock, and itimer profiling events supported.
- **Collapsed Stacks → d3-flamegraph** — Parses async-profiler collapsed stack output into d3-flamegraph-compatible JSON tree structure, reusable with the existing flame graph UI.
- **REST API: `GET /api/profiler/async/status`** — Check async-profiler availability, platform info, native lib path, and active session status.
- **REST API: `POST /api/profiler/async/start`** — Start profiling with event type, duration, and sampling interval. Returns session ID.
- **REST API: `POST /api/profiler/async/stop`** — Stop active profiling session. Returns sample count.
- **REST API: `GET /api/profiler/async/flamegraph/{id}`** — Retrieve d3-compatible flame graph JSON for a profiler session.
- **Web UI: Profiler Enhancement** — Collapsible async-profiler section added to the Flame Graph tab. Event selector (CPU/Alloc/Lock/Wall/itimer), duration input, Start/Stop/Load Flame Graph buttons, status indicator.
- **14 new tests** — AsyncProfilerService (11: status, availability, parsing, edge cases), handler tests (3)

### Changed
- `APIRoutes` now registers 45 routes (was 41): added 4 async-profiler endpoints
- `RoutePermissions` updated with async-profiler permissions (status/flamegraph: VIEWER, start/stop: OPERATOR)
- Updated project version to 0.15.0

## [0.14.0] - 2026-04-16

### Added
- **Watch Command** (`WatchService`) — Multiple concurrent method observation sessions (max 10). Each session installs its own Byte Buddy Advice transformer, captures method invocations (args, return values, exceptions, timing), and stores records in a bounded per-session buffer (max 1000). Sessions auto-expire after configured duration (default 60s, max 5min).
- **WatchSession** — Individual watch session with bounded CopyOnWriteArrayList record buffer, eviction of oldest records on overflow, configurable max records, and automatic expiration.
- **WatchAdvice** — Byte Buddy Advice class injected into watched methods. Captures arguments (truncated to 200 chars), return values, exception details, and execution duration.
- **REST API: `POST /api/watch/start`** — Start a new watch session with classPattern, methodPattern, conditionExpr, maxRecords, durationMs parameters.
- **REST API: `POST /api/watch/{id}/stop`** — Stop a specific watch session and return final summary.
- **REST API: `GET /api/watch/{id}/records`** — Fetch records from a session with optional `?since=N` pagination.
- **REST API: `GET /api/watch`** — List all watch sessions (active and stopped) with record counts and status.
- **REST API: `DELETE /api/watch/{id}`** — Delete a watch session and remove its Advice transformer.
- **Web UI: Watch Tab** — New tab with class/method input, duration control, New Watch button. Active watches table with status badges, Records/Stop/Delete actions. Record table showing timestamp, method, duration, args, return, exception.
- **17 new tests** — WatchSession (7), WatchRecord (2), WatchService (7), handler coverage

### Changed
- `APIRoutes` now registers 41 routes (was 36): added 5 watch command endpoints
- `RoutePermissions` updated with watch endpoint permissions (start/stop/delete: OPERATOR, list/records: VIEWER)
- Updated project version to 0.14.0

## [0.13.0] - 2026-04-16

### Added
- **OGNL Expression Engine** (`OgnlService`) — Safe OGNL expression evaluation against JVM runtime objects. Defense-in-depth security with four layers: pre-parse expression validation, MemberAccess sandbox, execution timeout (5s), and result depth limiting.
- **Security Sandbox** (`SafeOgnlMemberAccess`) — Comprehensive security sandbox blocking 60+ dangerous classes (Runtime, ProcessBuilder, System, Unsafe, ClassLoader, File I/O, Network, Scripting, etc.), 25+ dangerous methods (exec, exit, forName, getMethod, invoke, etc.), and 12+ blocked package prefixes (reflection, JMX, OGNL internals). Class hierarchy traversal ensures subclass access is also blocked.
- **Result Serializer** (`ResultSerializer`) — Safe JSON serialization with circular reference detection (identity-based), depth limiting (default 5, max 10), collection size limiting (max 200 elements), and graceful degradation for non-serializable objects.
- **REST API: `POST /api/ognl/eval`** — Evaluate OGNL expressions with configurable result depth. Returns structured response with success/error status, result, type, and execution time. Expression length limited to 4096 characters. Requires OPERATOR role.
- **Safe Runtime Info** — `#runtime` context variable provides read-only access to JVM info (memory, CPUs, Java version, OS) without exposing dangerous Runtime methods.
- **Web UI: Expression Tab** — Monospace textarea with Ctrl+Enter execution, preset templates (JVM Memory, CPUs, Java Version, etc.), syntax-highlighted JSON result display, and localStorage-backed expression history (50 entries).
- **Security Fuzz Tests** — 50+ parameterized test cases covering known OGNL injection vectors from Struts CVEs, Arthas attack patterns, and OWASP payloads. ALL must be blocked. Covers: Runtime.exec, System.exit, reflection chains, MemberAccess override, Unsafe, File I/O, Network, ClassLoader, ScriptEngine, Thread manipulation, serialization, OGNL internals, and Struts2 compound payloads.
- **Unit Tests** — ResultSerializer (14 tests), SafeOgnlMemberAccess (10 tests), OgnlEvalHandler (5 tests)

### Changed
- `APIRoutes` now registers 36 routes (was 35): added OGNL eval endpoint
- `RoutePermissions` updated with OGNL endpoint permission (OPERATOR role)
- Added `ognl:ognl:3.4.8` dependency to joltvm-server
- Updated project version to 0.13.0

## [0.12.0] - 2026-04-16

### Added
- **ClassLoader Analysis** (`ClassLoaderService`) — Enumerates all loaded classes via `Instrumentation.getAllLoadedClasses()`, groups by ClassLoader, builds parent-child hierarchy tree, and detects class conflicts (same FQCN loaded by multiple ClassLoaders).
- **REST API: `GET /api/classloaders`** — ClassLoader hierarchy tree with each node showing loader name, class name, loaded class count, parent info, and children.
- **REST API: `GET /api/classloaders/{id}/classes`** — Paginated list of classes loaded by a specific ClassLoader, with search filter. Max 5000 classes per page.
- **REST API: `GET /api/classloaders/conflicts`** — Detects classes loaded by multiple ClassLoaders (potential classpath conflicts).
- **Logger Dynamic Level** (`LoggerService`) — Auto-detects logging framework (Logback > Log4j2 > JUL) via reflection with zero compile-time dependencies. Supports listing all loggers and dynamically changing log levels at runtime.
- **LoggerAdapter Interface** — Pluggable adapter pattern with three implementations: `LogbackAdapter`, `Log4j2Adapter`, `JulAdapter`. All use reflection for framework interaction.
- **REST API: `GET /api/loggers`** — Lists all loggers with framework name, current level, and effective level.
- **REST API: `PUT /api/loggers/{name}`** — Dynamically changes a logger's level. Returns previous and new level. Requires OPERATOR role.
- **Web UI: ClassLoaders Tab** — Tree view of ClassLoader hierarchy with class counts, browse button to view loaded classes, conflict detection with warning badges.
- **Web UI: Loggers Tab** — Searchable logger list with inline level dropdown and Set button for dynamic level changes. Framework name and logger count displayed.
- **Codecov CI Fix** — Added explicit JaCoCo report collection step before codecov-action upload to resolve glob pattern issue with codecov-action@v5.

### Changed
- `APIRoutes` now registers 35 routes (was 30): added 3 ClassLoader endpoints + 2 Logger endpoints
- `RoutePermissions` updated with ClassLoader (VIEWER) and Logger (GET: VIEWER, PUT: OPERATOR) permissions
- Updated project version to 0.12.0

## [0.11.0] - 2026-04-15

### Added
- **JVM Info Service** (`JvmInfoService`) — Centralized service for GC statistics, system properties, environment variables, and classpath inspection. Sensitive keys (password, secret, token, api_key, etc.) are automatically redacted via regex pattern matching.
- **REST API: `GET /api/jvm/gc`** — GC statistics per collector (name, count, total time, memory pools) with overall GC overhead percentage.
- **REST API: `GET /api/jvm/sysprops`** — Sorted system properties with sensitive value redaction. Returns key-value pairs with count.
- **REST API: `GET /api/jvm/sysenv`** — Sorted environment variables with sensitive value redaction.
- **REST API: `GET /api/jvm/classpath`** — Runtime classpath entries with type (file/directory), existence check, and file size. Includes JPMS module path when available.
- **Web UI: Dashboard Enhancement** — Four collapsible `<details>` sections added to Dashboard: GC Statistics (table with collectors), System Properties (searchable/filterable table), Environment Variables (searchable/filterable table), Classpath (entries with type and size). Data loaded lazily on expand.
- **`dashboard.js`** — Extracted dashboard enhancement logic into dedicated JS module. Includes search filtering, sensitive value highlighting, and human-friendly byte/time formatting.
- **20 new tests** — JvmInfoService (15: GC stats, sysprops, sysenv, classpath, sensitive key detection), GcStatsHandler (1), SysPropsHandler (1), SysEnvHandler (1), ClasspathHandler (1), RoutePermissions JVM endpoints (1)
- **Docker CI/CD merged into release.yml** — Consolidated `docker-publish.yml` into `release.yml` as a second job (`docker`, `needs: publish`). Single tag push now triggers Maven Central → GitHub Release → Docker Hub in one atomic pipeline.

### Changed
- `APIRoutes` now registers 30 routes (was 26): added 4 JVM info endpoints
- `RoutePermissions` updated with JVM info endpoint permissions (VIEWER role)
- `release.yml` now includes Docker build & push as Job 2 (requires `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN` secrets)
- Removed standalone `docker-publish.yml` workflow
- Updated project version to 0.11.0

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
