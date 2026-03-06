plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21" // Added kotlinx.serialization plugin
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.boxtox"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/koog/koog")
}

dependencies {
    // Koog core (this should contain everything)
    implementation("ai.koog:koog-agents:0.6.3")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Kotlin serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.9")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("MainKt")
}
kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}