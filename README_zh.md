# ⚡ JoltVM

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![CI](https://github.com/lucientong/joltvm/actions/workflows/ci.yml/badge.svg)](https://github.com/lucientong/joltvm/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.lucientong/joltvm-agent)](https://central.sonatype.com/artifact/io.github.lucientong/joltvm-agent)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Build](https://img.shields.io/badge/build-Gradle-02303A.svg)](https://gradle.org/)

**如同一道闪电 — 精准、即时地诊断运行中的 JVM。**

JoltVM 是一个 JVM 在线诊断与热修复框架。通过 Java Agent 附着到目标 JVM，在浏览器端 Web IDE 中浏览和反编译类、一键热修复、可视化火焰图、方法调用链追踪，并内置安全审计与一键回滚。

[English](README.md) · [架构文档](docs/zh/architecture.md) · [Architecture](docs/en/architecture.md)

---

## ✨ 功能特性（规划中）

> JoltVM 正在积极开发中。Phase 1（Agent 骨架 + Attach API）已完成。完整计划参见[路线图](#-路线图)。

### 🖥️ 浏览器端 Web IDE
不再需要记忆 50+ 条命令。可视化界面集成 Monaco Editor 代码编辑器、实时日志流、类和方法树导航。在线编辑代码并直接热修复。

### 🔧 一键热修复
在 Web IDE 中编辑代码 → 自动编译 → 通过 `Instrumentation.redefineClasses()` 即时替换字节码。无需手动 `jad` → `mc` → `retransform` 流程。内置回滚，保留原始字节码。

### 🔥 交互式火焰图
浏览器内可缩放、可搜索的火焰图（d3-flame-graph）。支持 CPU 时间、Wall 时间和内存分配视图切换。前后对比优化效果一目了然。

### 🌱 Spring Boot 感知
列出所有 `@RestController` 端点及 URL 映射。追踪从 URL 到数据库的完整调用链。查看任意 Spring Bean 的当前字段值。热修复 Spring 组件无需重启。

### 🔒 安全审计
基于角色的访问控制（viewer/operator/admin）。每次热修复都生成包含时间戳、操作人和原因的 diff。可选审批工作流。不可篡改、可导出的审计日志。支持 SSO/LDAP 集成。

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
```

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
| `joltvm-server` | 嵌入式 Netty HTTP/WebSocket 服务器，提供 REST API | 📋 Phase 2 |
| `joltvm-cli` | 命令行工具，将 Agent 附着到运行中的 JVM 进程 | ✅ Phase 1 |
| `joltvm-ui` | React + TypeScript Web IDE 前端 | 📋 Phase 6 |

---

## 📦 安装

### Maven Central

```xml
<dependency>
    <groupId>io.github.lucientong</groupId>
    <artifactId>joltvm-agent</artifactId>
    <version>0.1.1</version>
</dependency>
```

```kotlin
// Gradle Kotlin DSL
implementation("io.github.lucientong:joltvm-agent:0.1.1")
```

---

## 🗺️ 路线图

- [x] **Phase 1**：Agent 骨架（premain/agentmain）+ Attach API + CLI
- [ ] **Phase 2**：Netty Web Server + 基础 API（列出类、反编译源码）
- [ ] **Phase 3**：热替换（编译 → redefineClasses）+ 回滚
- [ ] **Phase 4**：方法追踪（Byte Buddy Advice）+ 火焰图数据
- [ ] **Phase 5**：Spring Boot 感知（Bean 列表、URL 映射）
- [ ] **Phase 6**：Web UI（Monaco Editor + 火焰图 + 实时日志）

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
