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
  - [方法追踪与火焰图](#方法追踪与火焰图)
  - [Spring Boot 感知](#spring-boot-感知)
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
| `APIRoutes` | 在初始化时将所有 API 端点注册到路由器。 |
| `HealthHandler` | `GET /api/health` — 返回 JVM 状态、PID、运行时间和内存信息。 |
| `ClassListHandler` | `GET /api/classes` — 分页列出已加载的类，支持包名和搜索过滤。 |
| `ClassDetailHandler` | `GET /api/classes/{className}` — 类详情（字段、方法、修饰符）。 |
| `ClassSourceHandler` | `GET /api/classes/{className}/source` — 通过 CFR 将字节码反编译为 Java 源码。 |
| `DecompileService` | 从 ClassLoader 加载字节码，通过内存中的 `BytecodeClassFileSource` 传递给 CFR。 |
| `MethodTraceService` | 核心服务，编排方法追踪（Byte Buddy Advice 注入）和栈采样。通过 `AtomicBoolean` 管理追踪/采样生命周期。 |
| `FlameGraphCollector` | 中央数据收集器，收集追踪记录（`CopyOnWriteArrayList`，最多 500 条）和栈采样（最多 1000 条）。构建 d3-flame-graph 兼容的树结构。 |
| `FlameGraphNode` | 火焰图树节点。提供 `getOrCreateChild()` 用于构建树，`toMap()` 用于 d3-flame-graph JSON 序列化。 |
| `TraceRecord` | 不可变 Java record，捕获单次方法调用：类名、方法名、参数、返回值、异常、耗时、线程信息、调用深度。 |
| `TracingException` | 追踪包的标准 `RuntimeException` 子类。 |
| `TraceHandler` | `POST /api/trace/start` 和 `POST /api/trace/stop` — 控制方法追踪和栈采样的生命周期。 |
| `TraceListHandler` | `GET /api/trace/records` — 返回方法追踪记录，支持 limit 参数。 |
| `TraceFlameGraphHandler` | `GET /api/trace/flamegraph` — 返回 d3-flame-graph 兼容的 JSON 数据。 |
| `TraceStatusHandler` | `GET /api/trace/status` — 返回当前追踪/采样状态和统计信息。 |
| `SpringContextService` | 基于反射的 Spring ApplicationContext 发现。列出 Bean、解析 `@RequestMapping`、分析 `@Controller → @Service → @Repository` 依赖链。零编译期 Spring 依赖。 |
| `BeanListHandler` | `GET /api/spring/beans` — 分页列出 Spring Bean，支持包名/搜索/stereotype 过滤。 |
| `BeanDetailHandler` | `GET /api/spring/beans/{beanName}` — Bean 详情（方法、注解、接口、映射）。 |
| `RequestMappingHandler` | `GET /api/spring/mappings` — URL → 方法请求映射，支持 HTTP 方法和搜索过滤。 |
| `DependencyChainHandler` | `GET /api/spring/dependencies/{beanName}` — 单个 Bean 的递归依赖注入链。 |
| `DependencyGraphHandler` | `GET /api/spring/dependencies` — 所有 stereotype Bean 的完整依赖图。 |

#### REST API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 健康检查，包含 JVM 信息 |
| GET | `/api/classes` | 列出已加载的类（分页、可过滤） |
| GET | `/api/classes/{className}` | 类详情（字段、方法、父类） |
| GET | `/api/classes/{className}/source` | 反编译的 Java 源码 |
| POST | `/api/compile` | 内存中编译 Java 源码 |
| POST | `/api/hotswap` | 编译 + 热替换类 |
| POST | `/api/rollback` | 回滚已热替换的类 |
| GET | `/api/hotswap/history` | 热替换操作历史 |
| POST | `/api/trace/start` | 启动方法追踪或栈采样 |
| POST | `/api/trace/stop` | 停止追踪/采样（按类型或全部） |
| GET | `/api/trace/records` | 方法追踪记录（支持 limit 参数） |
| GET | `/api/trace/flamegraph` | 火焰图数据（d3-flame-graph 格式） |
| GET | `/api/trace/status` | 当前追踪/采样状态 |
| GET | `/api/spring/beans` | 列出所有 Spring Bean（分页、可过滤） |
| GET | `/api/spring/beans/{beanName}` | Spring Bean 详情（方法、注解、接口） |
| GET | `/api/spring/mappings` | URL → 方法请求映射 |
| GET | `/api/spring/dependencies` | 完整依赖图（Controller→Service→Repository） |
| GET | `/api/spring/dependencies/{beanName}` | 单个 Bean 的依赖链 |

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

*（Phase 3 — 已实现）*

```
源代码（通过 REST API 提交）
        │
        ▼
┌─────────────────────┐
│ InMemoryCompiler    │  javax.tools.JavaCompiler
│ （内存中编译）       │  无需临时文件
└────────┬────────────┘
         │ byte[]
         ▼
┌─────────────────────┐
│ BytecodeBackupService│  保存原始字节码
│ (ConcurrentHashMap)  │  用于回滚 (putIfAbsent)
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
│ HotSwapRecord       │  审计追踪：操作、状态、
│ (History)           │  时间戳、消息
└─────────────────────┘
```

**内存中编译**：`InMemoryCompiler` 使用自定义的 `ForwardingJavaFileManager` 配合 `InMemorySourceFile` 和 `InMemoryClassFile` 完全在内存中编译 Java 源码。字节码通过自定义的 `ByteArrayOutputStream.close()` 回调捕获 —— 无需创建临时文件。

**回滚机制**：每次热替换前，`BytecodeBackupService` 将原始字节码保存在 `ConcurrentHashMap<String, byte[]>` 中，使用 `putIfAbsent`（首次备份永不覆盖）。回滚时取回备份并通过 `redefineClasses()` 重新应用。

**审计追踪**：每次热替换和回滚操作都记录为一个 `HotSwapRecord`，包含 id、className、action (HOTSWAP/ROLLBACK)、status (SUCCESS/FAILED)、message 和 timestamp。历史记录存储在 `CopyOnWriteArrayList` 中，最多保留 200 条。

### 类热替换与回滚

*（Phase 3 — 已实现）*

JVM 通过 `redefineClasses()` 进行热替换有严格的约束：

| 允许的操作 | 不允许的操作 |
|-----------|-------------|
| 修改方法体 | 增删方法 |
| 修改常量池条目 | 增删字段 |
| 增删 private 方法（Java 9+） | 改变类继承关系 |
| | 修改方法签名 |

JoltVM 会在应用修改前进行验证，当违反约束时提供清晰的错误信息。`HotSwapService` 处理以下环节：
- **能力检查**：通过 `isRedefineClassesSupported()` 验证 JVM 支持热替换
- **类查找**：通过 `Instrumentation.getAllLoadedClasses()` 在已加载类中查找目标类
- **可修改性检查**：通过 `isModifiableClass()` 确保类可被修改
- **自动备份**：在任何修改前自动备份原始字节码
- **异常处理**：捕获 `UnsupportedOperationException`、`ClassFormatError`、`UnmodifiableClassException` 并提供描述性消息

### 方法追踪与火焰图

*（Phase 4 — 已实现）*

JoltVM 提供两种互补的性能分析机制：**方法追踪**（Byte Buddy Advice 注入）和**栈采样**（定期 `Thread.getAllStackTraces()`）。

#### 方法追踪（Byte Buddy Advice）

```
POST /api/trace/start  { "type": "trace", "className": "com.example.MyService", "methodName": "process" }
        │
        ▼
┌─────────────────────────┐
│ MethodTraceService      │  构建 AgentBuilder，使用
│ .startTrace()           │  RETRANSFORMATION 策略
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
│ MethodTraceAdvice       │  捕获：参数、返回值、
│ （静态 Advice 类）       │  异常、耗时（纳秒）、
│                         │  线程信息、调用深度
└────────┬────────────────┘
         │ TraceRecord
         ▼
┌─────────────────────────┐
│ FlameGraphCollector     │  CopyOnWriteArrayList<TraceRecord>
│ .addRecord()            │  （最多 500 条，FIFO）
└─────────────────────────┘
```

**关键设计决策**：
- **`RETRANSFORMATION` 策略**：使用 `AgentBuilder.Default(new ByteBuddy().with(TypeValidation.DISABLED))` 配合 `disableClassFormatChanges()`，实现安全的运行时 instrumentation 且可撤销
- **静态 `activeCollector` 字段**：Byte Buddy Advice 类必须是静态的；通过 `volatile` 静态引用将 Advice 回调桥接到服务的 `FlameGraphCollector` 实例
- **`Assigner.Typing.DYNAMIC`**：`@Advice.Return` 注解需要此设置来处理所有被 instrument 方法的动态返回类型
- **`ConcurrentHashMap.newKeySet()`**：跟踪当前正在被追踪的类，确保停止/清理操作的幂等性

#### 栈采样

```
POST /api/trace/start  { "type": "sample", "interval": 10 }
        │
        ▼
┌─────────────────────────┐
│ MethodTraceService      │  启动守护线程：
│ .startSampling()        │  "joltvm-stack-sampler"
└────────┬────────────────┘
         │ 以 interval 毫秒间隔调用 Thread.getAllStackTraces()
         ▼
┌─────────────────────────┐
│ FlameGraphCollector     │  CopyOnWriteArrayList<StackTraceElement[]>
│ .addStackSample()       │  （最多 1000 条采样，FIFO）
└────────┬────────────────┘
         │ buildFlameGraphFromSamples()
         ▼
┌─────────────────────────┐
│ FlameGraphNode (树)     │  Root → 包 → 类 → 方法
│ .toMap()                │  输出：{ name, value, children }
└─────────────────────────┘  兼容 d3-flame-graph
```

**采样过滤**：守护线程和名称以 `joltvm-` 开头的线程会被排除在采样之外，避免 JoltVM 自身开销污染火焰图。

#### 火焰图数据模型

`FlameGraphNode` 构建与 [d3-flame-graph](https://github.com/nicedoc/d3-flame-graph) 兼容的树结构：

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

两种数据源供给火焰图：
1. **追踪记录**：来自 Byte Buddy Advice 的方法级调用数据（当栈采样不可用时作为回退）
2. **栈采样**：来自定期 `Thread.getAllStackTraces()` 调用的 CPU 分析数据（可用时优先使用，因为它捕获完整的调用栈）

### Spring Boot 感知

*（Phase 5 — 已实现）*

JoltVM 能够内省 Spring Boot 应用，**无需任何编译期 Spring 依赖**。所有与 Spring 类的交互都通过反射完成，兼容 Spring Boot 2.x 和 3.x。

#### 架构

```
运行 Spring Boot 的目标 JVM
        │
        ▼
┌─────────────────────────┐
│ SpringContextService    │  通过 Thread.currentThread()
│ .detectSpringContext()   │  .getContextClassLoader() + 反射
└────────┬────────────────┘  发现 ApplicationContext
         │
         ├── listBeans()          → GET /api/spring/beans
         ├── getBeanDetail()      → GET /api/spring/beans/{beanName}
         ├── getRequestMappings() → GET /api/spring/mappings
         ├── getDependencyChain() → GET /api/spring/dependencies/{beanName}
         └── getDependencyGraph() → GET /api/spring/dependencies
```

#### 关键设计决策

- **零 Spring 编译期依赖**：所有 Spring 类（`ApplicationContext`、`@RequestMapping`、`@Autowired` 等）都通过 `Class.forName()` 和 `Method.invoke()` 访问。确保 Agent JAR 不会引入 Spring Boot 作为传递依赖。
- **优雅降级**：当附着到非 Spring JVM 时，所有端点返回 HTTP 503 `{"springDetected": false}`。不抛出错误或异常。
- **Stereotype 检测**：识别 `@Controller`、`@RestController`、`@Service`、`@Repository`、`@Component` 和 `@Configuration` 注解，用于 Bean 分类。
- **依赖链分析**：扫描字段和构造函数参数上的 `@Autowired`、`@Inject`（`jakarta.inject` 和 `javax.inject`）、`@Resource` 注解。递归构建依赖树，支持循环依赖检测。
- **请求映射解析**：支持 `@RequestMapping`、`@GetMapping`、`@PostMapping`、`@PutMapping`、`@DeleteMapping`、`@PatchMapping`。提取 URL 模式和 HTTP 方法。

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
├── joltvm-server/                  # ── 嵌入式 Web 服务器（Phase 2–5）──
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/com/joltvm/server/
│       │   ├── JoltVMServer.java           # Netty HTTP 服务器生命周期
│       │   ├── HttpRouter.java             # 路径模式匹配 + 参数
│       │   ├── HttpDispatcherHandler.java  # 请求分发 + CORS
│       │   ├── HttpResponseHelper.java     # JSON/文本响应构建器
│       │   ├── RouteHandler.java           # 函数式路由处理器接口
│       │   ├── APIRoutes.java              # API 路由注册（18 个路由）
│       │   ├── handler/
│       │   │   ├── HealthHandler.java      # GET /api/health
│       │   │   ├── ClassListHandler.java   # GET /api/classes
│       │   │   ├── ClassDetailHandler.java # GET /api/classes/{className}
│       │   │   ├── ClassSourceHandler.java # GET /api/classes/{className}/source
│       │   │   ├── ClassFinder.java        # 已加载类查找工具
│       │   │   ├── CompileHandler.java     # POST /api/compile
│       │   │   ├── HotSwapHandler.java     # POST /api/hotswap
│       │   │   ├── RollbackHandler.java    # POST /api/rollback
│       │   │   ├── HotSwapHistoryHandler.java # GET /api/hotswap/history
│       │   │   ├── TraceHandler.java       # POST /api/trace/start, /api/trace/stop
│       │   │   ├── TraceListHandler.java   # GET /api/trace/records
│       │   │   ├── TraceFlameGraphHandler.java # GET /api/trace/flamegraph
│       │   │   ├── TraceStatusHandler.java # GET /api/trace/status
│       │   │   ├── BeanListHandler.java    # GET /api/spring/beans
│       │   │   ├── BeanDetailHandler.java  # GET /api/spring/beans/{beanName}
│       │   │   ├── RequestMappingHandler.java # GET /api/spring/mappings
│       │   │   ├── DependencyGraphHandler.java # GET /api/spring/dependencies
│       │   │   └── DependencyChainHandler.java # GET /api/spring/dependencies/{beanName}
│       │   ├── compile/
│       │   │   ├── InMemoryCompiler.java   # javax.tools 内存中编译
│       │   │   ├── CompileResult.java      # 编译结果 record
│       │   │   └── CompileException.java   # 编译异常
│       │   ├── decompile/
│       │   │   ├── DecompileService.java   # CFR 反编译服务
│       │   │   └── DecompileException.java # 反编译异常
│       │   ├── hotswap/
│       │   │   ├── HotSwapService.java     # 热替换编排 + 回滚
│       │   │   ├── BytecodeBackupService.java # 原始字节码备份存储
│       │   │   ├── HotSwapRecord.java      # 审计追踪记录
│       │   │   └── HotSwapException.java   # 热替换异常
│       │   ├── tracing/
│       │   │   ├── MethodTraceService.java # 方法追踪 + 栈采样编排
│       │   │   ├── FlameGraphCollector.java # 追踪记录 + 栈采样收集器
│       │   │   ├── FlameGraphNode.java     # 火焰图树节点（d3 兼容）
│       │   │   ├── TraceRecord.java        # 单次方法调用记录
│       │   │   └── TracingException.java   # 追踪异常
│       │   ├── spring/
│       │   │   ├── SpringContextService.java # Spring 上下文发现 + Bean 分析
│       │   │   └── package-info.java
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
│           │   ├── TraceStatusHandlerTest.java
│           │   ├── BeanListHandlerTest.java
│           │   ├── BeanDetailHandlerTest.java
│           │   ├── RequestMappingHandlerTest.java
│           │   ├── DependencyChainHandlerTest.java
│           │   └── DependencyGraphHandlerTest.java
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
| **Phase 3** | 热替换 + 回滚 | `redefineClasses()`、`javax.tools.JavaCompiler` | ✅ 已完成 |
| **Phase 4** | 方法追踪 + 火焰图数据 | Byte Buddy Advice、栈采样 | ✅ 已完成 |
| **Phase 5** | Spring Boot 感知 | Spring `ApplicationContext`、`RequestMappingHandlerMapping` | ✅ 已完成 |
| **Phase 6** | Web UI | React、Monaco Editor、d3-flame-graph | 📋 计划中 |

---

*最后更新：2026-04-11*
