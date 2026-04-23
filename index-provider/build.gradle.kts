plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "com.i2vision"
version = "1.0.0"

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    // Kotlin
    implementation(kotlin("stdlib"))

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.7")

    // YAML
    implementation("org.yaml:snakeyaml:2.2")

    // Storage
    implementation(project(":storage-core"))

    // Testing
    testImplementation(kotlin("test"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["kotlin"])
        }
    }
}

kotlin {
    jvmToolchain(21)
}
