import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

kotlin {
    android {
        namespace = "capital.yuri.yuriplayer.core"
        compileSdk = 36
        minSdk = 27
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.cio)
        }
        jvmMain.dependencies {
            implementation(libs.jaudiotagger)
            implementation(libs.ktor.client.cio)
            implementation(libs.slf4j.api)
            implementation(libs.androidx.sqlite.bundled)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

dependencies {
    // Common metadata pass generates the KMP constructor (expect/actual) and
    // validates the schema. Per-target passes below generate the real
    // `YuriDatabase_Impl` that each platform loads at runtime.
    add("kspCommonMainMetadata", libs.androidx.room.compiler)
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspJvm", libs.androidx.room.compiler)
}

room {
    schemaDirectory(layout.projectDirectory.dir("schemas").asFile.absolutePath)
}
