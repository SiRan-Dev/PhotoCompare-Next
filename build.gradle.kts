plugins {
    id("com.android.application") version "9.3.1" apply false
    // Compose 1.12 (BOM 2026.08) is compiled with Kotlin 2.4 metadata, which the built-in
    // Kotlin compiler of AGP 9.3.x (2.2.10) cannot read. Use explicit KGP 2.4.10 instead.
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
