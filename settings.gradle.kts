/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

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

rootProject.name = "i2vision"

// Public modules - MIT-licensed standalone libraries
include("architecture-types")
include("vslfc-core")
include("verbalization-core")
include("i2vision-architecture")
include("llm-client")
include("conf-agent-core")
include("storage-core")
include("intent-parser")
include("discovery-engine")
include("contracts")
include("discovery-api")

include("i2vision-discover")
include("i2vision-cli")
include("i2vision-instant")
include("i2vision-mcp")
include("index-provider")
include("link-service")

// Internal validation module (not published)
include("discovery-validation")
