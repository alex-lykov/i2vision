/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

// Root build configuration for multi-module project
plugins {
    kotlin("plugin.serialization") version "2.3.0" apply false
    id("org.jetbrains.compose") version "1.6.11" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
    kotlin("jvm") version "2.3.20"
}

group = "com.i2vision"
version = "1.0-SNAPSHOT"

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://packages.jetbrains.team/maven/p/koog/koog")
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

    group = rootProject.group
    version = rootProject.version

    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    dependencies {
        // Common dependencies for all modules
        add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
        add("implementation", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")
        add("implementation", "org.slf4j:slf4j-api:2.0.9")

        add("testImplementation", "org.jetbrains.kotlin:kotlin-test")
    }
}
dependencies {
    implementation(kotlin("stdlib-jdk8"))
}
repositories {
    mavenCentral()
}
kotlin {
    jvmToolchain(8)
}