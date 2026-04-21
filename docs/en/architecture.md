# JoltVM Architecture

This document provides a deep dive into JoltVM's architecture, module design, and implementation principles. For a quick overview and usage guide, see the [README](../../README.md).

---

## Table of Contents

- [System Overview](#system-overview)
- [Module Architecture](#module-architecture)
  - [joltvm-agent](#joltvm-agent)
  - [joltvm-server](#joltvm-server)
  - [joltvm-cli](#joltvm-cli)
  - [joltvm-ui](#joltvm-ui)
  - [joltvm-tunnel](#joltvm-tunnel)
- [Core Mechanisms](#core-mechanisms)
  - [Java Instrumentation API](#java-instrumentation-api)
  - [Agent Loading Modes](#agent-loading-modes)
  - [JDK Attach API](#jdk-attach-api)
  - [Bytecode Transformation Pipeline](#bytecode-transformation-pipeline)
  - [Class Hot-Swap & Rollback](#class-hot-swap--rollback)
  - [Method Tracing & Flame Graph](#method-tracing--flame-graph)
  - [Spring Boot Awareness](#spring-boot-awareness)
  - [Embedded Web UI](#embedded-web-ui)
  - [OGNL Expression Engine](#ognl-expression-engine)
  - [Watch Command](#watch-command)
  - [WebSocket Real-time Push](#websocket-real-time-push)
  - [Plugin/SPI Extension](#pluginspi-extension)
  - [Tunnel Remote Diagnostics](#tunnel-remote-diagnostics)
- [Data Flow](#data-flow)
- [Thread Model](#thread-model)
- [Security Model](#security-model)
- [Technology Stack](#technology-stack)
- [Project Structure](#project-structure)
- [Phased Development Plan](#phased-development-plan)

---

## System Overview

JoltVM is a JVM online diagnostics and hot-fix framework. It runs **inside** the target JVM process as a Java Agent, providing runtime introspection and bytecode modification capabilities through a browser-based Web IDE.

```
                         ┌─── Developer's Browser ───┐
                         │                           │
                         │   joltvm-ui (Web IDE)     │
                         │   ┌───────────────────┐   │
                         │   │  Monaco Editor    │   │
                         │   │  Flame Graph      │   │
                         │   │  Class Tree       │   │
                         │   │  Audit Dashboard  │   │
                         │   └────────┬──────────┘   │
                         │            │ HTTP/WS      │
                         └────────────┼──────────────┘
                                      │
                         ┌────────────┼──────────────┐
                         │  Target JVM Process       │
                         │            │              │
                         │   ┌────────┴─────────┐    │
                         │   │  joltvm-server   │    │
                         │   │  (Netty HTTP/WS) │    │
                         │   └────────┬─────────┘    │
                         │            │ Internal API │
                         │   ┌────────┴─────────┐    │
                         │   │  joltvm-agent    │    │
                         │   │  ┌─────────────┐ │    │
                         │   │  │ Instrument  │ │    │
                         │   │  │ ation API   │ │    │
                         │   │  ├─────────────┤ │    │
                         │   │  │ Bytecode    │ │    │
                         │   │  │ Engine      │ │    │
                         │   │  ├─────────────┤ │    │
                         │   │  │ Profiler    │ │    │
                         │   │  ├─────────────┤ │    │
                         │   │  │ Spring      │ │    │
                         │   │  │ Awareness   │ │    │
                         │   │  └─────────────┘ │    │
                         │   └──────────────────┘    │
                         │            │              │
                         │   ┌────────┴─────────┐    │
                         │   │  Your Application │    │
                         │   └──────────────────┘    │
                         └───────────────────────────┘

                         ┌───────────────────────────┐
                         │  Developer's Terminal      │
                         │                           │
                         │  joltvm-cli               │
                         │  (attach / list commands) │
                         └───────────────────────────┘
```

**Key design principle**: JoltVM loads entirely into the target JVM's process space. There is no separate daemon process. The embedded web server provides a zero-install browser experience — just open `http://localhost:7758`.

---

## Module Architecture

### joltvm-agent

The core module that runs inside the target JVM. It is the only module that directly interacts with the JVM's internal state.

#### Key Classes

| Class | Responsibility |
|-------|---------------|
| `JoltVMAgent` | Agent entry point. Implements `premain()` (startup) and `agentmain()` (dynamic). Idempotent initialization with double-check pattern. |
| `InstrumentationHolder` | Thread-safe singleton (`AtomicReference` + CAS) that stores the `java.lang.instrument.Instrumentation` instance. Fail-fast `get()` throws if not initialized. |
| `AttachHelper` | Uses `com.sun.tools.attach.VirtualMachine` to dynamically load the agent JAR into a running JVM. Validates PID format, resolves agent JAR path, and ensures proper detach in `finally`. |

#### Class Diagram

```
┌─────────────────────┐       ┌────────────────────────┐
│    JoltVMAgent      │       │  InstrumentationHolder  │
├─────────────────────┤       ├────────────────────────┤
│ -initialized: bool  │──set──│ -INSTANCE: AtomicRef   │
│ -BANNER: String     │       ├────────────────────────┤
├─────────────────────┤       │ +set(Instrumentation)  │
│ +premain()          │       │ +get(): Instrumentation│
│ +agentmain()        │       │ +isAvailable(): bool   │
│ -initialize()       │       └────────────────────────┘
└─────────────────────┘
           ▲
           │ loaded by
┌──────────┴──────────┐
│    AttachHelper     │
├─────────────────────┤
│ +attach(pid, args)  │
│ +listJvmProcesses() │
│ -getAgentJarPath()  │
└─────────────────────┘
```

#### Manifest Configuration

The agent JAR's `MANIFEST.MF` declares both entry points:

```
Premain-Class: com.joltvm.agent.JoltVMAgent
Agent-Class: com.joltvm.agent.JoltVMAgent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
Can-Set-Native-Method-Prefix: true
```

### joltvm-server

The embedded HTTP server module, based on Netty 4.x, that runs inside the target JVM alongside the agent. It exposes REST APIs consumed by the Web IDE frontend and CLI tools.

#### Key Classes

| Class | Responsibility |
|-------|---------------|
| `JoltVMServer` | Netty HTTP server lifecycle management (start/stop). Boss group (1 thread) + Worker group (2 threads). Default port 7758, configurable. Idempotent start/stop with `AtomicBoolean`. |
| `HttpRouter` | Path pattern matching with `{paramName}` support via regex compilation. Routes matched in registration order, first match wins. |
| `HttpDispatcherHandler` | Netty `SimpleChannelInboundHandler` that dispatches requests to `RouteHandler` via `HttpRouter`. Handles CORS preflight (OPTIONS), token-based authentication and RBAC enforcement, static file fallback, and error handling. |
| `StaticFileHandler` | Serves embedded Web UI static files from classpath `webui/` directory. Resolves MIME types for 18+ file extensions, prevents path traversal attacks, and sets cache control headers. |
| `HttpResponseHelper` | Utility class for building JSON, text, and error responses with proper Content-Type and CORS headers. |
| `APIRoutes` | Registers all API endpoints on the router during initialization. Manages 46+ routes including security/audit, thread diagnostics, JVM info, classloader, logger, OGNL, watch, profiler, WebSocket, and plugin endpoints. |
| `HealthHandler` | `GET /api/health` — Returns JVM status, PID, uptime, and memory info. |
| `ClassListHandler` | `GET /api/classes` — Paginated listing of loaded classes with package/search filters. |
| `ClassDetailHandler` | `GET /api/classes/{className}` — Detailed class info (fields, methods, modifiers). |
| `ClassSourceHandler` | `GET /api/classes/{className}/source` — CFR-powered bytecode decompilation to Java source. |
| `DecompileService` | Loads bytecode from ClassLoader, feeds to CFR via in-memory `BytecodeClassFileSource`. |
| `MethodTraceService` | Core service orchestrating method tracing (Byte Buddy Advice injection) and stack sampling. Manages tracing/sampling lifecycle with `AtomicBoolean` state flags. |
| `FlameGraphCollector` | Central data collector for trace records (`CopyOnWriteArrayList`, max 500) and stack samples (max 1000). Builds d3-flame-graph compatible tree structures. |
| `FlameGraphNode` | Tree node for flame graph data. Provides `getOrCreateChild()` for tree building and `toMap()` for d3-flame-graph JSON serialization. |
| `TraceRecord` | Immutable Java record capturing a single method invocation: class, method, arguments, return value, exception, duration, thread info, depth. |
| `TracingException` | Standard `RuntimeException` subclass for the tracing package. |
| `TraceHandler` | `POST /api/trace/start` and `POST /api/trace/stop` — Controls method tracing and stack sampling lifecycle. |
| `TraceListHandler` | `GET /api/trace/records` — Returns recorded method trace entries with configurable limit. |
| `TraceFlameGraphHandler` | `GET /api/trace/flamegraph` — Returns d3-flame-graph compatible JSON data. |
| `TraceStatusHandler` | `GET /api/trace/status` — Returns current tracing/sampling state and statistics. |
| `SpringContextService` | Reflection-based Spring ApplicationContext discovery. Lists beans, parses `@RequestMapping`, analyzes `@Controller → @Service → @Repository` dependency chains. Zero compile-time Spring dependencies. |
| `BeanListHandler` | `GET /api/spring/beans` — Paginated list of Spring beans with package/search/stereotype filtering. |
| `BeanDetailHandler` | `GET /api/spring/beans/{beanName}` — Bean detail (methods, annotations, interfaces, mappings). |
| `RequestMappingHandler` | `GET /api/spring/mappings` — URL → method request mappings with HTTP method and search filtering. |
| `DependencyChainHandler` | `GET /api/spring/dependencies/{beanName}` — Recursive dependency injection chain for a specific bean. |
| `DependencyGraphHandler` | `GET /api/spring/dependencies` — Full dependency graph across all stereotyped beans. |
| `Role` | RBAC role enum with three hierarchical levels: Viewer (1) < Operator (2) < Admin (3). Provides `hasPermission()` for upward-compatible permission checks. |
| `SecurityConfig` | Authentication configuration and user management. `ConcurrentHashMap`-based credential storage with default admin account. Runtime enable/disable toggle. |
| `TokenService` | HMAC-SHA256 token generation and validation. `SecureRandom` 32-byte key, Base64 payload, configurable expiration (default 24h), in-memory active token tracking, constant-time signature comparison. |
| `RoutePermissions` | API endpoint to minimum `Role` mapping. 19 permission rules with exact match, prefix match, and auth-exempt endpoints. |
| `AuditLogService` | Audit log service with in-memory `CopyOnWriteArrayList` (max 1000 entries) and optional JSON Lines file persistence. Records hot-swap operations and security events. Exports as JSON Lines or CSV. |
| `LoginHandler` | `POST /api/auth/login` — Authenticates username/password, returns HMAC token with role info. |
| `AuthStatusHandler` | `GET /api/auth/status` — Returns current authentication state and user info if token is provided. |
| `AuditExportHandler` | `GET /api/audit/export` — Exports audit logs in JSON Lines or CSV format. Requires ADMIN role. |

#### REST API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/health` | Health check with JVM info |
| GET | `/api/classes` | List loaded classes (paginated, filterable) |
| GET | `/api/classes/{className}` | Class detail (fields, methods, superclass) |
| GET | `/api/classes/{className}/source` | Decompiled Java source code |
| POST | `/api/compile` | Compile Java source in memory |
| POST | `/api/hotswap` | Compile + hot-swap a class |
| POST | `/api/rollback` | Rollback a hot-swapped class |
| GET | `/api/hotswap/history` | Hot-swap operation history |
| POST | `/api/trace/start` | Start method tracing or stack sampling |
| POST | `/api/trace/stop` | Stop tracing/sampling (by type or all) |
| GET | `/api/trace/records` | Recorded method trace entries (with limit) |
| GET | `/api/trace/flamegraph` | Flame graph data (d3-flame-graph format) |
| GET | `/api/trace/status` | Current tracing/sampling status |
| GET | `/api/spring/beans` | List all Spring beans (paginated, filterable) |
| GET | `/api/spring/beans/{beanName}` | Spring bean detail (methods, annotations, interfaces) |
| GET | `/api/spring/mappings` | URL → method request mappings |
| GET | `/api/spring/dependencies` | Full dependency graph (Controller→Service→Repository) |
| GET | `/api/spring/dependencies/{beanName}` | Dependency chain for a specific bean |
| POST | `/api/auth/login` | Authenticate and get token |
| GET | `/api/auth/status` | Current authentication status |
| GET | `/api/audit/export` | Export audit logs (JSON Lines or CSV) |
| GET | `/api/threads` | List all threads (filterable by state) |
| GET | `/api/threads/top` | Top-N CPU-consuming threads |
| GET | `/api/threads/{id}` | Thread detail with stack trace |
| GET | `/api/threads/deadlocks` | Deadlock detection |
| GET | `/api/threads/dump` | Plain-text thread dump (jstack format) |
| GET | `/api/jvm/gc` | GC statistics per collector |
| GET | `/api/jvm/sysprops` | System properties (sensitive values redacted) |
| GET | `/api/jvm/sysenv` | Environment variables (sensitive values redacted) |
| GET | `/api/jvm/classpath` | Runtime classpath entries |
| GET | `/api/classloaders` | ClassLoader hierarchy tree |
| GET | `/api/classloaders/{id}/classes` | Classes loaded by a specific ClassLoader |
| GET | `/api/classloaders/conflicts` | Class conflict detection |
| GET | `/api/loggers` | List all loggers with levels |
| PUT | `/api/loggers/{name}` | Change logger level at runtime |
| POST | `/api/ognl/eval` | Evaluate OGNL expression (sandboxed) |
| POST | `/api/watch/start` | Start a method watch session |
| POST | `/api/watch/{id}/stop` | Stop a watch session |
| GET | `/api/watch/{id}/records` | Fetch watch records |
| GET | `/api/watch` | List active watch sessions |
| DELETE | `/api/watch/{id}` | Delete a watch session |
| GET | `/api/profiler/async/status` | async-profiler availability |
| POST | `/api/profiler/async/start` | Start async-profiler session |
| POST | `/api/profiler/async/stop` | Stop async-profiler session |
| GET | `/api/profiler/async/flamegraph/{id}` | Flame graph data for profiler session |
| GET | `/api/plugins` | List loaded plugins |

**Design considerations**:
- Netty is chosen for its minimal footprint and zero external dependencies
- Runs on a dedicated thread pool to avoid interfering with the application
- Agent loads server via reflection (`Class.forName`) to avoid circular compile-time dependency
- WebSocket support at `/ws` enables real-time data push for threads, GC, and memory metrics
- API endpoints follow a RESTful convention under `/api/`
- Static file serving via `StaticFileHandler` provides an embedded Web UI without a separate frontend server

### joltvm-cli

A command-line tool packaged as a fat JAR (via Shadow plugin). It provides two commands:

| Command | Description |
|---------|-------------|
| `attach <pid> [agentArgs]` | Attach the JoltVM agent to a running JVM process |
| `list` | List all visible JVM processes |

**Version management**: The CLI reads its version from a `version.properties` resource file that is generated at build time by Gradle's `processResources` task, keeping it in sync with `gradle.properties`.

### joltvm-ui

*(Phase 6 — Implemented as embedded Web UI)*

Rather than a separate React/TypeScript project, the Web UI is implemented as vanilla HTML + CSS + JavaScript files embedded in `joltvm-server/src/main/resources/webui/`. This zero-build approach ensures the agent JAR is fully self-contained with no Node.js build dependency.

#### Architecture

```
Browser → GET / → HttpDispatcherHandler
                      │
                      ├── Match API route? → RouteHandler (JSON response)
                      │
                      └── No API match (GET)? → StaticFileHandler
                            │
                            ├── /           → webui/index.html
                            ├── /css/*.css  → webui/css/app.css
                            └── /js/*.js    → webui/js/api.js, app.js
```

#### Components

| File | Purpose |
|------|---------|
| `index.html` | SPA entry point with 6 navigation tabs (Dashboard, Classes, Hot-Swap, Flame Graph, Spring, Audit Log) |
| `css/app.css` | Dark theme (Catppuccin Mocha-inspired) with CSS custom properties, styles for all views |
| `js/api.js` | `JoltAPI` module wrapping all 18+ REST API endpoints using `fetch()` |
| `js/app.js` | Application logic: tab navigation, data rendering, Monaco Editor integration, d3-flame-graph visualization |

#### Key Design Decisions

- **Embedded in JAR**: Web UI assets are packaged under `src/main/resources/webui/` and served from classpath — no separate `joltvm-ui` module or frontend build step needed
- **CDN-loaded libraries**: Monaco Editor and d3-flame-graph are loaded from CDN at runtime, keeping the JAR size small
- **SPA-style routing**: `HttpDispatcherHandler` falls back to `StaticFileHandler` when no API route matches a GET request
- **Path traversal protection**: `StaticFileHandler` blocks `..` and `\` in requested paths
- **XSS prevention**: All user-generated content is escaped via the `esc()` utility function before DOM insertion

### joltvm-tunnel

*(Phase 17 — Implemented as standalone server)*

A standalone tunnel server that enables remote JVM diagnostics without direct network access. The agent initiates an outbound WebSocket connection to the tunnel server; users access remote agents through the tunnel's HTTP reverse proxy.

#### Architecture

```
                           ┌─── User Browser ─────────┐
                           │  Tunnel Dashboard        │
                           │  or JoltVM Web IDE       │
                           │  (via proxy)             │
                           └──────────┬───────────────┘
                                      │ HTTP
                           ┌──────────┴───────────────┐
                           │  joltvm-tunnel (:8800)    │
                           │                           │
                           │  /api/tunnel/agents       │
                           │  /api/tunnel/agents/{id}  │
                           │    /proxy/**  ──────────┐ │
                           │                         │ │
                           │  /ws/agent ◄────────┐   │ │
                           │                     │   │ │
                           │  AgentRegistry      │   │ │
                           │  RequestCorrelator   │   │ │
                           └─────────────────────┼───┼─┘
                                                 │   │
                        ┌───── WebSocket ─────────┘   │
                        │    (outbound from agent)    │
                        │                              │
               ┌────────┴───────────┐     ┌───────────┘
               │  Target JVM (behind│     │ HTTP proxied
               │  firewall)         │◄────┘ via WS tunnel
               │                    │
               │  joltvm-agent      │
               │  + TunnelClient    │
               │  + JoltVM Server   │
               └────────────────────┘
```

#### Key Classes

| Class | Responsibility |
|-------|---------------|
| `TunnelProtocol` | Shared JSON-based WebSocket protocol: `register`, `registered`, `heartbeat`, `heartbeat_ack`, `request`, `response`, `error`. |
| `AgentRegistry` | Thread-safe registry of connected agents. Pre-shared token validation, heartbeat tracking, channel-to-agent reverse index. |
| `RequestCorrelator` | Async request/response correlation via `CompletableFuture`. Auto-timeout (30s), bulk cancellation on disconnect. |
| `TunnelServer` | Standalone Netty server. Dual pipeline: WebSocket at `/ws/agent` + HTTP for REST API and dashboard. |
| `AgentWebSocketHandler` | Processes agent registration, heartbeats, and proxied responses. |
| `TunnelHttpHandler` | HTTP handler for tunnel REST API and dashboard static files. Reverse-proxies requests to agents via WebSocket. |
| `TunnelClient` (in joltvm-server) | Agent-side outbound WebSocket client. Auto-reconnect (1s→30s exponential backoff), heartbeat (30s), proxied request forwarding to local JoltVM server. |

#### Tunnel REST API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/tunnel/health` | Tunnel server health (version, agent count, pending requests) |
| GET | `/api/tunnel/agents` | List connected agents with metadata |
| GET | `/api/tunnel/agents/{id}` | Agent detail (metadata, status, remote address) |
| * | `/api/tunnel/agents/{id}/proxy/**` | Reverse proxy — forwards to agent's local JoltVM server |

---

## Core Mechanisms

### Java Instrumentation API

The foundation of JoltVM is the `java.lang.instrument` API, introduced in Java 5 and enhanced in subsequent versions. Key capabilities:

```java
public interface Instrumentation {
    // Class transformation (Phase 3)
    void redefineClasses(ClassDefinition... definitions);
    void addTransformer(ClassFileTransformer transformer, boolean canRetransform);
    void retransformClasses(Class<?>... classes);

    // Class inspection (Phase 2)
    Class[] getAllLoadedClasses();
    Class[] getInitiatedClasses(ClassLoader loader);
    long getObjectSize(Object objectToSize);

    // Capability queries
    boolean isRedefineClassesSupported();
    boolean isRetransformClassesSupported();
    boolean isNativeMethodPrefixSupported();
}
```

JoltVM stores the `Instrumentation` instance in `InstrumentationHolder` using a CAS-based singleton pattern, ensuring thread safety without locks:

```java
private static final AtomicReference<Instrumentation> INSTANCE = new AtomicReference<>();

public static void set(Instrumentation inst) {
    INSTANCE.compareAndSet(null, inst);  // Only the first call succeeds
}
```

### Agent Loading Modes

JoltVM supports two standard Java Agent loading modes:

#### 1. Startup Attachment (`premain`)

```bash
java -javaagent:joltvm-agent.jar=port=7758 -jar app.jar
```

```
JVM Startup
    │
    ├── Load bootstrap classes
    ├── Load joltvm-agent.jar
    ├── Call JoltVMAgent.premain(args, inst)  ◄── JoltVM initialized here
    ├── Load application classes
    └── Call Application.main()
```

**Advantage**: The agent is loaded before any application classes, enabling instrumentation of all classes from the start.

#### 2. Dynamic Attachment (`agentmain`)

```bash
java -jar joltvm-cli.jar attach <pid>
```

```
Running JVM (pid=12345)              joltvm-cli
    │                                    │
    │    ◄───── VirtualMachine.attach ───┤
    │    ◄───── vm.loadAgent(jar, args) ─┤
    │                                    │
    ├── JVM loads joltvm-agent.jar       │
    ├── Calls JoltVMAgent.agentmain()    │
    │                                    │
    │    ─────── vm.detach ─────────────►│
    │                                    │
    ▼ (agent continues running inside)   ▼ (CLI exits)
```

**Advantage**: No application restart required. Attach to a running JVM when issues are detected.

### JDK Attach API

The `com.sun.tools.attach` API enables cross-process communication with running JVMs:

```java
// AttachHelper.attach() implementation flow:
public static void attach(String pid, String agentArgs) throws Exception {
    // 1. Validate PID (must be a positive integer)
    Long.parseLong(pid.trim());

    // 2. Resolve agent JAR path from CodeSource
    String jarPath = getAgentJarPath();

    // 3. Attach → Load → Detach
    VirtualMachine vm = VirtualMachine.attach(pid);
    try {
        vm.loadAgent(jarPath, agentArgs);
    } finally {
        vm.detach();  // Always detach to release resources
    }
}
```

**Platform considerations**:
- On Linux, the Attach API uses Unix domain sockets (`/tmp/.java_pid<pid>`)
- On macOS, it uses a similar mechanism but may require running with elevated permissions
- On Windows, it uses named pipes

### Bytecode Transformation Pipeline

*(Phase 3 — Implemented)*

```
Source Code (via REST API)
        │
        ▼
┌─────────────────────┐
│ InMemoryCompiler    │  javax.tools.JavaCompiler
│ (in-memory compile) │  No temp files needed
└────────┬────────────┘
         │ byte[]
         ▼
┌─────────────────────┐
│ BytecodeBackupService│  Save original bytecode
│ (ConcurrentHashMap)  │  for rollback (putIfAbsent)
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│ HotSwapService      │  inst.redefineClasses(
│ .hotSwap()          │    new ClassDefinition(clazz, bytes))
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│ HotSwapRecord       │  Audit trail: action, status,
│ (History)           │  timestamp, message
└─────────────────────┘
```

**In-memory compilation**: `InMemoryCompiler` uses a custom `ForwardingJavaFileManager` with `InMemorySourceFile` and `InMemoryClassFile` to compile Java source entirely in memory. Bytecode is captured via a custom `ByteArrayOutputStream.close()` callback — no temp files are created.

**Rollback mechanism**: Before each hot-swap, `BytecodeBackupService` saves the original bytecode in a `ConcurrentHashMap<String, byte[]>` using `putIfAbsent` (first backup is never overwritten). Rollback retrieves the backup and re-applies it via `redefineClasses()`.

**Audit trail**: Every hot-swap and rollback operation is recorded as a `HotSwapRecord` with id, className, action (HOTSWAP/ROLLBACK), status (SUCCESS/FAILED), message, timestamp, operator (from Bearer token), reason (from request body), and diff (auto-generated bytecode summary). History is stored in a `CopyOnWriteArrayList` with a maximum of 200 entries.

### Class Hot-Swap & Rollback

*(Phase 3 — Implemented)*

JVM hot-swap via `redefineClasses()` has strict constraints:

| Allowed | Not Allowed |
|---------|-------------|
| Change method bodies | Add/remove methods |
| Change constant pool entries | Add/remove fields |
| Add/remove private methods (Java 9+) | Change class hierarchy |
| | Change method signatures |

JoltVM validates changes before applying and provides clear error messages when constraints are violated. The `HotSwapService` handles:
- **Capability check**: Verifies `isRedefineClassesSupported()` before attempting hot-swap
- **Class lookup**: Finds the target class among all loaded classes via `Instrumentation.getAllLoadedClasses()`
- **Modifiability check**: Ensures the class is modifiable via `isModifiableClass()`
- **Automatic backup**: Original bytecode is backed up before any modification
- **Error handling**: Catches `UnsupportedOperationException`, `ClassFormatError`, and `UnmodifiableClassException` with descriptive messages

### Method Tracing & Flame Graph

*(Phase 4 — Implemented)*

JoltVM provides two complementary profiling mechanisms: **method tracing** (Byte Buddy Advice injection) and **stack sampling** (periodic `Thread.getAllStackTraces()`).

#### Method Tracing (Byte Buddy Advice)

```
POST /api/trace/start  { "type": "trace", "className": "com.example.MyService", "methodName": "process" }
        │
        ▼
┌─────────────────────────┐
│ MethodTraceService      │  Builds AgentBuilder with
│ .startTrace()           │  RETRANSFORMATION strategy
└────────┬────────────────┘
         │
         ▼
┌─────────────────────────┐
│ Byte Buddy AgentBuilder │  disableClassFormatChanges()
│ + MethodTraceAdvice     │  Advice.to(MethodTraceAdvice.class)
└────────┬────────────────┘
         │ @Advice.OnMethodEnter / @Advice.OnMethodExit
         ▼
┌─────────────────────────┐
│ MethodTraceAdvice       │  Captures: arguments, return value,
│ (static Advice class)   │  exception, duration (nanos),
│                         │  thread info, call depth
└────────┬────────────────┘
         │ TraceRecord
         ▼
┌─────────────────────────┐
│ FlameGraphCollector     │  CopyOnWriteArrayList<TraceRecord>
│ .addRecord()            │  (max 500 records, FIFO)
└─────────────────────────┘
```

**Key design decisions**:
- **`RETRANSFORMATION` strategy**: Uses `AgentBuilder.Default(new ByteBuddy().with(TypeValidation.DISABLED))` with `disableClassFormatChanges()` for safe runtime instrumentation that can be reverted
- **Static `activeCollector` field**: Byte Buddy Advice classes must be static; a `volatile` static reference bridges Advice callbacks to the service's `FlameGraphCollector` instance
- **`Assigner.Typing.DYNAMIC`**: Required for `@Advice.Return` annotation to handle dynamic return types across all instrumented methods
- **`ConcurrentHashMap.newKeySet()`**: Tracks which classes are currently being traced for idempotent stop/cleanup

#### Stack Sampling

```
POST /api/trace/start  { "type": "sample", "interval": 10 }
        │
        ▼
┌─────────────────────────┐
│ MethodTraceService      │  Spawns daemon thread:
│ .startSampling()        │  "joltvm-stack-sampler"
└────────┬────────────────┘
         │ Thread.getAllStackTraces() at interval ms
         ▼
┌─────────────────────────┐
│ FlameGraphCollector     │  CopyOnWriteArrayList<StackTraceElement[]>
│ .addStackSample()       │  (max 1000 samples, FIFO)
└────────┬────────────────┘
         │ buildFlameGraphFromSamples()
         ▼
┌─────────────────────────┐
│ FlameGraphNode (tree)   │  Root → package → class → method
│ .toMap()                │  Output: { name, value, children }
└─────────────────────────┘  Compatible with d3-flame-graph
```

**Sampling filter**: Daemon threads and threads whose name starts with `joltvm-` are excluded from sampling to avoid polluting the flame graph with JoltVM's own overhead.

#### Flame Graph Data Model

`FlameGraphNode` builds a tree structure compatible with [d3-flame-graph](https://github.com/nicedoc/d3-flame-graph):

```json
{
  "name": "root",
  "value": 42,
  "children": [
    {
      "name": "com.example.Service.process",
      "value": 30,
      "children": [
        { "name": "com.example.Dao.query", "value": 25, "children": [] }
      ]
    }
  ]
}
```

Two data sources feed the flame graph:
1. **Trace records**: Method-level invocation data from Byte Buddy Advice (preferred when stack samples are unavailable)
2. **Stack samples**: CPU profile data from periodic `Thread.getAllStackTraces()` calls (preferred when available, as it captures the full call stack)

### Spring Boot Awareness

*(Phase 5 — Implemented)*

JoltVM can introspect Spring Boot applications **without any compile-time Spring dependencies**. All interaction with Spring classes is done purely via reflection, making it compatible with both Spring Boot 2.x and 3.x.

#### Architecture

```
Target JVM with Spring Boot
        │
        ▼
┌─────────────────────────┐
│ SpringContextService    │  Discovers ApplicationContext via
│ .detectSpringContext()   │  Thread.currentThread().getContextClassLoader()
└────────┬────────────────┘  + reflection on LiveBeansView, WebApplicationContext
         │
         ├── listBeans()          → GET /api/spring/beans
         ├── getBeanDetail()      → GET /api/spring/beans/{beanName}
         ├── getRequestMappings() → GET /api/spring/mappings
         ├── getDependencyChain() → GET /api/spring/dependencies/{beanName}
         └── getDependencyGraph() → GET /api/spring/dependencies
```

#### Key Design Decisions

- **Zero Spring compile-time dependency**: All Spring classes (`ApplicationContext`, `@RequestMapping`, `@Autowired`, etc.) are accessed via `Class.forName()` and `Method.invoke()`. This ensures the agent JAR doesn't pull in Spring Boot as a transitive dependency.
- **Graceful degradation**: When attached to a non-Spring JVM, all endpoints return HTTP 503 with `{"springDetected": false}`. No errors or exceptions are thrown.
- **Stereotype detection**: Recognizes `@Controller`, `@RestController`, `@Service`, `@Repository`, `@Component`, and `@Configuration` annotations for bean classification.
- **Dependency chain analysis**: Scans `@Autowired`, `@Inject` (`jakarta.inject` and `javax.inject`), and `@Resource` annotations on fields and constructor parameters. Recursively builds the dependency tree with circular dependency detection.
- **Request mapping parsing**: Supports `@RequestMapping`, `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@PatchMapping`. Extracts URL patterns and HTTP methods.

### Embedded Web UI

*(Phase 6 — Implemented)*

JoltVM ships a fully embedded browser-based Web UI inside the agent JAR. When a developer opens `http://localhost:7758`, the Netty server serves the Web UI assets directly from the classpath — no separate frontend build or deployment required.

#### Request Routing

```
HTTP GET request
        │
        ▼
┌─────────────────────────┐
│ HttpDispatcherHandler    │
│ .channelRead0()          │
└────────┬────────────────┘
         │
         ├── Router.match() ──► API RouteHandler (JSON)
         │
         └── No match + GET ──► StaticFileHandler
                                    │
                                    ├── Resolve path ("/" → "index.html")
                                    ├── Validate path (block ".." and "\")
                                    ├── Read from classpath: webui/{path}
                                    ├── Resolve Content-Type (18+ MIME types)
                                    ├── Set Cache-Control headers
                                    └── Return FullHttpResponse
```

#### Six UI Views

| View | Data Source | Key Features |
|------|------------|--------------|
| **Dashboard** | `/api/health` | JVM info, memory usage, uptime display |
| **Classes** | `/api/classes`, `/api/classes/{name}/source` | Paginated class browser, decompiled source viewer |
| **Hot-Swap** | `/api/hotswap`, `/api/rollback` | Monaco Editor (CDN), compile + hot-swap, rollback |
| **Flame Graph** | `/api/trace/start`, `/api/trace/flamegraph` | d3-flame-graph (CDN), method tracing, stack sampling |
| **Spring** | `/api/spring/beans`, `/api/spring/mappings`, `/api/spring/dependencies` | Bean browser, request mappings, dependency visualization |
| **Audit Log** | `/api/hotswap/history` | Hot-swap/rollback history with status badges |

#### Static File Handler

`StaticFileHandler` implements the `RouteHandler` interface and provides:

- **MIME type resolution**: Maps 18+ file extensions (HTML, CSS, JS, JSON, images, fonts, WASM, etc.) to proper `Content-Type` headers
- **Path traversal protection**: Rejects any path containing `..` or `\` with HTTP 400
- **Cache control**: `no-cache` for HTML files (always fresh), `max-age=86400` for static assets (CSS, JS, images)
- **Classpath loading**: Reads files from `webui/` base directory via `ClassLoader.getResourceAsStream()`
- **Default document**: Requests to `/` are automatically served as `index.html`

### OGNL Expression Engine

*(Phase 13 — Implemented)*

JoltVM provides a secure OGNL expression evaluation engine for inspecting runtime objects. Four-layer defense-in-depth security:

1. **Pre-parse validation** — Regex-based expression scanning before OGNL parsing
2. **MemberAccess sandbox** (`SafeOgnlMemberAccess`) — 60+ blocked classes (Runtime, ProcessBuilder, Unsafe, ClassLoader, File, Network, etc.), 25+ blocked methods, 12+ blocked package prefixes, class hierarchy traversal
3. **Execution timeout** — 5-second timeout via `Future.get(5, SECONDS)` in a dedicated thread
4. **Result depth limiting** (`ResultSerializer`) — Identity-based circular reference detection, depth limit (default 5, max 10), collection size limit (200)

### Watch Command

*(Phase 14 — Implemented)*

Multiple concurrent method observation sessions (max 10). Each `WatchSession` owns its own Byte Buddy `ResettableClassFileTransformer`. OGNL context variables: `#args`, `#returnObj`, `#throwExp`, `#cost`, `#target`, `#clazz`. Sessions auto-expire after configurable duration (default 60s, max 5min).

### WebSocket Real-time Push

*(Phase 16 — Implemented)*

Netty pipeline includes `WebSocketServerProtocolHandler` at `/ws`. JSON-based pub/sub protocol with channels: `threads.top` (5s), `gc.stats` (10s), `jvm.memory` (5s). `SubscriptionManager` manages per-channel subscriptions with periodic data push. Client-side `websocket.js` provides auto-reconnect with exponential backoff and REST fallback.

### Plugin/SPI Extension

*(Phase 17 — Implemented)*

`JoltVMPlugin` SPI interface loaded via `ServiceLoader` from `plugins/` directory. Each plugin JAR gets its own `URLClassLoader` for isolation. Plugins can contribute REST routes (auto-prefixed `/api/plugins/{id}/`) and web assets. `PluginLifecycleManager` handles discovery, ID validation, route registration, and lifecycle management.

### Tunnel Remote Diagnostics

*(Phase 17 — Implemented)*

See [joltvm-tunnel](#joltvm-tunnel) module above. The tunnel enables diagnosing JVMs behind firewalls or in Kubernetes pods without opening inbound ports.

```
Agent → TunnelClient → outbound WS → TunnelServer → AgentRegistry
                                              ↕
User → HTTP request → TunnelHttpHandler → RequestCorrelator → WS frame → Agent
                                              ↕
Agent → WS response → RequestCorrelator.complete() → HTTP response → User
```

---

## Data Flow

### Attach Flow (Phase 1 — Implemented)

```
┌──────────┐    ┌───────────┐    ┌─────────────┐    ┌──────────┐
│ Developer │───►│ joltvm-cli│───►│ Attach API  │───►│ Target   │
│           │    │           │    │ (Unix sock) │    │ JVM      │
│ attach    │    │ validate  │    │ loadAgent() │    │ agentmain│
│ <pid>     │    │ PID       │    │             │    │ called   │
└──────────┘    └───────────┘    └─────────────┘    └──────────┘
```

### Diagnostics Flow (Phase 2 — Implemented)

```
┌──────────┐    ┌───────────┐    ┌─────────────┐    ┌──────────┐
│ Browser  │◄──►│  Netty    │◄──►│ joltvm-agent│───►│ Target   │
│ Web IDE  │    │  Server   │    │ bytecode    │    │ JVM      │
│          │    │  :7758    │    │ engine      │    │ Classes  │
│ Monaco   │    │ REST API  │    │ profiler    │    │          │
│ Flame    │    │ WebSocket │    │ spring ctx  │    │          │
└──────────┘    └───────────┘    └─────────────┘    └──────────┘
```

---

## Thread Model

JoltVM is designed to minimize impact on the target application:

| Thread / Pool | Purpose | Impact |
|---------------|---------|--------|
| Agent init thread | One-time initialization during `premain`/`agentmain` | Minimal — runs once |
| Netty boss group (1 thread) | Accept incoming HTTP/WS connections | Negligible |
| Netty worker group (2 threads) | Handle HTTP requests and WebSocket frames | Low — only active during diagnostics |
| Profiler sampling thread | Periodic stack sampling for flame graphs | Configurable sampling interval |
| WebSocket push scheduler (1 thread) | Periodic data push to WebSocket subscribers | Low — daemon thread, 5-10s intervals |
| Tunnel client I/O (1 thread) | Outbound WebSocket to tunnel server | Negligible — only when tunnel is configured |
| Tunnel heartbeat scheduler | Periodic heartbeat to tunnel server (30s) | Negligible |
| Application threads | Not modified by JoltVM | Zero impact when not actively profiling |

---

## Security Model

*(Phase 7 — Implemented)*

JoltVM provides a complete embedded RBAC + Token authentication system:

```
HTTP Request → HttpDispatcherHandler (Auth Middleware)
                 ├── RoutePermissions.getRequiredRole(method, path)
                 │     ├── null → No auth needed (login, static files)
                 │     └── Role → Requires authentication
                 ├── Extract Bearer token from Authorization header
                 ├── TokenService.validateToken(token)
                 │     ├── null → 401 Unauthorized
                 │     └── TokenInfo → Check role permission
                 ├── role.hasPermission(required)?
                 │     ├── false → 403 Forbidden
                 │     └── true → Continue to route handler
                 └── Route to Handler
                       ├── LoginHandler (POST /api/auth/login)
                       ├── AuthStatusHandler (GET /api/auth/status)
                       ├── AuditExportHandler (GET /api/audit/export)
                       └── ... other business handlers
```

### Key Components

| Component | Responsibility |
|-----------|---------------|
| `Role` | Three-tier RBAC: Viewer (read-only) < Operator (hot-fix + trace) < Admin (full access) |
| `SecurityConfig` | User management, credential validation, runtime enable/disable |
| `TokenService` | HMAC-SHA256 token generation/validation, 24h expiration, constant-time comparison |
| `RoutePermissions` | API endpoint → minimum role mapping, auth-exempt endpoints |
| `AuditLogService` | Immutable audit log with file persistence and JSON/CSV export |

### Permission Matrix

| Endpoint | Method | Required Role |
|----------|--------|---------------|
| `/api/auth/login` | POST | *(none — public)* |
| `/api/auth/status` | GET | *(none — public)* |
| Static files (`/`, `/css/*`, `/js/*`) | GET | *(none — public)* |
| `/api/health` | GET | VIEWER |
| `/api/classes`, `/api/classes/{name}` | GET | VIEWER |
| `/api/hotswap/history` | GET | VIEWER |
| `/api/trace/records`, `/api/trace/flamegraph`, `/api/trace/status` | GET | VIEWER |
| `/api/spring/beans`, `/api/spring/mappings`, `/api/spring/dependencies` | GET | VIEWER |
| `/api/compile` | POST | OPERATOR |
| `/api/hotswap`, `/api/rollback` | POST | OPERATOR |
| `/api/trace/start`, `/api/trace/stop` | POST | OPERATOR |
| `/api/audit/export` | GET | ADMIN |

### Token Format

```
base64url(username:role:expirationEpochSeconds).base64url(HMAC-SHA256-signature)
```

- **Secret key**: 32 bytes from `SecureRandom` (generated per JVM session)
- **Expiration**: Configurable, default 24 hours
- **Signature verification**: Constant-time comparison to prevent timing attacks
- **Active token tracking**: In-memory `ConcurrentHashMap` for immediate invalidation

### Security Toggle

Security can be completely disabled via `SecurityConfig.setEnabled(false)`. When disabled, all requests are allowed without authentication — suitable for development environments.

---

## Technology Stack

| Component | Technology | Why |
|-----------|-----------|-----|
| Agent core | `java.lang.instrument` | JVM standard API, zero dependencies |
| Dynamic attach | `com.sun.tools.attach` | JDK built-in, cross-platform |
| Bytecode manipulation | Byte Buddy | High-level API, mature and well-maintained |
| Decompilation | CFR | Best-in-class Java decompiler |
| HTTP server | Netty 4.x | Lightweight, embeddable, async |
| Serialization | Gson | Simple, fast, no reflection issues in agent context |
| Logging | JUL (`java.util.logging`) | Zero dependencies — critical for agent bootstrap |
| Build | Gradle + Shadow | Multi-module builds + fat JAR packaging |
| CI/CD | GitHub Actions | Automated testing + Maven Central publishing |
| Frontend | Vanilla HTML + CSS + JS | Zero build dependency, embedded in agent JAR |
| Code editor | Monaco Editor (CDN) | VS Code's editor component, loaded on-demand |
| Flame graph | d3-flame-graph (CDN) | Interactive, zoomable flame graphs |
| OGNL | ognl:ognl 3.4.x | Runtime expression evaluation (same as Arthas) |
| async-profiler | Reflection-based | CPU/Alloc/Lock profiling, no compile-time dep |

---

## Project Structure

```
joltvm/
├── build.gradle.kts               # Root build: plugins, maven-publish, signing
├── settings.gradle.kts             # Multi-module project settings
├── gradle.properties               # Version numbers, project metadata
├── gradlew / gradlew.bat           # Gradle Wrapper
│
├── joltvm-agent/                   # ── Java Agent Core ──
│   ├── build.gradle.kts            # Shadow JAR + MANIFEST.MF config
│   └── src/
│       ├── main/java/com/joltvm/agent/
│       │   ├── JoltVMAgent.java        # premain() / agentmain() entry
│       │   ├── InstrumentationHolder.java  # Thread-safe Instrumentation singleton
│       │   ├── AttachHelper.java       # Attach API wrapper
│       │   └── package-info.java       # Package-level Javadoc
│       └── test/java/com/joltvm/agent/
│           ├── JoltVMAgentTest.java        # 11 tests
│           ├── InstrumentationHolderTest.java
│           └── AttachHelperTest.java       # 15 tests (PID validation, etc.)
│
├── joltvm-server/                  # ── Embedded Web Server (Phase 2–7) ──
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/com/joltvm/server/
│       │   ├── JoltVMServer.java           # Netty HTTP server lifecycle
│       │   ├── HttpRouter.java             # Path pattern matching + params
│       │   ├── HttpDispatcherHandler.java  # Request dispatch + CORS + auth middleware + static fallback
│       │   ├── HttpResponseHelper.java     # JSON/text response builder
│       │   ├── RouteHandler.java           # Functional route handler interface
│       │   ├── APIRoutes.java              # API route registration (21 routes)
│       │   ├── handler/
│       │   │   ├── HealthHandler.java      # GET /api/health
│       │   │   ├── ClassListHandler.java   # GET /api/classes
│       │   │   ├── ClassDetailHandler.java # GET /api/classes/{className}
│       │   │   ├── ClassSourceHandler.java # GET /api/classes/{className}/source
│       │   │   ├── ClassFinder.java        # Utility for finding loaded classes
│       │   │   ├── CompileHandler.java     # POST /api/compile
│       │   │   ├── HotSwapHandler.java     # POST /api/hotswap
│       │   │   ├── RollbackHandler.java    # POST /api/rollback
│       │   │   ├── HotSwapHistoryHandler.java # GET /api/hotswap/history
│       │   │   ├── TraceHandler.java       # POST /api/trace/start, /api/trace/stop
│       │   │   ├── TraceListHandler.java   # GET /api/trace/records
│       │   │   ├── TraceFlameGraphHandler.java # GET /api/trace/flamegraph
│       │   │   ├── TraceStatusHandler.java # GET /api/trace/status
│       │   │   ├── StaticFileHandler.java  # Serves embedded Web UI static files
│       │   │   ├── BeanListHandler.java    # GET /api/spring/beans
│       │   │   ├── BeanDetailHandler.java  # GET /api/spring/beans/{beanName}
│       │   │   ├── RequestMappingHandler.java # GET /api/spring/mappings
│       │   │   ├── DependencyGraphHandler.java # GET /api/spring/dependencies
│       │   │   ├── DependencyChainHandler.java # GET /api/spring/dependencies/{beanName}
│       │   │   ├── LoginHandler.java       # POST /api/auth/login
│       │   │   ├── AuthStatusHandler.java  # GET /api/auth/status
│       │   │   └── AuditExportHandler.java # GET /api/audit/export
│       │   ├── compile/
│       │   │   ├── InMemoryCompiler.java   # javax.tools in-memory compilation
│       │   │   ├── CompileResult.java      # Compilation result record
│       │   │   └── CompileException.java   # Compilation error
│       │   ├── decompile/
│       │   │   ├── DecompileService.java   # CFR decompilation service
│       │   │   └── DecompileException.java # Decompilation error
│       │   ├── hotswap/
│       │   │   ├── HotSwapService.java     # Hot-swap orchestration + rollback
│       │   │   ├── BytecodeBackupService.java # Original bytecode backup store
│       │   │   ├── HotSwapRecord.java      # Audit trail record
│       │   │   └── HotSwapException.java   # Hot-swap error
│       │   ├── tracing/
│       │   │   ├── MethodTraceService.java # Method tracing + stack sampling orchestration
│       │   │   ├── FlameGraphCollector.java # Trace record + stack sample collector
│       │   │   ├── FlameGraphNode.java     # Flame graph tree node (d3-compatible)
│       │   │   ├── TraceRecord.java        # Single method invocation record
│       │   │   └── TracingException.java   # Tracing error
│       │   ├── spring/
│       │   │   ├── SpringContextService.java # Spring context discovery + bean analysis
│       │   │   └── package-info.java
│       │   ├── security/
│       │   │   ├── Role.java               # RBAC roles (Viewer/Operator/Admin)
│       │   │   ├── SecurityConfig.java     # Auth configuration + user management
│       │   │   ├── TokenService.java       # HMAC-SHA256 token generation/validation
│       │   │   ├── RoutePermissions.java   # API endpoint → role mapping
│       │   │   ├── AuditLogService.java    # Audit log persistence + export
│       │   │   └── package-info.java
│       │   └── package-info.java
│       ├── main/resources/webui/            # ── Embedded Web UI Assets ──
│       │   ├── index.html                   # SPA entry point (6 views)
│       │   ├── css/app.css                  # Dark theme styles
│       │   └── js/
│       │       ├── api.js                   # REST API client module
│       │       └── app.js                   # Application logic + UI rendering
│       └── test/java/com/joltvm/server/
│           ├── HttpRouterTest.java
│           ├── HttpResponseHelperTest.java
│           ├── JoltVMServerTest.java
│           ├── APIRoutesTest.java
│           ├── handler/
│           │   ├── HealthHandlerTest.java
│           │   ├── ClassListHandlerTest.java
│           │   ├── ClassDetailHandlerTest.java
│           │   ├── ClassSourceHandlerTest.java
│           │   ├── CompileHandlerTest.java
│           │   ├── HotSwapHandlerTest.java
│           │   ├── RollbackHandlerTest.java
│           │   ├── HotSwapHistoryHandlerTest.java
│           │   ├── TraceHandlerTest.java
│           │   ├── TraceListHandlerTest.java
│           │   ├── TraceFlameGraphHandlerTest.java
│           │   ├── TraceStatusHandlerTest.java
│           │   ├── BeanListHandlerTest.java
│           │   ├── BeanDetailHandlerTest.java
│           │   ├── RequestMappingHandlerTest.java
│           │   ├── DependencyChainHandlerTest.java
│           │   ├── DependencyGraphHandlerTest.java
│           │   └── StaticFileHandlerTest.java  # 18 tests (MIME types, serving, security)
│           ├── compile/
│           │   └── InMemoryCompilerTest.java
│           ├── decompile/
│           │   └── DecompileServiceTest.java
│           ├── hotswap/
│           │   ├── HotSwapServiceTest.java
│           │   ├── BytecodeBackupServiceTest.java
│           │   └── HotSwapRecordTest.java
│           ├── tracing/
│           │   ├── MethodTraceServiceTest.java
│           │   ├── FlameGraphCollectorTest.java
│           │   ├── FlameGraphNodeTest.java
│           │   └── TraceRecordTest.java
│           └── spring/
│               ├── SpringContextServiceTest.java
│               └── StubSpringContextService.java
│
├── joltvm-cli/                     # ── Command-Line Tool ──
│   ├── build.gradle.kts            # Shadow JAR + processResources for version
│   └── src/
│       ├── main/java/com/joltvm/cli/
│       │   ├── JoltVMCli.java          # CLI entry point (attach, list)
│       │   └── package-info.java
│       └── main/resources/
│           └── version.properties      # Generated at build time
│
├── docs/
│   ├── en/architecture.md          # This document
│   └── zh/architecture.md          # Chinese version
│
├── .github/workflows/
│   ├── ci.yml                      # CI: build + test on every push/PR
│   └── release.yml                 # Release: Maven Central + GitHub Releases
│
├── README.md                       # English README
├── README_zh.md                    # Chinese README
├── LICENSE                         # Apache License 2.0
└── CHANGELOG.md                    # Version changelog
```

---

## Phased Development Plan

| Phase | Scope | Key Technologies | Status |
|-------|-------|-----------------|--------|
| **Phase 1** | Agent skeleton + Attach API + CLI | `java.lang.instrument`, `com.sun.tools.attach` | ✅ Complete |
| **Phase 2** | Netty web server + basic APIs (list classes, decompile) | Netty, CFR | ✅ Complete |
| **Phase 3** | Hot-swap + rollback | `redefineClasses()`, `javax.tools.JavaCompiler` | ✅ Complete |
| **Phase 4** | Method tracing + flame graph data | Byte Buddy Advice, stack sampling | ✅ Complete |
| **Phase 5** | Spring Boot awareness | Spring `ApplicationContext`, `RequestMappingHandlerMapping` | ✅ Complete |
| **Phase 6** | Web UI | Vanilla JS, Monaco Editor (CDN), d3-flame-graph (CDN) | ✅ Complete |
| **Phase 7** | Security & Audit (RBAC + token auth + audit log + export) | HMAC-SHA256, `ConcurrentHashMap`, JSON Lines | ✅ Complete |
| **Phase 8** | Security hardening (PBKDF2, bug fixes, thread safety) | PBKDF2WithHmacSHA256, AtomicInteger | ✅ Complete |
| **Phase 9** | Thread diagnostics (list, CPU top-N, deadlock, dump) | `ThreadMXBean` | ✅ Complete |
| **Phase 10** | JVM dashboard (GC stats, system properties, classpath) | `GarbageCollectorMXBean` | ✅ Complete |
| **Phase 11** | ClassLoader analysis + Logger dynamic level | `Instrumentation.getAllLoadedClasses()`, reflection | ✅ Complete |
| **Phase 12** | OGNL expression engine (secure sandbox) | OGNL 3.4.x, 4-layer defense-in-depth | ✅ Complete |
| **Phase 13** | Watch command (conditional method observation) | Byte Buddy Advice, OGNL filters | ✅ Complete |
| **Phase 14** | async-profiler integration (CPU/Alloc/Lock) | Reflection-based, collapsed stacks → d3-flamegraph | ✅ Complete |
| **Phase 15** | WebSocket real-time push | `WebSocketServerProtocolHandler`, pub/sub channels | ✅ Complete |
| **Phase 16** | Plugin/SPI extension mechanism | `ServiceLoader`, `URLClassLoader` isolation | ✅ Complete |
| **Phase 17** | Tunnel server for remote diagnostics → v1.0.0 GA | Outbound WS, reverse proxy, `CompletableFuture` correlation | ✅ Complete |

---

*Last updated: 2026-04-21*
