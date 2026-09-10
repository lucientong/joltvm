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

import java.util.jar.JarFile

plugins {
    `java-library`
    id("com.gradleup.shadow")
}

/**
 * Packaging-only module that produces the canonical JoltVM agent fat JAR.
 *
 * <p>Compile-time DAG remains acyclic ({@code server → agent}). This module
 * sits at the end of the graph and merges agent + server + runtime dependencies
 * into a single attachable JAR with the Java Agent manifest.
 */
dependencies {
    implementation(project(":joltvm-agent"))
    implementation(project(":joltvm-server"))
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveBaseName.set("joltvm-agent")
    archiveClassifier.set("all")
    mergeServiceFiles()

    manifest {
        attributes(
            "Premain-Class" to "com.joltvm.agent.JoltVMAgent",
            "Agent-Class" to "com.joltvm.agent.JoltVMAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
            "Can-Set-Native-Method-Prefix" to "true",
            "Implementation-Title" to "JoltVM Agent",
            "Implementation-Version" to project.version
        )
    }

    // Relocate shaded dependencies to avoid conflicts with the target application
    relocate("net.bytebuddy", "com.joltvm.shaded.bytebuddy")
    relocate("com.google.gson", "com.joltvm.shaded.gson")
    relocate("org.objectweb.asm", "com.joltvm.shaded.asm")
    relocate("io.netty", "com.joltvm.shaded.netty")
    relocate("org.benf.cfr", "com.joltvm.shaded.cfr")
    relocate("com.github.difflib", "com.joltvm.shaded.difflib")
    relocate("ognl", "com.joltvm.shaded.ognl")
    relocate("javassist", "com.joltvm.shaded.javassist")
}

tasks.register("verifyShadowJar") {
    group = "verification"
    description = "Asserts the agent fat JAR contains server, Web UI, OpenAPI, and Agent-Class"
    dependsOn(tasks.shadowJar)
    doLast {
        val jarFile = tasks.shadowJar.get().archiveFile.get().asFile
        require(jarFile.exists()) { "Shadow JAR not found: $jarFile" }

        JarFile(jarFile).use { jar ->
            val entries = jar.entries().asSequence().map { entry -> entry.name }.toSet()
            val required = listOf(
                "com/joltvm/agent/JoltVMAgent.class",
                "com/joltvm/server/JoltVMServer.class",
                "webui/index.html",
                "webui/docs.html",
                "openapi/joltvm-openapi.json"
            )
            for (path in required) {
                require(entries.contains(path)) {
                    "Canonical agent fat JAR is missing required entry: $path"
                }
            }
            require(entries.any { name -> name.startsWith("com/joltvm/shaded/netty/") }) {
                "Canonical agent fat JAR is missing shaded Netty classes"
            }
            val agentClass = jar.manifest.mainAttributes.getValue("Agent-Class")
            require(agentClass == "com.joltvm.agent.JoltVMAgent") {
                "Canonical agent fat JAR must declare Agent-Class=com.joltvm.agent.JoltVMAgent, got: $agentClass"
            }
        }
    }
}

tasks.named("check") {
    dependsOn("verifyShadowJar")
}

// Packaging-only module — do not publish to Maven Central
tasks.matching { it.name.startsWith("publish") }.configureEach {
    enabled = false
}
tasks.withType<Javadoc>().configureEach { enabled = false }
tasks.matching { it.name == "sourcesJar" || it.name == "javadocJar" }.configureEach {
    enabled = false
}
