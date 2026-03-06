plugins {
    application
}

dependencies {
    implementation(project(":core:orchestrator"))
    implementation(project(":core:session"))
    implementation(project(":models:wrappers"))
    implementation("ai.koog:koog-agents:0.6.3")
    implementation("org.slf4j:slf4j-simple:2.0.9")
}

application {
    mainClass.set("com.alyk.ai.koog.launcher.MainKt")
}

tasks.withType<JavaExec> {
    standardInput = System.`in`
    isIgnoreExitValue = false
}
