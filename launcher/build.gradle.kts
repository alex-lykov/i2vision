import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.compose") version "1.6.10"
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(project(":core:config"))
    implementation(project(":core:orchestrator"))
    implementation(project(":core:session"))
    implementation(project(":models:wrappers"))
    implementation(project(":models:cloud"))
    implementation(project(":switching"))
    implementation(project(":context"))
    implementation("ai.koog:koog-agents:0.6.3")
    implementation("org.slf4j:slf4j-simple:2.0.9")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing")
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "koog-coding-agent"
            packageVersion = "1.0.0"
        }
    }
}

tasks.withType<JavaExec> {
    standardInput = System.`in`
    isIgnoreExitValue = false
}
