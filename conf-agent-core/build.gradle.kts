/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

plugins {
    kotlin("jvm")
    application
    `maven-publish`
}

group = "com.i2vision"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Core Kotlin
    implementation(kotlin("stdlib"))

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.7.3")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // YAML (for DiscoveryEngine tool parsing)
    implementation("org.yaml:snakeyaml:2.2")

    // External dependency (koog-agents for AI agent framework)
    implementation("ai.koog:koog-agents:0.6.3")

    // LLM Client
    implementation(project(":llm-client"))

    // Ktor Server (for JSON-RPC HTTP server)
    implementation("io.ktor:ktor-server-core:2.3.7")
    implementation("io.ktor:ktor-server-cio:2.3.7")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-server-websockets:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.7")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.slf4j:slf4j-simple:2.0.7")
    testImplementation("io.ktor:ktor-server-tests:2.3.7")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

// Configure source sets to include jvmMain
sourceSets {
    main {
        kotlin {
            srcDirs("src/jvmMain/kotlin", "src/commonMain/kotlin")
        }
    }
    test {
        kotlin {
            srcDirs("src/jvmTest/kotlin", "src/commonTest/kotlin")
        }
    }
}

application {
    mainClass.set("com.i2vision.agent.server.AgentServerMainKt")
}

// Custom task to run the agent server with arguments
tasks.register<JavaExec>("runAgentServer") {
    group = "application"
    description = "Run the i2Vision Agent JSON-RPC Server"
    
    mainClass.set("com.i2vision.agent.server.AgentServerMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    
    // Pass command line arguments
    if (project.hasProperty("args")) {
        args((project.property("args") as String).split(" "))
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("conf-agent-core")
                description.set("Core configurable agent framework with discovery, execution, and formatting engines")
                url.set("https://github.com/alex-lykov/i2vision/conf-agent-core")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("i2vision")
                        name.set("i2vision Team")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/alex-lykov/i2vision/conf-agent-core")
                    developerConnection.set("scm:git:ssh://github.com/alex-lykov/i2vision/conf-agent-core")
                    url.set("https://github.com/alex-lykov/i2vision/conf-agent-core")
                }
            }
        }
    }
}
