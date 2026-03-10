dependencies {
    implementation(project(":models:common"))
    implementation(project(":core:coroutines"))
    // Note: PerformanceMonitor is passed as parameter, not imported
    // This avoids circular dependency with :switching
    implementation("ai.koog:koog-agents:0.6.3")
}
