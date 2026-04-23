import java.time.Duration

plugins {
    kotlin("jvm")
}

dependencies {
    // Self-discovery utility dependencies
    implementation(project(":i2vision-discover"))
    implementation(project(":i2vision-cli"))
    implementation(project(":i2vision-mcp"))
    implementation(project(":i2vision-instant"))
    implementation(project(":i2vision-architecture"))
    implementation(project(":vslfc-core"))
    implementation(project(":llm-client"))
    implementation(project(":storage-core"))
    implementation(project(":index-provider"))
    implementation(project(":link-service"))
    implementation(project(":intent-parser"))
    implementation(project(":discovery-engine"))
    implementation(project(":contracts"))
    implementation(project(":discovery-api"))
    implementation(project(":architecture-types"))
    implementation(project(":conf-agent-core"))
    
    // YAML config parsing
    implementation("org.yaml:snakeyaml:2.2")
    
    // Test frameworks - JUnit 4 for compatibility with existing tests
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Long-running tests
    timeout.set(Duration.ofMinutes(30))
}

// Add task to run SelfDiscoveryTest
tasks.register<JavaExec>("runSelfDiscoveryTest") {
    group = "verification"
    description = "Run SelfDiscoveryTest standalone"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.i2vision.validation.SelfDiscoveryTestKt")
    standardOutput = System.out
    errorOutput = System.err
}

// Alias for convenience
tasks.register("run") {
    group = "verification"
    description = "Alias for runSelfDiscoveryTest"
    dependsOn("runSelfDiscoveryTest")
}
