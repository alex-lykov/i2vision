plugins {
    kotlin("jvm")
    application
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
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    
    // CLI parsing
    implementation("com.github.ajalt.clikt:clikt:4.2.1")
    
    // Internal dependencies
    implementation(project(":discovery-api"))
    implementation(project(":i2vision-discover"))
    implementation(project(":i2vision-instant"))
    implementation(project(":vslfc-core"))
    implementation(project(":storage-core"))
    implementation(project(":contracts"))
    implementation(project(":intent-parser"))
    implementation(project(":architecture-types"))
    
    // YAML for output
    implementation("org.yaml:snakeyaml:2.2")
    
    // Testing
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.i2vision.cli.I2VisionCliKt")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            
            pom {
                name.set("i2vision-cli")
                description.set("CLI interface for i2vision discovery engine")
                url.set("https://github.com/i2vision/i2vision-cli")
                
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
                    connection.set("scm:git:git://github.com/i2vision/i2vision-cli.git")
                    developerConnection.set("scm:git:ssh://github.com/i2vision/i2vision-cli.git")
                    url.set("https://github.com/i2vision/i2vision-cli")
                }
            }
        }
    }
}
