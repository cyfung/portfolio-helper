plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    application
    id("com.gradleup.shadow") version "8.3.5"
    id("edu.sc.seis.launch4j") version "4.0.0"
    id("org.panteleyev.jpackageplugin") version "1.7.6"
}

group = "com.portfoliohelper"
version = "0.2.5"

repositories {
    mavenCentral()
}

dependencies {
    // Ktor Server
    implementation("io.ktor:ktor-server-core:3.4.0")
    implementation("io.ktor:ktor-server-netty:3.4.0")
    implementation("io.ktor:ktor-server-html-builder:3.4.0")

    // Ktor HTTP Client for Yahoo Finance API
    implementation("io.ktor:ktor-client-core:3.4.0")
    implementation("io.ktor:ktor-client-cio:3.4.0")

    // kotlinx.html for HTML DSL
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.11.0")

    // Apache Commons CSV for parsing
    implementation("org.apache.commons:commons-csv:1.10.0")

    // Kotlinx Serialization for JSON parsing
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // Jsoup for HTML parsing (NAV scraping from fund provider websites)
    implementation("org.jsoup:jsoup:1.17.2")

    // Kotlin Coroutines (for async/parallel fetching)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.32")

    // Kotlin standard library
    implementation(kotlin("stdlib"))
}

application {
    mainClass.set("com.portfoliohelper.ApplicationKt")
}

// Java Toolchain Configuration (replaces sourceCompatibility/targetCompatibility)
kotlin {
    jvmToolchain(17)
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

val copyJar = tasks.register("copyJar", Copy::class) {
    dependsOn(tasks.shadowJar)
    val dir = layout.buildDirectory.dir("latest-lib")
    delete(dir)
    from(tasks.shadowJar).into(dir)
}

// jpackage Configuration using Petr Panteleyev plugin
// Creates an app image (portable application bundle) instead of an installer
tasks.jpackage {
    dependsOn(tasks.shadowJar, copyJar)

    // Input: directory containing the shadow JAR
    input.set(layout.buildDirectory.dir("latest-lib"))
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

// Copy config files into jpackage output (data/ is generated at runtime on first run)
tasks.register<Copy>("copyJpackageData") {
    dependsOn(tasks.jpackage)

    from("src/main/resources") {
        include("logback.xml")
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
    description = "Creates a portable ZIP distribution with shadow JAR and config (data/ generated at runtime)"
    archiveBaseName.set("${project.name}-portable")
    archiveClassifier.set("complete")

    from(tasks.shadowJar) {
        into("${project.name}")
    }

    from("docs") {
        into("${project.name}")
        include("RUNNING.md")
        rename("RUNNING.md", "README.md")
    }

    from("src/main/resources") {
        into("${project.name}/config")
        include("logback.xml")
    }
}

tasks.register<Tar>("portableDistTar") {
    group = "distribution"
    description = "Creates a portable TAR.GZ distribution with shadow JAR and config (data/ generated at runtime)"
    archiveBaseName.set("${project.name}-portable")
    archiveClassifier.set("complete")
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")

    from(tasks.shadowJar) {
        into("${project.name}")
    }

    from("docs") {
        into("${project.name}")
        include("RUNNING.md")
        rename("RUNNING.md", "README.md")
    }

    from("src/main/resources") {
        into("${project.name}/config")
        include("logback.xml")
    }
}

tasks.register<Zip>("windowsDistZip") {
    group = "distribution"
    description = "Creates a Windows distribution with EXE launcher (data/ generated at runtime)"
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

    from("docs") {
        into("${project.name}")
        include("RUNNING.md")
        rename("RUNNING.md", "README.md")
    }

    from("src/main/resources") {
        into("${project.name}/config")
        include("logback.xml")
    }
}

tasks.register<Zip>("jpackageDistZip") {
    group = "distribution"
    description = "Creates a ZIP of the self-contained jpackage app image (no Java required)"
    archiveBaseName.set("${project.name}-jpackage")
    archiveVersion.set(project.version.toString())

    dependsOn(tasks.named("copyJpackageData"))

    from(layout.buildDirectory.dir("jpackage/Portfolio Helper")) {
        into("Portfolio Helper")
    }
}

// Convenience task for complete jpackage distribution
tasks.register("jpackageDistribution") {
    group = "distribution"
    description = "Creates a self-contained application bundle with data files using jpackage"

    dependsOn(tasks.jpackage, tasks.named("copyJpackageData"), tasks.named("jpackageDistZip"))

    doLast {
        val jpackageDir = layout.buildDirectory.dir("jpackage").get().asFile
        val appImage = File(jpackageDir, "Portfolio Helper")
        val zipFile = layout.buildDirectory.dir("distributions").get().asFile
            .listFiles { f -> f.name.startsWith("${project.name}-jpackage") && f.name.endsWith(".zip") }
            ?.firstOrNull()

        if (appImage.exists()) {
            println("✓ App image: ${appImage.absolutePath}")
        }
        if (zipFile != null) {
            println("✓ ZIP created: ${zipFile.absolutePath}")
        }
    }
}
