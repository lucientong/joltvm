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
    mainClass.set("com.joltvm.cli.JoltVMCli")
}

dependencies {
    // Thin agent library only (AttachHelper). The fat agent is embedded as a resource.
    implementation(project(":joltvm-agent"))
}

tasks.processResources {
    filesMatching("version.properties") {
        expand("projectVersion" to project.version)
    }
}

val embeddedAgentDir = layout.buildDirectory.dir("generated-resources")

/**
 * Copy the canonical agent fat JAR under a non-.jar extension so the Shadow plugin
 * does not explode/merge it into the CLI classpath.
 */
val prepareEmbeddedAgent by tasks.registering(Copy::class) {
    dependsOn(":joltvm-distribution:shadowJar")
    from(project(":joltvm-distribution").tasks.named("shadowJar").map { it.outputs.files })
    into(embeddedAgentDir.map { it.dir("agent") })
    rename { "joltvm-agent-all.jar.embedded" }
}

sourceSets {
    main {
        resources.srcDir(embeddedAgentDir)
    }
}

tasks.named("processResources") {
    dependsOn(prepareEmbeddedAgent)
}

tasks.named("sourcesJar") {
    dependsOn(prepareEmbeddedAgent)
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
    dependsOn(prepareEmbeddedAgent)

    manifest {
        attributes(
            "Main-Class" to "com.joltvm.cli.JoltVMCli",
            "Implementation-Title" to "JoltVM CLI",
            "Implementation-Version" to project.version
        )
    }
}
