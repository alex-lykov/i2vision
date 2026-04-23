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

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.7")

    // Internal dependencies
    implementation(project(":discovery-engine"))
    implementation(project(":architecture-types"))

    // External dependencies
    implementation("org.yaml:snakeyaml:2.2")

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
                name.set("contracts")
                description.set("VSLFC contracts and validation for discovery operations")
                url.set("https://github.com/alex-lykov/i2vision/contracts")

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
                    connection.set("scm:git:git://github.com/alex-lykov/i2vision/contracts.git")
                    developerConnection.set("scm:git:ssh://github.com/alex-lykov/i2vision/contracts.git")
                    url.set("https://github.com/alex-lykov/i2vision/contracts")
                }
            }
        }
    }
}
