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
    // Core agent module (for AttachHelper)
    implementation(project(":joltvm-agent"))
}

tasks.processResources {
    // Generate version.properties from gradle.properties version,
    // so that JoltVMCli reads the version at runtime instead of hardcoding.
    filesMatching("version.properties") {
        expand("projectVersion" to project.version)
    }
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()

    manifest {
        attributes(
            "Main-Class" to "com.joltvm.cli.JoltVMCli",
            "Implementation-Title" to "JoltVM CLI",
            "Implementation-Version" to project.version
        )
    }
}
