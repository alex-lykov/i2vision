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
    
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.7")
    implementation("org.slf4j:slf4j-simple:2.0.7")
    
    // YAML
    implementation("org.yaml:snakeyaml:2.2")
    
    // Internal dependencies
    implementation(project(":discovery-api"))
    implementation(project(":i2vision-discover"))
    implementation(project(":storage-core"))
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    
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
                name.set("i2vision-instant")
                description.set("Instant context API for LLMs")
                url.set("https://github.com/i2vision/i2vision-instant")
                
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                
                developers {
                    developer {
                        id.set("i2vision")
                        name.set("i2vision Team")
                        email.set("team@i2vision.com")
                    }
                }
                
                scm {
                    connection.set("scm:git:git://github.com/i2vision/i2vision-instant.git")
                    developerConnection.set("scm:git:ssh://github.com/i2vision/i2vision-instant.git")
                    url.set("https://github.com/i2vision/i2vision-instant")
                }
            }
        }
    }
}
