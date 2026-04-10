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
    java
    id("com.gradleup.shadow") version "8.3.0" apply false
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

val projectGroup: String by project
val projectVersion: String by project

allprojects {
    group = projectGroup
    version = projectVersion

    repositories {
        mavenCentral()
    }
}

// ---------------------------------------------------------------------------
// Maven Central publishing via Sonatype Central Portal
// ---------------------------------------------------------------------------
nexusPublishing {
    repositories {
        // OSSRH is deprecated (EOL June 30, 2025). Use the Central Portal staging API.
        // See: https://central.sonatype.org/publish/publish-portal-ossrh-staging-api/
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
            // Credentials read from: ORG_GRADLE_PROJECT_sonatypeUsername / ORG_GRADLE_PROJECT_sonatypePassword
            // or gradle.properties: sonatypeUsername / sonatypePassword
        }
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
    }

    tasks.withType<Javadoc> {
        options.encoding = "UTF-8"
        if (options is StandardJavadocDocletOptions) {
            (options as StandardJavadocDocletOptions).apply {
                addStringOption("Xdoclint:none", "-quiet")
                charSet = "UTF-8"
            }
        }
        // Don't fail when a submodule has no public/protected classes to document (e.g., server placeholder)
        isFailOnError = false
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
    }

    dependencies {
        // Testing
        "testImplementation"(platform("org.junit:junit-bom:${property("junitVersion")}"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.mockito:mockito-core:${property("mockitoVersion")}")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    // -----------------------------------------------------------------
    // Sources JAR + Javadoc JAR (required by Maven Central)
    // -----------------------------------------------------------------
    java {
        withSourcesJar()
        withJavadocJar()
    }

    // -----------------------------------------------------------------
    // Maven publication configuration
    // -----------------------------------------------------------------
    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("mavenJava") {
                from(components["java"])

                pom {
                    name.set(project.name)
                    description.set(property("projectDescription").toString())
                    url.set(property("projectUrl").toString())

                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }

                    developers {
                        developer {
                            id.set("lucientong")
                            name.set("lucientong")
                            url.set("https://github.com/lucientong")
                        }
                    }

                    scm {
                        url.set("https://github.com/lucientong/joltvm")
                        connection.set("scm:git:git://github.com/lucientong/joltvm.git")
                        developerConnection.set("scm:git:ssh://git@github.com/lucientong/joltvm.git")
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // GPG Signing (required by Maven Central)
    // -----------------------------------------------------------------
    configure<SigningExtension> {
        // In CI: use in-memory key from environment variables
        //   ORG_GRADLE_PROJECT_signingKeyId
        //   ORG_GRADLE_PROJECT_signingKey
        //   ORG_GRADLE_PROJECT_signingPassword
        val signingKeyId: String? by project
        val signingKey: String? by project
        val signingPassword: String? by project

        if (signingKey != null) {
            useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        }

        // Only sign when publishing to a remote repository (not mavenLocal)
        isRequired = gradle.taskGraph.allTasks.any { it.name.contains("publishToSonatype") }

        sign(the<PublishingExtension>().publications["mavenJava"])
    }
}
