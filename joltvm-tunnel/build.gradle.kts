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
    application
    id("com.gradleup.shadow")
}

application {
    mainClass.set("com.joltvm.tunnel.TunnelServerMain")
}

dependencies {
    // Netty for WebSocket server + HTTP reverse proxy
    implementation("io.netty:netty-all:${property("nettyVersion")}")

    // JSON processing
    implementation("com.google.code.gson:gson:${property("gsonVersion")}")

    // SLF4J + Logback for logging
    implementation("org.slf4j:slf4j-api:${property("slf4jVersion")}")
    runtimeOnly("ch.qos.logback:logback-classic:${property("logbackVersion")}")
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()

    manifest {
        attributes(
            "Main-Class" to "com.joltvm.tunnel.TunnelServerMain",
            "Implementation-Title" to "JoltVM Tunnel Server",
            "Implementation-Version" to project.version
        )
    }
}

tasks.processResources {
    filesMatching("tunnel-version.properties") {
        expand("projectVersion" to project.version)
    }
}
