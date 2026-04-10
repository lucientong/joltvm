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
- [Core Mechanisms](#core-mechanisms)
  - [Java Instrumentation API](#java-instrumentation-api)
  - [Agent Loading Modes](#agent-loading-modes)
  - [JDK Attach API](#jdk-attach-api)
  - [Bytecode Transformation Pipeline](#bytecode-transformation-pipeline)
  - [Class Hot-Swap & Rollback](#class-hot-swap--rollback)
  - [Method Tracing & Flame Graph](#method-tracing--flame-graph)
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
| `HttpDispatcherHandler` | Netty `SimpleChannelInboundHandler` that dispatches requests to `RouteHandler` via `HttpRouter`. Handles CORS preflight (OPTIONS) and error handling. |
| `HttpResponseHelper` | Utility class for building JSON, text, and error responses with proper Content-Type and CORS headers. |
| `APIRoutes` | Registers all API endpoints on the router during initialization. |
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

**Design considerations**:
- Netty is chosen for its minimal footprint and zero external dependencies
- Runs on a dedicated thread pool to avoid interfering with the application
- Agent loads server via reflection (`Class.forName`) to avoid circular compile-time dependency
- WebSocket support planned for real-time log streaming and live method tracing (Phase 6)
- API endpoints follow a RESTful convention under `/api/`

### joltvm-cli

A command-line tool packaged as a fat JAR (via Shadow plugin). It provides two commands:

| Command | Description |
|---------|-------------|
| `attach <pid> [agentArgs]` | Attach the JoltVM agent to a running JVM process |
| `list` | List all visible JVM processes |

**Version management**: The CLI reads its version from a `version.properties` resource file that is generated at build time by Gradle's `processResources` task, keeping it in sync with `gradle.properties`.

### joltvm-ui

*(Phase 6 — not yet implemented)*

A React + TypeScript single-page application that provides a browser-based IDE experience. Planned components:

- **Monaco Editor** — Code editing with syntax highlighting and IntelliSense
- **d3-flame-graph** — Interactive flame graph visualization
- **Class/Method Tree** — Hierarchical class browser with search
- **Audit Dashboard** — View hot-fix history with diffs

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

**Audit trail**: Every hot-swap and rollback operation is recorded as a `HotSwapRecord` with id, className, action (HOTSWAP/ROLLBACK), status (SUCCESS/FAILED), message, and timestamp. History is stored in a `CopyOnWriteArrayList` with a maximum of 200 entries.

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
| Application threads | Not modified by JoltVM | Zero impact when not actively profiling |

---

## Security Model

*(Phase 5+ — design only)*

```
┌─────────────────────────────────┐
│         Security Layer          │
├─────────────────────────────────┤
│ Authentication                  │
│  ├── Token-based (built-in)     │
│  └── SSO/LDAP (enterprise)     │
├─────────────────────────────────┤
│ Authorization (RBAC)            │
│  ├── Viewer: read-only access  │
│  ├── Operator: hot-fix + trace │
│  └── Admin: full access        │
├─────────────────────────────────┤
│ Audit Trail                     │
│  ├── Every hot-fix → diff log  │
│  ├── Timestamp + User + Reason │
│  └── Immutable + Exportable    │
├─────────────────────────────────┤
│ Approval Workflow (optional)    │
│  └── Hot-fix requires approval │
│      from admin before applying│
└─────────────────────────────────┘
```

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
| Frontend | React + TypeScript | Modern, type-safe UI development |
| Code editor | Monaco Editor | VS Code's editor component |
| Flame graph | d3-flame-graph | Interactive, zoomable flame graphs |

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
├── joltvm-server/                  # ── Embedded Web Server (Phase 2–4) ──
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/com/joltvm/server/
│       │   ├── JoltVMServer.java           # Netty HTTP server lifecycle
│       │   ├── HttpRouter.java             # Path pattern matching + params
│       │   ├── HttpDispatcherHandler.java  # Request dispatch + CORS
│       │   ├── HttpResponseHelper.java     # JSON/text response builder
│       │   ├── RouteHandler.java           # Functional route handler interface
│       │   ├── APIRoutes.java              # API route registration
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
│       │   │   └── TraceStatusHandler.java # GET /api/trace/status
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
│       │   └── package-info.java
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
│           │   └── TraceStatusHandlerTest.java
│           ├── compile/
│           │   └── InMemoryCompilerTest.java
│           ├── decompile/
│           │   └── DecompileServiceTest.java
│           ├── hotswap/
│           │   ├── HotSwapServiceTest.java
│           │   ├── BytecodeBackupServiceTest.java
│           │   └── HotSwapRecordTest.java
│           └── tracing/
│               ├── MethodTraceServiceTest.java
│               ├── FlameGraphCollectorTest.java
│               ├── FlameGraphNodeTest.java
│               └── TraceRecordTest.java
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
| **Phase 5** | Spring Boot awareness | Spring `ApplicationContext`, `RequestMappingHandlerMapping` | 📋 Planned |
| **Phase 6** | Web UI | React, Monaco Editor, d3-flame-graph | 📋 Planned |

---

*Last updated: 2026-04-11*
