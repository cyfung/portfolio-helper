import com.github.jk1.license.render.ReportRenderer
import com.github.jk1.license.render.TextReportRenderer
import org.gradle.api.tasks.bundling.Jar
import com.github.jk1.license.render.InventoryHtmlReportRenderer
import org.panteleyev.jpackage.ImageType
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Properties

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    application
    id("com.gradleup.shadow") version "8.3.5"
    id("edu.sc.seis.launch4j") version "4.0.0"
    id("org.panteleyev.jpackageplugin") version "1.7.6"
    id("com.github.jk1.dependency-license-report") version "2.9"
}

group = "com.portfoliohelper"
version = "0.4.8"

repositories {
    mavenCentral()
}

dependencies {
    // Ktor Server
    implementation("io.ktor:ktor-server-core:3.4.0")
    implementation("io.ktor:ktor-server-netty:3.4.0")
    implementation("io.ktor:ktor-server-html-builder:3.4.0")
    implementation("io.ktor:ktor-server-sse:3.4.0")

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

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // mDNS for Android Sync
    implementation("org.jmdns:jmdns:3.6.3")

    // DB schema, table definitions, and initialization
    implementation(project(":db-schema"))

    // TLS — Bouncy Castle for self-signed cert generation
    implementation("org.bouncycastle:bcpkix-jdk18on:1.83")

    // Native BoringSSL — replaces JDK TLS in Netty for fast HTTPS handshakes
    implementation("io.netty:netty-tcnative-boringssl-static:2.0.75.Final")

    implementation(project(":tws-client"))
    testImplementation(kotlin("test"))
    testImplementation(project(":db-schema"))
}

application {
    mainClass.set("com.portfoliohelper.ApplicationKt")
    applicationDefaultJvmArgs = listOf("-Djava.net.preferIPv4Stack=true")
}

// Java Toolchain Configuration (replaces sourceCompatibility/targetCompatibility)
kotlin {
    jvmToolchain(17)
}

// Kotlin will automatically use the Java toolchain
// No need for explicit kotlinOptions.jvmTarget when using toolchains

// Generate AppVersion.kt so the version constant is always in sync with build.gradle.kts
val generateVersionFile: TaskProvider<Task?> = tasks.register("generateVersionFile") {
    val outputDir = layout.buildDirectory.dir("generated/version")
    outputs.dir(outputDir)
    inputs.property("version", version)
    doLast {
        val file = outputDir.get().file("com/portfoliohelper/AppVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package com.portfoliohelper\n\ninternal const val APP_VERSION = \"${version}\"\n")
    }
}
tasks.named("compileKotlin") { dependsOn(generateVersionFile) }
kotlin.sourceSets.main { kotlin.srcDir(layout.buildDirectory.dir("generated/version")) }

