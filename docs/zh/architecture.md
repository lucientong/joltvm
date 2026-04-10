# JoltVM 架构文档

本文档深入介绍 JoltVM 的架构设计、模块划分和实现原理。快速入门和使用指南请参见 [README](../../README_zh.md)。

---

## 目录

- [系统概览](#系统概览)
- [模块架构](#模块架构)
  - [joltvm-agent](#joltvm-agent)
  - [joltvm-server](#joltvm-server)
  - [joltvm-cli](#joltvm-cli)
  - [joltvm-ui](#joltvm-ui)
- [核心机制](#核心机制)
  - [Java Instrumentation API](#java-instrumentation-api)
  - [Agent 加载模式](#agent-加载模式)
  - [JDK Attach API](#jdk-attach-api)
  - [字节码转换流水线](#字节码转换流水线)
  - [类热替换与回滚](#类热替换与回滚)
- [数据流](#数据流)
- [线程模型](#线程模型)
- [安全模型](#安全模型)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [分阶段开发计划](#分阶段开发计划)

---

## 系统概览

JoltVM 是一个 JVM 在线诊断与热修复框架。它以 Java Agent 的形式运行在目标 JVM 进程**内部**，通过浏览器端 Web IDE 提供运行时自省和字节码修改能力。

```
                         ┌─── 开发者浏览器 ──────────┐
                         │                           │
                         │   joltvm-ui (Web IDE)     │
                         │   ┌───────────────────┐   │
                         │   │  Monaco Editor    │   │
                         │   │  火焰图           │   │
                         │   │  类/方法树        │   │
                         │   │  审计面板         │   │
                         │   └────────┬──────────┘   │
                         │            │ HTTP/WS      │
                         └────────────┼──────────────┘
                                      │
                         ┌────────────┼──────────────┐
                         │  目标 JVM 进程             │
                         │            │              │
                         │   ┌────────┴─────────┐    │
                         │   │  joltvm-server   │    │
                         │   │  (Netty HTTP/WS) │    │
                         │   └────────┬─────────┘    │
                         │            │ 内部 API     │
                         │   ┌────────┴─────────┐    │
                         │   │  joltvm-agent    │    │
                         │   │  ┌─────────────┐ │    │
                         │   │  │ Instrument  │ │    │
                         │   │  │ ation API   │ │    │
                         │   │  ├─────────────┤ │    │
                         │   │  │ 字节码引擎   │ │    │
                         │   │  ├─────────────┤ │    │
                         │   │  │ 性能分析器   │ │    │
                         │   │  ├─────────────┤ │    │
                         │   │  │ Spring      │ │    │
                         │   │  │ 感知        │ │    │
                         │   │  └─────────────┘ │    │
                         │   └──────────────────┘    │
                         │            │              │
                         │   ┌────────┴─────────┐    │
                         │   │  你的应用程序      │    │
                         │   └──────────────────┘    │
                         └───────────────────────────┘

                         ┌───────────────────────────┐
                         │  开发者终端                │
                         │                           │
                         │  joltvm-cli               │
                         │  (attach / list 命令)     │
                         └───────────────────────────┘
```

**核心设计原则**：JoltVM 完全加载到目标 JVM 的进程空间中，没有独立的守护进程。嵌入式 Web 服务器提供零安装的浏览器体验 —— 只需打开 `http://localhost:7758`。

---

## 模块架构

### joltvm-agent

核心模块，运行在目标 JVM 内部。它是唯一直接与 JVM 内部状态交互的模块。

#### 关键类

| 类名 | 职责 |
|------|------|
| `JoltVMAgent` | Agent 入口。实现 `premain()`（启动挂载）和 `agentmain()`（动态附着）。使用同步方法实现幂等初始化。 |
| `InstrumentationHolder` | 线程安全单例（`AtomicReference` + CAS），存储 `java.lang.instrument.Instrumentation` 实例。`get()` 方法在未初始化时快速失败抛异常。 |
| `AttachHelper` | 使用 `com.sun.tools.attach.VirtualMachine` 将 Agent JAR 动态加载到运行中的 JVM。验证 PID 格式、解析 Agent JAR 路径，并在 `finally` 中确保 detach。 |

#### 类图

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
           │ 由其加载
┌──────────┴──────────┐
│    AttachHelper     │
├─────────────────────┤
│ +attach(pid, args)  │
│ +listJvmProcesses() │
│ -getAgentJarPath()  │
└─────────────────────┘
```

#### Manifest 配置

Agent JAR 的 `MANIFEST.MF` 声明了两个入口：

```
Premain-Class: com.joltvm.agent.JoltVMAgent
Agent-Class: com.joltvm.agent.JoltVMAgent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
Can-Set-Native-Method-Prefix: true
```

### joltvm-server

基于 Netty 4.x 的嵌入式 HTTP 服务器，运行在目标 JVM 内部（与 Agent 共存）。为 Web IDE 前端和 CLI 工具暴露 REST API。

#### 关键类

| 类名 | 职责 |
|------|------|
| `JoltVMServer` | Netty HTTP 服务器生命周期管理（启停）。Boss group（1 线程）+ Worker group（2 线程）。默认端口 7758，可配置。使用 `AtomicBoolean` 实现幂等启停。 |
| `HttpRouter` | 路径模式匹配，支持 `{paramName}` 路径参数（通过正则编译）。路由按注册顺序匹配，首次匹配生效。 |
| `HttpDispatcherHandler` | Netty `SimpleChannelInboundHandler`，将请求通过 `HttpRouter` 分发到 `RouteHandler`。处理 CORS 预检（OPTIONS）和异常。 |
| `HttpResponseHelper` | 构建 JSON、文本和错误响应的工具类，自动添加 Content-Type 和 CORS 头。 |
| `ApiRoutes` | 在初始化时将所有 API 端点注册到路由器。 |
| `HealthHandler` | `GET /api/health` — 返回 JVM 状态、PID、运行时间和内存信息。 |
| `ClassListHandler` | `GET /api/classes` — 分页列出已加载的类，支持包名和搜索过滤。 |
| `ClassDetailHandler` | `GET /api/classes/{className}` — 类详情（字段、方法、修饰符）。 |
| `ClassSourceHandler` | `GET /api/classes/{className}/source` — 通过 CFR 将字节码反编译为 Java 源码。 |
| `DecompileService` | 从 ClassLoader 加载字节码，通过内存中的 `BytecodeClassFileSource` 传递给 CFR。 |

#### REST API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 健康检查，包含 JVM 信息 |
| GET | `/api/classes` | 列出已加载的类（分页、可过滤） |
| GET | `/api/classes/{className}` | 类详情（字段、方法、父类） |
| GET | `/api/classes/{className}/source` | 反编译的 Java 源码 |

**设计考虑**：
- 选择 Netty 是因为其极小的内存占用和零外部依赖
- 运行在独立线程池上，避免干扰应用程序
- Agent 通过反射（`Class.forName`）加载 Server，避免循环编译依赖
- WebSocket 支持计划用于实时日志流和实时方法追踪（Phase 6）
- API 端点遵循 RESTful 约定，统一在 `/api/` 路径下

### joltvm-cli

命令行工具，打包为 fat JAR（通过 Shadow 插件）。提供两个命令：

| 命令 | 说明 |
|------|------|
| `attach <pid> [agentArgs]` | 将 JoltVM Agent 附着到运行中的 JVM 进程 |
| `list` | 列出所有可见的 JVM 进程 |

**版本管理**：CLI 从 `version.properties` 资源文件读取版本号，该文件由 Gradle 的 `processResources` 任务在构建时生成，与 `gradle.properties` 中的版本保持同步。

### joltvm-ui

*（Phase 6 — 尚未实现）*

React + TypeScript 单页应用，提供浏览器端 IDE 体验。计划组件：

- **Monaco Editor** — 代码编辑，支持语法高亮和智能提示
- **d3-flame-graph** — 交互式火焰图可视化
- **类/方法树** — 层级类浏览器，支持搜索
- **审计面板** — 查看热修复历史和 diff

---

## 核心机制

### Java Instrumentation API

JoltVM 的基础是 `java.lang.instrument` API（Java 5 引入，后续版本持续增强）。关键能力：

```java
public interface Instrumentation {
    // 类转换（Phase 3）
    void redefineClasses(ClassDefinition... definitions);
    void addTransformer(ClassFileTransformer transformer, boolean canRetransform);
    void retransformClasses(Class<?>... classes);

    // 类检查（Phase 2）
    Class[] getAllLoadedClasses();
    Class[] getInitiatedClasses(ClassLoader loader);
    long getObjectSize(Object objectToSize);

    // 能力查询
    boolean isRedefineClassesSupported();
    boolean isRetransformClassesSupported();
    boolean isNativeMethodPrefixSupported();
}
```

JoltVM 使用基于 CAS 的单例模式将 `Instrumentation` 实例存储在 `InstrumentationHolder` 中，无锁保证线程安全：

```java
private static final AtomicReference<Instrumentation> INSTANCE = new AtomicReference<>();

public static void set(Instrumentation inst) {
    INSTANCE.compareAndSet(null, inst);  // 只有第一次调用会成功
}
```

### Agent 加载模式

JoltVM 支持两种标准的 Java Agent 加载模式：

#### 1. 启动时挂载（`premain`）

```bash
java -javaagent:joltvm-agent.jar=port=7758 -jar app.jar
```

```
JVM 启动
    │
    ├── 加载 bootstrap 类
    ├── 加载 joltvm-agent.jar
    ├── 调用 JoltVMAgent.premain(args, inst)  ◄── JoltVM 在此初始化
    ├── 加载应用类
    └── 调用 Application.main()
```

**优势**：Agent 在所有应用类之前加载，可以从一开始就对所有类进行 instrumentation。

#### 2. 动态附着（`agentmain`）

```bash
java -jar joltvm-cli.jar attach <pid>
```

```
运行中的 JVM (pid=12345)            joltvm-cli
    │                                    │
    │    ◄───── VirtualMachine.attach ───┤
    │    ◄───── vm.loadAgent(jar, args) ─┤
    │                                    │
    ├── JVM 加载 joltvm-agent.jar        │
    ├── 调用 JoltVMAgent.agentmain()     │
    │                                    │
    │    ─────── vm.detach ─────────────►│
    │                                    │
    ▼ (Agent 继续在进程内运行)            ▼ (CLI 退出)
```

**优势**：无需重启应用。在发现问题时直接附着到运行中的 JVM。

### JDK Attach API

`com.sun.tools.attach` API 实现了与运行中 JVM 的跨进程通信：

```java
// AttachHelper.attach() 实现流程：
public static void attach(String pid, String agentArgs) throws Exception {
    // 1. 验证 PID（必须为正整数）
    Long.parseLong(pid.trim());

    // 2. 从 CodeSource 解析 Agent JAR 路径
    String jarPath = getAgentJarPath();

    // 3. 附着 → 加载 → 分离
    VirtualMachine vm = VirtualMachine.attach(pid);
    try {
        vm.loadAgent(jarPath, agentArgs);
    } finally {
        vm.detach();  // 始终 detach 以释放资源
    }
}
```

**平台差异**：
- Linux：Attach API 使用 Unix 域套接字（`/tmp/.java_pid<pid>`）
- macOS：使用类似机制，但可能需要提升权限运行
- Windows：使用命名管道

### 字节码转换流水线

*（Phase 3 — 仅设计）*

```
源代码（在 Web IDE 中编辑）
        │
        ▼
┌─────────────────────┐
│ Java Compiler API   │  javax.tools.JavaCompiler
│ （内存中编译）       │  无需临时文件
└────────┬────────────┘
         │ byte[]
         ▼
┌─────────────────────┐
│ 字节码验证器         │  验证类结构，
│                     │  检查兼容性
└────────┬────────────┘
         │ 验证后的 byte[]
         ▼
┌─────────────────────┐
│ 备份原始字节码       │  保存原始字节码
│                     │  用于回滚
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│ Instrumentation     │  inst.redefineClasses(
│ .redefineClasses()  │    new ClassDefinition(clazz, bytes))
└─────────────────────┘
```

**回滚机制**：每次热替换前，JoltVM 将原始字节码保存在 `ConcurrentHashMap<String, byte[]>` 中。回滚时只需重新应用保存的字节码。

### 类热替换与回滚

*（Phase 3 — 仅设计）*

JVM 通过 `redefineClasses()` 进行热替换有严格的约束：

| 允许的操作 | 不允许的操作 |
|-----------|-------------|
| 修改方法体 | 增删方法 |
| 修改常量池条目 | 增删字段 |
| 增删 private 方法（Java 9+） | 改变类继承关系 |
| | 修改方法签名 |

JoltVM 会在应用修改前进行验证，当违反约束时提供清晰的错误信息。

---

## 数据流

### 附着流程（Phase 1 — 已实现）

```
┌──────────┐    ┌───────────┐    ┌─────────────┐    ┌──────────┐
│ 开发者    │───►│ joltvm-cli│───►│ Attach API  │───►│ 目标     │
│          │    │           │    │(Unix 套接字) │    │ JVM      │
│ attach   │    │ 验证 PID  │    │ loadAgent() │    │ agentmain│
│ <pid>    │    │           │    │             │    │ 被调用   │
└──────────┘    └───────────┘    └─────────────┘    └──────────┘
```

### 诊断流程（Phase 2 — 已实现）

```
┌──────────┐    ┌───────────┐    ┌─────────────┐    ┌──────────┐
│ 浏览器   │◄──►│  Netty    │◄──►│ joltvm-agent│───►│ 目标     │
│ Web IDE  │    │  Server   │    │ 字节码引擎   │    │ JVM      │
│          │    │  :7758    │    │ 性能分析器   │    │ 类       │
│ Monaco   │    │ REST API  │    │ Spring 上下文│    │          │
│ 火焰图   │    │ WebSocket │    │             │    │          │
└──────────┘    └───────────┘    └─────────────┘    └──────────┘
```

---

## 线程模型

JoltVM 的设计目标是最小化对目标应用的影响：

| 线程 / 线程池 | 用途 | 影响 |
|---------------|------|------|
| Agent 初始化线程 | `premain`/`agentmain` 时的一次性初始化 | 极小 — 只运行一次 |
| Netty boss group（1 线程） | 接受传入的 HTTP/WS 连接 | 可忽略 |
| Netty worker group（2 线程） | 处理 HTTP 请求和 WebSocket 帧 | 低 — 仅在诊断时活跃 |
| 性能采样线程 | 定期栈采样用于火焰图 | 可配置采样间隔 |
| 应用线程 | JoltVM 不修改 | 不主动性能分析时零影响 |

---

## 安全模型

*（Phase 5+ — 仅设计）*

```
┌─────────────────────────────────┐
│         安全层                   │
├─────────────────────────────────┤
│ 身份认证                        │
│  ├── 令牌认证（内置）            │
│  └── SSO/LDAP（企业集成）       │
├─────────────────────────────────┤
│ 权限控制 (RBAC)                 │
│  ├── Viewer: 只读访问           │
│  ├── Operator: 热修复 + 追踪   │
│  └── Admin: 完全访问            │
├─────────────────────────────────┤
│ 审计追踪                        │
│  ├── 每次热修复 → diff 日志     │
│  ├── 时间戳 + 操作人 + 原因    │
│  └── 不可篡改 + 可导出          │
├─────────────────────────────────┤
│ 审批工作流（可选）               │
│  └── 热修复需经管理员审批       │
│      方可生效                   │
└─────────────────────────────────┘
```

---

## 技术栈

| 组件 | 技术 | 选型理由 |
|------|------|---------|
| Agent 核心 | `java.lang.instrument` | JVM 标准 API，零依赖 |
| 动态附着 | `com.sun.tools.attach` | JDK 内置，跨平台 |
| 字节码操作 | Byte Buddy | 高层 API，成熟稳定 |
| 反编译 | CFR | 业界最佳的 Java 反编译器 |
| HTTP 服务器 | Netty 4.x | 轻量级、可嵌入、异步 |
| 序列化 | Gson | 简单、快速，Agent 上下文无反射问题 |
| 日志 | JUL (`java.util.logging`) | 零依赖 — 对 Agent 启动阶段至关重要 |
| 构建 | Gradle + Shadow | 多模块构建 + fat JAR 打包 |
| CI/CD | GitHub Actions | 自动化测试 + Maven Central 发布 |
| 前端 | React + TypeScript | 现代化、类型安全的 UI 开发 |
| 代码编辑器 | Monaco Editor | VS Code 的编辑器组件 |
| 火焰图 | d3-flame-graph | 交互式、可缩放的火焰图 |

---

## 项目结构

```
joltvm/
├── build.gradle.kts               # 根构建：插件、maven-publish、signing
├── settings.gradle.kts             # 多模块项目设置
├── gradle.properties               # 版本号、项目元数据
├── gradlew / gradlew.bat           # Gradle Wrapper
│
├── joltvm-agent/                   # ── Java Agent 核心 ──
│   ├── build.gradle.kts            # Shadow JAR + MANIFEST.MF 配置
│   └── src/
│       ├── main/java/com/joltvm/agent/
│       │   ├── JoltVMAgent.java        # premain() / agentmain() 入口
│       │   ├── InstrumentationHolder.java  # 线程安全 Instrumentation 单例
│       │   ├── AttachHelper.java       # Attach API 封装
│       │   └── package-info.java       # 包级别 Javadoc
│       └── test/java/com/joltvm/agent/
│           ├── JoltVMAgentTest.java        # 11 个测试
│           ├── InstrumentationHolderTest.java
│           └── AttachHelperTest.java       # 15 个测试（PID 验证等）
│
├── joltvm-server/                  # ── 嵌入式 Web 服务器（Phase 2）──
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/com/joltvm/server/
│       │   ├── JoltVMServer.java           # Netty HTTP 服务器生命周期
│       │   ├── HttpRouter.java             # 路径模式匹配 + 参数
│       │   ├── HttpDispatcherHandler.java  # 请求分发 + CORS
│       │   ├── HttpResponseHelper.java     # JSON/文本响应构建器
│       │   ├── RouteHandler.java           # 函数式路由处理器接口
│       │   ├── ApiRoutes.java              # API 路由注册
│       │   ├── handler/
│       │   │   ├── HealthHandler.java      # GET /api/health
│       │   │   ├── ClassListHandler.java   # GET /api/classes
│       │   │   ├── ClassDetailHandler.java # GET /api/classes/{className}
│       │   │   ├── ClassSourceHandler.java # GET /api/classes/{className}/source
│       │   │   └── ClassFinder.java        # 已加载类查找工具
│       │   ├── decompile/
│       │   │   ├── DecompileService.java   # CFR 反编译服务
│       │   │   └── DecompileException.java # 反编译异常
│       │   └── package-info.java
│       └── test/java/com/joltvm/server/
│           ├── HttpRouterTest.java
│           ├── HttpResponseHelperTest.java
│           ├── JoltVMServerTest.java
│           ├── ApiRoutesTest.java
│           ├── handler/
│           │   ├── HealthHandlerTest.java
│           │   ├── ClassListHandlerTest.java
│           │   ├── ClassDetailHandlerTest.java
│           │   └── ClassSourceHandlerTest.java
│           └── decompile/
│               └── DecompileServiceTest.java
│
├── joltvm-cli/                     # ── 命令行工具 ──
│   ├── build.gradle.kts            # Shadow JAR + processResources 版本注入
│   └── src/
│       ├── main/java/com/joltvm/cli/
│       │   ├── JoltVMCli.java          # CLI 入口（attach、list）
│       │   └── package-info.java
│       └── main/resources/
│           └── version.properties      # 构建时生成
│
├── docs/
│   ├── en/architecture.md          # 英文架构文档
│   └── zh/architecture.md          # 本文档
│
├── .github/workflows/
│   ├── ci.yml                      # CI：每次 push/PR 编译 + 测试
│   └── release.yml                 # Release：Maven Central + GitHub Releases
│
├── README.md                       # 英文 README
├── README_zh.md                    # 中文 README
├── LICENSE                         # Apache License 2.0
└── CHANGELOG.md                    # 版本变更日志
```

---

## 分阶段开发计划

| 阶段 | 范围 | 关键技术 | 状态 |
|------|------|---------|------|
| **Phase 1** | Agent 骨架 + Attach API + CLI | `java.lang.instrument`、`com.sun.tools.attach` | ✅ 已完成 |
| **Phase 2** | Netty Web Server + 基础 API（列出类、反编译） | Netty、CFR | ✅ 已完成 |
| **Phase 3** | 热替换 + 回滚 | `redefineClasses()`、`javax.tools.JavaCompiler` | 📋 计划中 |
| **Phase 4** | 方法追踪 + 火焰图数据 | Byte Buddy Advice、栈采样 | 📋 计划中 |
| **Phase 5** | Spring Boot 感知 | Spring `ApplicationContext`、`RequestMappingHandlerMapping` | 📋 计划中 |
| **Phase 6** | Web UI | React、Monaco Editor、d3-flame-graph | 📋 计划中 |

---

*最后更新：2026-04-10*
