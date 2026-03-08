plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":models:wrappers"))
    implementation(project(":switching:monitor"))
    implementation("ai.koog:koog-agents:0.6.3")
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")
}
