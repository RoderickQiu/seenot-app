// Top-level build file
plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
}

// Load local.properties if exists
val localProps = rootProject.file("local.properties")
if (localProps.exists()) {
    val props = java.util.Properties()
    localProps.inputStream().use { props.load(it) }
    props.forEach { (key, value) ->
        rootProject.ext.set(key.toString(), value)
    }
}
