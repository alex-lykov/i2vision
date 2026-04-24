/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

plugins {
    kotlin("jvm")
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

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.7")

    // Internal dependencies (already extracted modules)
    implementation(project(":llm-client"))
    implementation(project(":architecture-types"))
    implementation(project(":vslfc-core"))
    implementation(project(":intent-parser"))

    // External dependencies
    implementation("org.yaml:snakeyaml:2.2")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.slf4j:slf4j-simple:2.0.7")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("discovery-engine")
                description.set("Discovery engine for code flow, logic, and architecture analysis")
                url.set("https://github.com/alex-lykov/i2vision/discovery-engine")

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
                    connection.set("scm:git:git://github.com/alex-lykov/i2vision/discovery-engine")
                    developerConnection.set("scm:git:ssh://github.com/alex-lykov/i2vision/discovery-engine")
                    url.set("https://github.com/alex-lykov/i2vision/discovery-engine")
                }
            }
        }
    }
}