// Generate bundled app.db from DBBuilder
val generateAppDb = tasks.register<JavaExec>("generateAppDb") {
    group = "build"
    description = "Generates the bundled app.db SQLite database via DBBuilder"

    val dbSchema = project(":db-schema")
    val dbJarTask = dbSchema.tasks.named<Jar>("jar")

    // classpath is @Classpath-annotated on JavaExec — both entries are tracked as inputs,
    // and the Provider<RegularFile> from dbJarTask implicitly adds a task dependency on :db-schema:jar.
    classpath(
        dbSchema.configurations.named("runtimeClasspath"),
        dbJarTask.map { it.archiveFile }
    )
    mainClass.set("com.portfoliohelper.service.db.DBBuilderKt")

    val outFile = layout.buildDirectory.file("generated/db/data/app.db")
    outputs.file(outFile)
    argumentProviders.add(CommandLineArgumentProvider { listOf(outFile.get().asFile.absolutePath) })
    doFirst { outFile.get().asFile.parentFile.mkdirs() }
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated/db")) }
tasks.named("processResources") { dependsOn(generateAppDb) }

// Shadow JAR Configuration
tasks {
    shadowJar {
        archiveBaseName.set("portfolio-helper")
        archiveClassifier.set("all")
        archiveVersion.set("")

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
    type = ImageType.APP_IMAGE

    // JVM arguments for Netty/Ktor
    javaOptions = listOf(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        "-Dfile.encoding=UTF-8",
        "-Djava.net.preferIPv4Stack=true"
    )

    // Platform-specific icons
    icon.set(file("${projectDir}/src/main/resources/static/favicon.ico"))
}

// Copy config files into jpackage output (data/ is generated at runtime on first run)
tasks.register<Copy>("copyJpackageData") {
    dependsOn(tasks.jpackage, tasks.named("generateLicenseReport"))

    from("src/main/resources") {
        include("logback.xml")
        into("config")
    }

    from(layout.buildDirectory.file("reports/dependency-license/THIRD_PARTY_NOTICES.txt"))

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
    icon.set("${projectDir}/src/main/resources/static/images/favicon.ico")
    setJarTask(tasks.shadowJar.get())
    jvmOptions = listOf("-Djava.net.preferIPv4Stack=true")
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
    description =
        "Creates a portable ZIP distribution with shadow JAR and config (data/ generated at runtime)"
    archiveBaseName.set("${project.name}-portable")
    archiveClassifier.set("complete")

    dependsOn(tasks.named("generateLicenseReport"))

    from(layout.buildDirectory.file("reports/dependency-license/THIRD_PARTY_NOTICES.txt")) {
        into(project.name)
    }

    from(tasks.shadowJar) {
        into(project.name)
    }

    from("docs") {
        into(project.name)
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
    description =
        "Creates a portable TAR.GZ distribution with shadow JAR and config (data/ generated at runtime)"
    archiveBaseName.set("${project.name}-portable")
    archiveClassifier.set("complete")
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")

    dependsOn(tasks.named("generateLicenseReport"))

    from(layout.buildDirectory.file("reports/dependency-license/THIRD_PARTY_NOTICES.txt")) {
        into(project.name)
    }

    from(tasks.shadowJar) {
        into(project.name)
    }

    from("docs") {
        into(project.name)
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

    dependsOn(tasks.named("createExe"), tasks.named("generateLicenseReport"))

    from(layout.buildDirectory.file("reports/dependency-license/THIRD_PARTY_NOTICES.txt")) {
        into(project.name)
    }

    from(layout.buildDirectory.dir("launch4j")) {
        into(project.name)
        include("portfolio-helper.exe")
    }

    from(tasks.shadowJar) {
        into("${project.name}/lib")
    }

    from("docs") {
        into(project.name)
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

// License report configuration
licenseReport {
    renderers = arrayOf<ReportRenderer>(
        TextReportRenderer("THIRD_PARTY_NOTICES.txt"),
        InventoryHtmlReportRenderer("index.html", "Third Party Licenses")
    )
    excludeGroups = arrayOf("com.portfoliohelper")
}

// GitHub Release Task
tasks.register("githubRelease") {
    group = "distribution"
    description = "Creates a GitHub release and uploads distribution artifacts. Requires GITHUB_TOKEN env var and a pre-existing git tag matching v{version}."
    dependsOn(tasks.named("jpackageDistZip"), tasks.shadowJar)
    doLast {
        val localProps = Properties().apply {
            rootProject.file("../local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        val token = System.getenv("GITHUB_TOKEN")
            ?: localProps.getProperty("githubToken")
            ?: (project.findProperty("githubToken") as? String)
            ?: error("GITHUB_TOKEN env var or githubToken in local.properties / ~/.gradle/gradle.properties required")
        val repo = System.getenv("GITHUB_REPO")
            ?: localProps.getProperty("githubRepo")
            ?: (project.findProperty("githubRepo") as? String)
            ?: error("GITHUB_REPO env var or githubRepo in local.properties / ~/.gradle/gradle.properties required")
        val ver = project.version.toString()
        val tagName = "v$ver"

        fun String.escapeJson() = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

        val releaseBody = """## Portfolio Helper $tagName

### Downloads
- **Self-contained app** (no Java required): `portfolio-helper-jpackage-$ver.zip`"""

        // Step 1: Create release
        println("Creating GitHub release $tagName on $repo...")
        val releasePayload = """{"tag_name":"$tagName","name":"Portfolio Helper $tagName","body":"${releaseBody.escapeJson()}","draft":false,"prerelease":false}"""
        val releaseConn = URI("https://api.github.com/repos/$repo/releases").toURL().openConnection() as HttpURLConnection
        releaseConn.requestMethod = "POST"
        releaseConn.setRequestProperty("Authorization", "Bearer $token")
        releaseConn.setRequestProperty("Content-Type", "application/json")
        releaseConn.setRequestProperty("Accept", "application/vnd.github+json")
        releaseConn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        releaseConn.doOutput = true
        releaseConn.outputStream.use { it.write(releasePayload.toByteArray(Charsets.UTF_8)) }

        val releaseCode = releaseConn.responseCode
        val releaseText = if (releaseCode in 200..299)
            releaseConn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        else
            releaseConn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

        if (releaseCode !in 200..299) error("Failed to create release (HTTP $releaseCode): $releaseText")

        val uploadUrlTemplate = Regex("\"upload_url\"\\s*:\\s*\"([^\"]+)\"").find(releaseText)?.groupValues?.get(1)
            ?: error("No upload_url in response: $releaseText")
        val uploadBaseUrl = uploadUrlTemplate.substringBefore("{")
        println("Release created. Uploading artifacts...")

        // Step 2: Upload artifacts
        data class Artifact(val file: File, val contentType: String, val uploadName: String)
        val distDir = layout.buildDirectory.dir("distributions").get().asFile
        val shadowJarFile = tasks.shadowJar.get().archiveFile.get().asFile
        val artifacts = listOf(
            Artifact(File(distDir, "${project.name}-jpackage-$ver.zip"), "application/zip", "${project.name}-jpackage-$ver.zip"),
            Artifact(shadowJarFile, "application/java-archive", "${project.name}-jpackage-$ver.jar")
        )
        for ((file, contentType, uploadName) in artifacts) {
            if (!file.exists()) error("Artifact not found: ${file.absolutePath}")
            println("Uploading $uploadName (${"%.1f".format(file.length() / 1024.0 / 1024.0)} MB)...")
            val uploadUrl = URI("$uploadBaseUrl?name=${URLEncoder.encode(uploadName, "UTF-8")}").toURL()
            val uploadConn = uploadUrl.openConnection() as HttpURLConnection
            uploadConn.requestMethod = "POST"
            uploadConn.setRequestProperty("Authorization", "Bearer $token")
            uploadConn.setRequestProperty("Content-Type", contentType)
            uploadConn.setRequestProperty("Accept", "application/vnd.github+json")
            uploadConn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            uploadConn.doOutput = true
            uploadConn.setFixedLengthStreamingMode(file.length())
            uploadConn.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
            val upCode = uploadConn.responseCode
            if (upCode !in 200..299) {
                val err = uploadConn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                error("Failed to upload $uploadName (HTTP $upCode): $err")
            }
            println("  Uploaded $uploadName")
        }
        println("GitHub release $tagName created successfully!")
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
