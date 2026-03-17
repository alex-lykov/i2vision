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
                name.set("i2vision-architecture")
                description.set("Architecture detection for multi-technology projects with framework and pattern recognition")
                url.set("https://github.com/i2vision/i2vision-architecture")
                
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
                    connection.set("scm:git:git://github.com/i2vision/i2vision-architecture.git")
                    developerConnection.set("scm:git:ssh://github.com/i2vision/i2vision-architecture.git")
                    url.set("https://github.com/i2vision/i2vision-architecture")
                }
            }
        }
    }
}
