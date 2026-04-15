# ⚡ JoltVM

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![CI](https://github.com/lucientong/joltvm/actions/workflows/ci.yml/badge.svg)](https://github.com/lucientong/joltvm/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/lucientong/joltvm/graph/badge.svg)](https://codecov.io/gh/lucientong/joltvm)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.lucientong/joltvm-agent)](https://central.sonatype.com/artifact/io.github.lucientong/joltvm-agent)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Build](https://img.shields.io/badge/build-Gradle-02303A.svg)](https://gradle.org/)

**如同一道闪电 — 精准、即时地诊断运行中的 JVM。**

JoltVM 是一个 JVM 在线诊断与热修复框架。通过 Java Agent 附着到目标 JVM，在浏览器端 Web IDE 中浏览和反编译类、一键热修复、可视化火焰图、方法调用链追踪，并内置安全审计与一键回滚。

[English](README.md) · [架构文档](docs/zh/architecture.md) · [Architecture](docs/en/architecture.md)

---

## ✨ 功能特性

> JoltVM 正在积极开发中。Phase 1（Agent 骨架 + Attach API）、Phase 2（Netty Web Server + REST API）、Phase 3（热替换 + 回滚）、Phase 4（方法追踪 + 火焰图）、Phase 5（Spring Boot 感知）、Phase 6（Web UI）和 Phase 7（安全审计）已完成。完整计划参见[路线图](#-路线图)。

### 🖥️ 浏览器端 Web IDE
不再需要记忆 50+ 条命令。可视化界面集成 Monaco Editor 代码编辑器、实时日志流、类和方法树导航。在线编辑代码并直接热修复。

### 🔧 一键热修复
在 Web IDE 中编辑代码 → 自动编译 → 通过 `Instrumentation.redefineClasses()` 即时替换字节码。无需手动 `jad` → `mc` → `retransform` 流程。内置回滚，保留原始字节码。

### 🔥 交互式火焰图
浏览器内可缩放、可搜索的火焰图（d3-flame-graph）。支持 CPU 时间与 Wall 时间视图切换。内存分配视图和前后对比功能为 **计划中**。

### 🌱 Spring Boot 感知
列出所有 Spring Bean，支持过滤和分页。解析 `@RequestMapping` 端点，展示 URL → 方法映射。分析 `@Controller → @Service → @Repository` 依赖注入调用链，支持循环依赖检测。零编译期 Spring 依赖 —— 通过反射实现，兼容 Spring Boot 2.x/3.x。

### 🔒 安全审计
基于 HMAC-SHA256 的令牌认证，三级 RBAC 角色控制（Viewer / Operator / Admin）。认证中间件对每个 API 请求进行权限检查。每次热修复生成包含时间戳、操作人、原因和差异的审计条目。不可篡改的审计日志，支持 JSON Lines 和 CSV 格式导出。安全功能可关闭以便开发使用。

---

## 🚀 快速开始

### 环境要求

- **JDK 17+**（完整 JDK，非 JRE — Attach API 和动态编译需要）
- **Gradle 8.x**（或使用项目自带的 Gradle Wrapper）

### 从源码构建

```bash
git clone https://github.com/lucientong/joltvm.git
cd joltvm
./gradlew build
```

### 附着到运行中的 JVM

```bash
# 列出所有运行中的 JVM 进程
java -jar joltvm-cli/build/libs/joltvm-cli-*-all.jar list

# 附着 JoltVM Agent 到目标 PID
java -jar joltvm-cli/build/libs/joltvm-cli-*-all.jar attach <pid>

# 或使用 -javaagent 启动时挂载
java -javaagent:joltvm-agent/build/libs/joltvm-agent-*-all.jar -jar your-app.jar

# 带自定义配置（逗号分隔的 key=value 参数）
java -javaagent:joltvm-agent/build/libs/joltvm-agent-*-all.jar=port=7758,security=true,adminPassword=MySecret,auditFile=/var/log/joltvm-audit.jsonl -jar your-app.jar
```

### Agent 参数说明

| 参数名          | 默认值                              | 说明                                                             |
|-----------------|-------------------------------------|------------------------------------------------------------------|
| `port`          | `7758`                              | 内嵌 Web 服务器监听端口                                         |
| `security`      | `false`                             | 是否启用认证（`true` / `false`）                               |
| `adminPassword` | `joltvm`                            | 初始管理员密码（以加盐 SHA-256 哈希存储）。使用默认密码时，首次登录后将提示修改。 |
| `auditFile`     | `$TMPDIR/joltvm-audit.jsonl`        | 审计日志持久化路径（JSON Lines 格式）                          |
| `tlsCert`       | *(无)*                              | TLS 证书路径（PEM 格式）——设置后自动启用 HTTPS                |
| `tlsKey`        | *(无)*                              | TLS 私钥路径（PEM 格式）——设置 `tlsCert` 时必填               |

---

## 🏗️ 架构

JoltVM 由四个模块组成（详见[架构文档](docs/zh/architecture.md)）：

```
┌─────────────────────────────────────────────────┐
│                  目标 JVM 进程                    │
│                                                  │
│  ┌──────────┐    ┌──────────────┐               │
│  │  你的应用  │◄──│ joltvm-agent │               │
│  └──────────┘    │  (字节码引擎)  │               │
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
              │     浏览器 / CLI       │
              │  ┌─────────────────┐  │
              │  │   joltvm-ui     │  │
              │  │  (Monaco Editor │  │
              │  │  + 火焰图)      │  │
              │  └─────────────────┘  │
              └───────────────────────┘
```

| 模块 | 说明 | 状态 |
|------|------|------|
| `joltvm-agent` | Java Agent 核心 — premain/agentmain 入口、Instrumentation 管理、Attach API | ✅ Phase 1 |
| `joltvm-server` | 嵌入式 Netty HTTP 服务器，提供 REST API（类列表、详情、反编译、热替换、追踪、Spring 感知、安全审计）+ Web UI | ✅ Phase 2–7 |
| `joltvm-cli` | 命令行工具，将 Agent 附着到运行中的 JVM 进程 | ✅ Phase 1 |
| `joltvm-ui` | 浏览器端 Web IDE（嵌入 joltvm-server） | ✅ Phase 6 |

---

## 📦 安装

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

## 🗺️ 路线图

- [x] **Phase 1**：Agent 骨架（premain/agentmain）+ Attach API + CLI
- [x] **Phase 2**：Netty Web Server + 基础 API（列出类、反编译源码）
- [x] **Phase 3**：热替换（编译 → redefineClasses）+ 回滚
- [x] **Phase 4**：方法追踪（Byte Buddy Advice）+ 火焰图数据
- [x] **Phase 5**：Spring Boot 感知（Bean 列表、URL 映射、依赖链分析）
- [x] **Phase 6**：Web UI（Monaco Editor + 火焰图 + 仪表盘 + Spring 面板）
- [x] **Phase 7**：安全审计（RBAC + 令牌认证 + 审计日志 + 导出）
- [x] **Phase 8**：安全加固（PBKDF2 密码哈希、Bug 修复、线程安全）
- [ ] **Phase 9**：线程诊断（线程列表、CPU Top-N、死锁检测）
- [ ] **Phase 10**：JVM 仪表盘增强（GC 统计、系统属性、Classpath）
- [ ] **Phase 11**：ClassLoader 分析 + Logger 动态调级
- [ ] **Phase 12**：OGNL 表达式引擎（运行时对象查看）
- [ ] **Phase 13**：Watch 命令（OGNL 条件过滤的方法观察）
- [ ] **Phase 14**：async-profiler 集成（CPU/Alloc/Lock 分析）
- [ ] **Phase 15**：WebSocket 实时推送
- [ ] **Phase 16**：Plugin/SPI 扩展机制
- [ ] **Phase 17**：Tunnel Server 远程诊断 → v1.0.0 正式发布

---

## 🤝 参与贡献

欢迎贡献代码！提交 PR 前请阅读贡献指南。

1. Fork 本仓库
2. 创建功能分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 创建 Pull Request

---

## 📄 许可证

本项目采用 Apache License 2.0 许可 — 详见 [LICENSE](LICENSE) 文件。

---

## 🙏 致谢

- [Arthas](https://github.com/alibaba/arthas) — JVM 诊断工具的灵感来源
- [Byte Buddy](https://bytebuddy.net/) — 字节码操作库
- [CFR](https://www.benf.org/other/cfr/) — Java 反编译器
- [Netty](https://netty.io/) — 异步事件驱动网络框架
- [Monaco Editor](https://microsoft.github.io/monaco-editor/) — Web IDE 代码编辑器
