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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.7")
    implementation("org.slf4j:slf4j-simple:2.0.7")

    // YAML processing
    implementation("org.yaml:snakeyaml:2.2")

    // Jackson for YAML/JSON
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")
    implementation("com.networknt:json-schema-validator:1.0.79")

    // Internal dependencies (extracted modules)
    implementation(project(":vslfc-core"))
    implementation(project(":architecture-types"))
    implementation(project(":i2vision-architecture"))
    implementation(project(":llm-client"))
    implementation(project(":discovery-engine"))
    implementation(project(":contracts"))
    implementation(project(":intent-parser"))
    implementation(project(":discovery-api"))
    implementation(project(":conf-agent-core"))
    implementation(project(":storage-core"))
    implementation(project(":index-provider"))
    implementation(project(":link-service"))

    // Testing
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("i2vision-discover")
                description.set("Full discovery engine for i2vision - Code → Vision discovery")
                url.set("https://github.com/alex-lykov/i2vision/i2vision-discover")

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
                    connection.set("scm:git:git://github.com/alex-lykov/i2vision/i2vision-discover.git")
                    developerConnection.set("scm:git:ssh://github.com/alex-lykov/i2vision/i2vision-discover.git")
                    url.set("https://github.com/alex-lykov/i2vision/i2vision-discover")
                }
            }
        }
    }
}
