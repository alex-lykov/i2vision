plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    // Project dependency on the database module
    implementation(project(":database"))

    // Compose UI dependencies
    implementation(compose.desktop.currentOs)
}
