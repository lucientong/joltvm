# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Gradle multi-module project structure (joltvm-agent, joltvm-server, joltvm-cli)
- Java Agent entry point with `premain()` and `agentmain()` support
- `InstrumentationHolder` — thread-safe singleton for Instrumentation instance
- `AttachHelper` — dynamic agent attachment via Attach API
- `JoltVMCli` — CLI tool with `attach <pid>` command
- Project documentation (English + Chinese)
