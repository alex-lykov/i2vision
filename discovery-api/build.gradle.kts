/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.7")

    // Internal dependencies (extracted modules)
    implementation(project(":vslfc-core"))
    implementation(project(":architecture-types"))
    implementation(project(":discovery-engine"))
    implementation(project(":contracts"))
    implementation(project(":intent-parser"))

    // Testing
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("discovery-api")
                description.set("Discovery API interfaces for i2vision - breaks circular dependencies")
                url.set("https://github.com/alex-lykov/i2vision/discovery-api")

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
                    connection.set("scm:git:git://github.com/alex-lykov/i2vision/discovery-api.git")
                    developerConnection.set("scm:git:ssh://github.com/alex-lykov/i2vision/discovery-api.git")
                    url.set("https://github.com/alex-lykov/i2vision/discovery-api")
                }
            }
        }
    }
}
