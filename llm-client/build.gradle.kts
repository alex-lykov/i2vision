plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "com.i2vision"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Core Kotlin
    implementation(kotlin("stdlib"))

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.7.3")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // External dependency (koog-agents for AI agent framework)
    implementation("ai.koog:koog-agents:0.6.3")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.7")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.slf4j:slf4j-simple:2.0.7")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("llm-client")
                description.set("Unified LLM client library with support for local Ollama, cloud providers, and model runtime management")
                url.set("https://github.com/alex-lykov/i2vision/llm-client")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("i2vision")
                        name.set("i2vision Team")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/alex-lykov/i2vision/llm-client.git")
                    developerConnection.set("scm:git:ssh://github.com/alex-lykov/i2vision/llm-client.git")
                    url.set("https://github.com/alex-lykov/i2vision/llm-client")
                }
            }
        }
    }
}
