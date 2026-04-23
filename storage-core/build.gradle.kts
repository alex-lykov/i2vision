plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
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

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.7")

    // VSLFC structure
    implementation(project(":vslfc-core"))

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
                name.set("storage-core")
                description.set("Core storage abstractions for sessions, cache, projects, and other persistence needs")
                url.set("https://github.com/alex-lykov/i2vision/storage-core")

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
                    connection.set("scm:git:git://github.com/alex-lykov/i2vision/storage-core.git")
                    developerConnection.set("scm:git:ssh://github.com/alex-lykov/i2vision/storage-core.git")
                    url.set("https://github.com/alex-lykov/i2vision/storage-core")
                }
            }
        }
    }
}
