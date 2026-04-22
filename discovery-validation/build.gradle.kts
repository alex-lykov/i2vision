import java.time.Duration

plugins {
    kotlin("jvm")
    application
}

dependencies {
    // Test all modules together
    testImplementation(project(":i2vision-discover"))
    testImplementation(project(":i2vision-cli"))
    testImplementation(project(":i2vision-mcp"))
    testImplementation(project(":i2vision-instant"))
    testImplementation(project(":i2vision-architecture"))
    testImplementation(project(":vslfc-core"))
    testImplementation(project(":llm-client"))
    testImplementation(project(":storage-core"))
    testImplementation(project(":index-provider"))
    testImplementation(project(":link-service"))
    testImplementation(project(":intent-parser"))
    testImplementation(project(":discovery-engine"))
    testImplementation(project(":contracts"))
    testImplementation(project(":discovery-api"))
    testImplementation(project(":architecture-types"))
    testImplementation(project(":conf-agent-core"))
    
    // Test frameworks - JUnit 4 for compatibility with existing tests
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    
    // YAML config parsing
    testImplementation("org.yaml:snakeyaml:2.2")
}

tasks.test {
    // Long-running tests
    timeout.set(Duration.ofMinutes(30))
}

// Add task to run SelfDiscoveryTest
tasks.register<JavaExec>("runSelfDiscoveryTest") {
    group = "verification"
    description = "Run SelfDiscoveryTest standalone"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.i2vision.validation.SelfDiscoveryTestKt")
    standardOutput = System.out
    errorOutput = System.err
}
