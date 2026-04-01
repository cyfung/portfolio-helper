import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val localProps = Properties()
val localPropsFile = file("../../../local.properties")
if (localPropsFile.exists()) {
    localProps.load(localPropsFile.inputStream())
}

android {
    namespace = "com.portfoliohelper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.portfoliohelper"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.2"
    }

    signingConfigs {
        create("release") {
            storeFile = localProps.getProperty("keystore.file")?.let { file(it) }
            storePassword = localProps.getProperty("keystore.password")
            keyAlias = localProps.getProperty("keystore.alias")
            keyPassword = localProps.getProperty("keystore.keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.bundles.lifecycle)
    implementation(libs.navigation.compose)
    implementation(libs.work.runtime.ktx)

    implementation(libs.bundles.room)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.bundles.ktor)
    implementation(libs.jsoup)

}

tasks.register("releaseAab") {
    group = "release"
    description = "Build signed release AAB with native debug symbols"
    dependsOn("bundleRelease")
    doLast {
        val outputDir = file("release")
        outputDir.mkdirs()

        // Copy AAB from bundleRelease task outputs
        tasks.named("bundleRelease").get().outputs.files
            .filter { it.isFile && it.extension == "aab" }
            .forEach { aab ->
                val dest = File(outputDir, aab.name)
                aab.copyTo(dest, overwrite = true)
                println("Signed AAB: ${dest.absolutePath}")
            }

        // Copy native debug symbols zip (search recursively under outputs/native-debug-symbols)
        val nativeSymbolsDir = layout.buildDirectory.dir("outputs/native-debug-symbols").get().asFile
        if (nativeSymbolsDir.exists()) {
            nativeSymbolsDir.walkTopDown()
                .filter { it.isFile && it.extension == "zip" }
                .forEach { zip ->
                    val dest = File(outputDir, zip.name)
                    zip.copyTo(dest, overwrite = true)
                    println("Native debug symbols: ${dest.absolutePath}")
                }
        }
    }
}
