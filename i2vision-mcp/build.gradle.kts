/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

plugins {
    kotlin("jvm")
    `maven-publish`
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.i2vision"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Core Kotlin
    implementation(kotlin("stdlib"))

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.7")
    implementation("org.slf4j:slf4j-simple:2.0.7")

    // Internal dependencies
    implementation(project(":discovery-api"))
    implementation(project(":i2vision-discover"))
    implementation(project(":i2vision-instant"))
    implementation(project(":index-provider"))
    implementation(project(":link-service"))
    implementation(project(":storage-core"))
    implementation(project(":vslfc-core"))

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // HTTP Server (Ktor)
    implementation("io.ktor:ktor-server-core:2.3.4")
    implementation("io.ktor:ktor-server-netty:2.3.4")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.4")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // JSON/YAML
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
    implementation("org.yaml:snakeyaml:2.2")

    // Testing
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    manifest {
        attributes["Main-Class"] = "com.i2vision.mcp.transport.StdioTransportKt"
    }
    archiveClassifier.set("all")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("i2vision-mcp")
                description.set("MCP server exposing i2vision discovery and context tools")
                url.set("https://github.com/alex-lykov/i2vision/i2vision-mcp")

                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("i2vision")
                        name.set("i2vision Team")
                        email.set("team@i2vision.com")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/alex-lykov/i2vision/i2vision-mcp")
                    developerConnection.set("scm:git:ssh://github.com/alex-lykov/i2vision/i2vision-mcp")
                    url.set("https://github.com/alex-lykov/i2vision/i2vision-mcp")
                }
            }
        }
    }
}
