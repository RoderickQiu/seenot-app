plugins {
    kotlin("jvm")
    application
}

group = "com.seenot.debugger"
version = "1.0-SNAPSHOT"

dependencies {
    // Kotlin
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // DashScope SDK
    implementation("com.alibaba:dashscope-sdk-java:2.16.7")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // CLI
    implementation("com.github.ajalt.clikt:clikt:4.2.1")

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.9")

    // Image processing (for screenshot handling)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.7.3")

    // Testing
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.seenot.debugger.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
