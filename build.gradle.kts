import edu.sc.seis.launch4j.tasks.DefaultLaunch4jTask

plugins {
    kotlin("jvm") version "1.9.21"
    kotlin("plugin.serialization") version "1.9.21"
    application
    id("com.gradleup.shadow") version "8.3.5"
    id("edu.sc.seis.launch4j") version "4.0.0"
    id("org.panteleyev.jpackageplugin") version "1.7.6"
}

group = "com.portfoliohelper"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Ktor Server
    implementation("io.ktor:ktor-server-core:2.3.7")
    implementation("io.ktor:ktor-server-netty:2.3.7")
    implementation("io.ktor:ktor-server-html-builder:2.3.7")

    // Ktor HTTP Client for Yahoo Finance API
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")

    // kotlinx.html for HTML DSL
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.9.1")

    // Apache Commons CSV for parsing
    implementation("org.apache.commons:commons-csv:1.10.0")

    // Kotlinx Serialization for JSON parsing
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Jsoup for HTML parsing (NAV scraping from fund provider websites)
    implementation("org.jsoup:jsoup:1.17.2")

    // Kotlin Coroutines (for async/parallel fetching)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Dorkbox SystemTray for cross-platform system tray with Swing menu customization
    implementation("com.dorkbox:SystemTray:4.4")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.11")

    // Kotlin standard library
    implementation(kotlin("stdlib"))
}

application {
    mainClass.set("com.portfoliohelper.ApplicationKt")
}

// Java Toolchain Configuration (replaces sourceCompatibility/targetCompatibility)
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
        vendor.set(JvmVendorSpec.ADOPTIUM)  // Eclipse Adoptium (Temurin)
    }
}

// Kotlin will automatically use the Java toolchain
// No need for explicit kotlinOptions.jvmTarget when using toolchains

// Shadow JAR Configuration
tasks {
    shadowJar {
        archiveBaseName.set("portfolio-helper")
        archiveClassifier.set("all")
        archiveVersion.set(project.version.toString())

        mergeServiceFiles()  // Critical for Ktor's ServiceLoader

        manifest {
            attributes(
                "Main-Class" to "com.portfoliohelper.ApplicationKt",
                "Implementation-Title" to "Portfolio Helper",
                "Implementation-Version" to project.version
            )
        }

        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }

    named("build") {
        dependsOn(shadowJar)
    }
}

// jpackage Configuration using Petr Panteleyev plugin
// Creates an app image (portable application bundle) instead of an installer
tasks.jpackage {
    dependsOn(tasks.shadowJar)

    // Input: directory containing the shadow JAR
    input.set(layout.buildDirectory.dir("libs"))
    destination.set(layout.buildDirectory.dir("jpackage"))

    // Application entry point
    mainJar = tasks.shadowJar.get().archiveFileName.get()
    mainClass = "com.portfoliohelper.ApplicationKt"

    // Application metadata
    appName = "Portfolio Helper"
    appVersion = "1.0.0"  // Must be in X.Y.Z format
    vendor = "Portfolio Helper"
    copyright = "Copyright © 2026"

    // Create app image only (not installer)
    // This creates a self-contained application bundle without requiring WiX
    type = org.panteleyev.jpackage.ImageType.APP_IMAGE

    // JVM arguments for Netty/Ktor
    javaOptions = listOf(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        "-Dfile.encoding=UTF-8"
    )

    // Platform-specific icons
    icon.set(file("${projectDir}/src/main/resources/static/images/app-icon.ico"))
}

// Copy data and config files into jpackage output
tasks.register<Copy>("copyJpackageData") {
    dependsOn(tasks.jpackage)

    from("src/main/resources/data") {
        into("data")
    }
    from("src/main/resources") {
        include("application.conf", "logback.xml")
        into("config")
    }

    into(layout.buildDirectory.dir("jpackage/Portfolio Helper"))
}

