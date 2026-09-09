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
    // Shadow is no longer used here — the canonical fat JAR is produced by
    // :joltvm-distribution. Keep this module as a thin library for Maven Central.
}

dependencies {
    // Bytecode manipulation
    implementation("net.bytebuddy:byte-buddy:${property("byteBuddyVersion")}")

    // Low-level bytecode reading/writing (transitive via Byte Buddy, declared explicitly for clarity)
    implementation("org.ow2.asm:asm:${property("asmVersion")}")

    // JSON processing
    implementation("com.google.code.gson:gson:${property("gsonVersion")}")
}

// Stage the distribution JAR under this project's build directory before
// publishing. Signing an artifact in joltvm-distribution/build/libs directly
// would make the agent and distribution Sign tasks claim the same .asc output.
val fatAgentPublicationDir = layout.buildDirectory.dir("publication/fat-agent")
val fatAgentPublicationJar = fatAgentPublicationDir.map {
    it.file("joltvm-agent-${project.version}-all.jar")
}
val prepareFatAgentPublication by tasks.registering(Copy::class) {
    dependsOn(":joltvm-distribution:shadowJar")
    from(project(":joltvm-distribution").tasks.named("shadowJar").map { it.outputs.files })
    into(fatAgentPublicationDir)
}

// Keep the thin JAR as the primary Maven artifact for API consumers, and
// publish the staged canonical distribution under the "all" classifier.
gradle.projectsEvaluated {
    publishing {
        publications.named<MavenPublication>("mavenJava") {
            artifact(fatAgentPublicationJar) {
                classifier = "all"
                extension = "jar"
                builtBy(prepareFatAgentPublication)
            }
        }
    }
}

tasks.matching { it.name == "signMavenJavaPublication" }.configureEach {
    dependsOn(prepareFatAgentPublication)
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
