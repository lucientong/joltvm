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
    id("com.gradleup.shadow")
}

dependencies {
    // Bytecode manipulation
    implementation("net.bytebuddy:byte-buddy:${property("byteBuddyVersion")}")

    // Low-level bytecode reading/writing (transitive via Byte Buddy, declared explicitly for clarity)
    implementation("org.ow2.asm:asm:${property("asmVersion")}")

    // JSON processing
    implementation("com.google.code.gson:gson:${property("gsonVersion")}")
}

tasks.jar {
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
}

tasks.shadowJar {
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

    // Relocate shaded dependencies to avoid conflicts with target application
    relocate("net.bytebuddy", "com.joltvm.shaded.bytebuddy")
    relocate("com.google.gson", "com.joltvm.shaded.gson")
    relocate("org.objectweb.asm", "com.joltvm.shaded.asm")
    relocate("io.netty", "com.joltvm.shaded.netty")
    relocate("org.benf.cfr", "com.joltvm.shaded.cfr")
}
