pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "koog-coding-agent"

// Core modules (keep split for now - can consolidate later if needed)
include("core:orchestrator")
include("core:session")
include("core:coroutines")
include("core:config")

// Model modules (keep split - wrappers vs cloud makes sense)
include("models:wrappers")
include("models:cloud")

// Consolidated modules (flattened from nested structure)
include("switching")
include("context")
include("pipeline")
include("learning")
include("server")
include("database")
include("ui")
include("security")

// Kotlin analysis
include("kotlin:analysis")

// Launcher (contains main)
include("launcher")
