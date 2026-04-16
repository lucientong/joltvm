/*
 * Copyright 2026 lucientong.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    `java-library`
}

dependencies {
    // Core agent module
    implementation(project(":joltvm-agent"))

    // HTTP/WebSocket server (Phase 2)
    implementation("io.netty:netty-all:${property("nettyVersion")}")

    // Java decompiler for viewing source code (Phase 2)
    implementation("org.benf:cfr:${property("cfrVersion")}")

    // JSON processing
    implementation("com.google.code.gson:gson:${property("gsonVersion")}")

    // Byte Buddy for method tracing (Phase 4)
    implementation("net.bytebuddy:byte-buddy:${property("byteBuddyVersion")}")

    // Unified diff generation for hot-swap audit (Phase 8)
    implementation("io.github.java-diff-utils:java-diff-utils:${property("javaDiffUtilsVersion")}")

    // OGNL expression engine for runtime object inspection (Phase 12)
    implementation("ognl:ognl:${property("ognlVersion")}")
}
