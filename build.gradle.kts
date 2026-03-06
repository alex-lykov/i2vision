// Root build configuration for multi-module project
plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.serialization") version "2.2.21" apply false
}

group = "com.alyk.ai.koog"
version = "1.0-SNAPSHOT"

allprojects {
    repositories {
        mavenCentral()
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
        add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        add("implementation", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
        add("implementation", "org.slf4j:slf4j-api:2.0.9")
        
        add("testImplementation", "org.jetbrains.kotlin:kotlin-test")
    }
}