// Launch4j Configuration for Windows EXE
launch4j {
    outfile.set("portfolio-helper.exe")
    mainClassName.set("com.portfoliohelper.ApplicationKt")
    productName.set("Portfolio Helper")
    fileDescription.set("Stock Portfolio Viewer")
    copyright.set("Copyright © 2026")
    companyName.set("Portfolio Helper")
    icon.set("${projectDir}/src/main/resources/static/images/app-icon.ico")
    setJarTask(tasks.shadowJar.get())
}

//// Configure createExe to use shadowJar instead of regular jar
//tasks.named("createExe").configure {
//    dependsOn(tasks.shadowJar)
//    doFirst {
//        // Replace the regular JAR with shadow JAR so Launch4j uses it
//        val shadowJar = tasks.shadowJar.get().archiveFile.get().asFile
//        val regularJar = tasks.jar.get().archiveFile.get().asFile
//        shadowJar.copyTo(regularJar, overwrite = true)
//    }
//}

// Portable Distribution Tasks
tasks.register<Zip>("portableDistZip") {
    group = "distribution"
    description = "Creates a portable ZIP distribution with shadow JAR, data, and config"
    archiveBaseName.set("${project.name}-portable")
    archiveClassifier.set("complete")

    from(tasks.shadowJar) {
        into("${project.name}")
    }

    from("src/main/resources/data") {
        into("${project.name}/data")
        include("*.csv", "README.md")
    }

    from("docs") {
        into("${project.name}")
        include("RUNNING.md")
        rename("RUNNING.md", "README.md")
    }

    from("src/main/resources") {
        into("${project.name}/config")
        include("application.conf", "logback.xml")
    }
}

tasks.register<Tar>("portableDistTar") {
    group = "distribution"
    description = "Creates a portable TAR.GZ distribution with shadow JAR, data, and config"
    archiveBaseName.set("${project.name}-portable")
    archiveClassifier.set("complete")
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")

    from(tasks.shadowJar) {
        into("${project.name}")
    }

    from("src/main/resources/data") {
        into("${project.name}/data")
        include("*.csv", "README.md")
    }

    from("docs") {
        into("${project.name}")
        include("RUNNING.md")
        rename("RUNNING.md", "README.md")
    }

    from("src/main/resources") {
        into("${project.name}/config")
        include("application.conf", "logback.xml")
    }
}

tasks.register<Zip>("windowsDistZip") {
    group = "distribution"
    description = "Creates a Windows distribution with EXE launcher"
    archiveBaseName.set("${project.name}-windows")
    archiveClassifier.set("exe")

    dependsOn(tasks.named("createExe"))

    from(layout.buildDirectory.dir("launch4j")) {
        into("${project.name}")
        include("portfolio-helper.exe")
    }

    from(tasks.shadowJar) {
        into("${project.name}/lib")
    }

    from("src/main/resources/data") {
        into("${project.name}/data")
        include("*.csv", "README.md")
    }

    from("docs") {
        into("${project.name}")
        include("RUNNING.md")
        rename("RUNNING.md", "README.md")
    }

    from("src/main/resources") {
        into("${project.name}/config")
        include("application.conf", "logback.xml")
    }
}

// Convenience task for complete jpackage distribution
tasks.register("jpackageDistribution") {
    group = "distribution"
    description = "Creates a self-contained application bundle with data files using jpackage"

    dependsOn(tasks.jpackage, tasks.named("copyJpackageData"))

    doLast {
        val jpackageDir = layout.buildDirectory.dir("jpackage").get().asFile
        val appImage = File(jpackageDir, "Portfolio Helper")

        if (appImage.exists() && appImage.isDirectory) {
            println("✓ App image created: ${appImage.absolutePath}")
            println("  Contents:")
            appImage.listFiles()?.forEach { file ->
                println("    - ${file.name}")
            }
        } else {
            println("✗ No app image found in ${jpackageDir.absolutePath}")
        }
    }
}
