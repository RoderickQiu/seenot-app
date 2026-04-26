plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val releaseStoreFilePath = project.findProperty("RELEASE_STORE_FILE") as? String
val releaseStorePassword = project.findProperty("RELEASE_STORE_PASSWORD") as? String
val releaseKeyAlias = project.findProperty("RELEASE_KEY_ALIAS") as? String
val releaseKeyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as? String
val configuredVersionCode = (project.findProperty("SEENOT_VERSION_CODE") as? String)
    ?.toIntOrNull()
    ?: 1
val configuredVersionName = (project.findProperty("SEENOT_VERSION_NAME") as? String)
    ?.takeIf { it.isNotBlank() }
    ?: "1.0.0"
val hasReleaseSigningConfig = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.seenot.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.seenot.app"
        minSdk = 30
        targetSdk = 35
        versionCode = configuredVersionCode
        versionName = configuredVersionName

        val developmentModeEnabled =
            (project.findProperty("SEENOT_DEVELOPMENT_MODE") as? String)
                ?.toBooleanStrictOrNull()
                ?: false
        val rawDashscopeKey = project.findProperty("DASHSCOPE_API_KEY") as? String ?: ""
        val buildTimestampMs = System.currentTimeMillis()
        val devDashscopeKeyValidUntilMs = if (developmentModeEnabled && rawDashscopeKey.isNotBlank()) {
            buildTimestampMs + 14L * 24L * 60L * 60L * 1000L
        } else {
            0L
        }
        val injectedDashscopeKey = if (developmentModeEnabled) rawDashscopeKey else ""

        buildConfigField("String", "DASHSCOPE_API_KEY", "\"$injectedDashscopeKey\"")
        buildConfigField("boolean", "ENABLE_DEVELOPMENT_MODE", developmentModeEnabled.toString())
        buildConfigField(
            "long",
            "DEVELOPMENT_DASHSCOPE_KEY_VALID_UNTIL_EPOCH_MS",
            "${devDashscopeKeyValidUntilMs}L"
        )
        val runtimeEventLoggingEnabled = developmentModeEnabled
        buildConfigField(
            "boolean",
            "ENABLE_RUNTIME_EVENT_LOGGING",
            runtimeEventLoggingEnabled.toString()
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Specify build tools version
    buildToolsVersion = "35.0.0"

    signingConfigs {
        create("release") {
            if (hasReleaseSigningConfig) {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "Release signing is not configured. " +
                        "Set RELEASE_STORE_FILE/RELEASE_STORE_PASSWORD/" +
                        "RELEASE_KEY_ALIAS/RELEASE_KEY_PASSWORD to generate a signed APK."
                )
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val verifyRoomMigrations by tasks.registering {
    group = "verification"
    description = "Fails the build if Room database version changes without a matching migration."

    val dbFile = layout.projectDirectory.file("src/main/java/com/seenot/app/data/local/SeenotDatabase.kt")
    val javaSourceDir = layout.projectDirectory.dir("src/main/java")
    val kotlinSourceDir = layout.projectDirectory.dir("src/main/kotlin")
    val guardBaselineFile = layout.projectDirectory.file("ROOM_MIGRATION_GUARD_BASELINE")
    inputs.file(dbFile)
    if (javaSourceDir.asFile.exists()) {
        inputs.dir(javaSourceDir)
    }
    if (kotlinSourceDir.asFile.exists()) {
        inputs.dir(kotlinSourceDir)
    }
    inputs.file(guardBaselineFile)

    doLast {
        val dbText = dbFile.asFile.readText()

        val versionMatch = Regex("""version\s*=\s*(\d+)""").find(dbText)
            ?: error("Could not find Room database version in SeenotDatabase.kt")
        val currentVersion = versionMatch.groupValues[1].toInt()
        val baselineVersion = guardBaselineFile.asFile.readText().trim().toInt()

        if (currentVersion <= baselineVersion) return@doLast

        val expectedFrom = currentVersion - 1
        val migrationRegex = Regex("""Migration\s*\(\s*$expectedFrom\s*,\s*$currentVersion\s*\)""")
        val sourceRoots = listOf(
            javaSourceDir.asFile,
            kotlinSourceDir.asFile
        ).filter { it.exists() }

        val hasMatchingMigration = sourceRoots
            .flatMap { root -> root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList() }
            .any { file -> migrationRegex.containsMatchIn(file.readText()) }

        check(hasMatchingMigration) {
            "Room database version is $currentVersion, but no matching Migration($expectedFrom, $currentVersion) was found. " +
                "Add an explicit migration before changing the version."
        }
    }
}

tasks.named("preBuild") {
    dependsOn(verifyRoomMigrations)
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)

    // Core Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.1")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Ktor for networking
    implementation("io.ktor:ktor-client-core:2.3.11")
    implementation("io.ktor:ktor-client-okhttp:2.3.11")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.11")
    implementation("io.ktor:ktor-serialization-gson:2.3.11")
    implementation("io.ktor:ktor-client-logging:2.3.11")
    // OkHttp for WebSocket (used for real-time STT)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // DashScope SDK for real-time speech recognition
    implementation("com.alibaba:dashscope-sdk-java:2.22.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Security - Encrypted SharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
