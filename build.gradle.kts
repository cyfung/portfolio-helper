import com.github.jk1.license.render.ReportRenderer
import com.github.jk1.license.render.TextReportRenderer
import com.github.gradle.node.npm.task.NpmTask
import org.gradle.api.file.CopySpec
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
    id("com.github.node-gradle.node") version "7.1.0"
}

group = "com.portfoliohelper"
version = "0.8.23"

repositories {
    mavenCentral()
}

node {
    version.set("22.11.0")
    download.set(true)
    nodeProjectDir.set(file("frontend"))
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

    // XML serialization for IBKR Flex Query parsing
    implementation("io.github.pdvrieze.xmlutil:serialization-jvm:0.86.3")

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

    // DB migrations
    implementation("org.flywaydb:flyway-core:12.1.1")

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
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })

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
    inputs.files(dbSchema.fileTree("src/main/kotlin"))
        .withPropertyName("dbSchemaKotlinSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(dbSchema.fileTree("src/main/resources/db/migration"))
        .withPropertyName("dbSchemaMigrations")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    argumentProviders.add(CommandLineArgumentProvider { listOf(outFile.get().asFile.absolutePath) })
    doFirst { outFile.get().asFile.parentFile.mkdirs() }
}
sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated/db"))
    resources.srcDir(layout.buildDirectory.dir("generated/frontend"))
}
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
    icon.set(file("${projectDir}/frontend/public/favicon.ico"))
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
    icon.set("${projectDir}/frontend/public/favicon.ico")
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
fun CopySpec.includeThirdPartyNotices(destinationDir: String) {
    from(layout.buildDirectory.file("reports/dependency-license/THIRD_PARTY_NOTICES.txt")) {
        into(destinationDir)
    }
}

fun CopySpec.includeRuntimeReadme(destinationDir: String) {
    from("docs") {
        into(destinationDir)
        include("RUNNING.md")
        rename("RUNNING.md", "README.md")
    }
}

fun CopySpec.includeLogbackConfig(destinationDir: String) {
    from("src/main/resources") {
        into("$destinationDir/config")
        include("logback.xml")
    }
}

fun CopySpec.includePortableDistributionContents(destinationDir: String) {
    includeThirdPartyNotices(destinationDir)
    from(tasks.shadowJar) {
        into(destinationDir)
    }
    includeRuntimeReadme(destinationDir)
    includeLogbackConfig(destinationDir)
}

tasks.register<Zip>("portableDistZip") {
    group = "distribution"
    description =
        "Creates a portable ZIP distribution with shadow JAR and config (data/ generated at runtime)"
    archiveBaseName.set("${project.name}-portable")
    archiveClassifier.set("complete")

    dependsOn(tasks.named("generateLicenseReport"))
    includePortableDistributionContents(project.name)
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
    includePortableDistributionContents(project.name)
}

tasks.register<Zip>("windowsDistZip") {
    group = "distribution"
    description = "Creates a Windows distribution with EXE launcher (data/ generated at runtime)"
    archiveBaseName.set("${project.name}-windows")
    archiveClassifier.set("exe")

    dependsOn(tasks.named("createExe"), tasks.named("generateLicenseReport"))

    includeThirdPartyNotices(project.name)
    from(layout.buildDirectory.dir("launch4j")) {
        into(project.name)
        include("portfolio-helper.exe")
    }
    from(tasks.shadowJar) {
        into("${project.name}/lib")
    }
    includeRuntimeReadme(project.name)
    includeLogbackConfig(project.name)
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

data class GithubReleaseConfig(
    val token: String,
    val repo: String,
    val version: String,
    val tagName: String
)

data class GithubReleaseArtifact(
    val file: File,
    val contentType: String,
    val uploadName: String
)

fun loadLocalReleaseProperties(): Properties =
    Properties().apply {
        rootProject.file("../local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }

fun releaseSetting(
    envName: String,
    localProps: Properties,
    localName: String,
    gradleName: String,
    missingMessage: String
): String =
    System.getenv(envName)
        ?: localProps.getProperty(localName)
        ?: (project.findProperty(gradleName) as? String)
        ?: error(missingMessage)

fun githubReleaseConfig(): GithubReleaseConfig {
    val localProps = loadLocalReleaseProperties()
    val version = project.version.toString()
    return GithubReleaseConfig(
        token = releaseSetting(
            envName = "GITHUB_TOKEN",
            localProps = localProps,
            localName = "githubToken",
            gradleName = "githubToken",
            missingMessage = "GITHUB_TOKEN env var or githubToken in local.properties / ~/.gradle/gradle.properties required"
        ),
        repo = releaseSetting(
            envName = "GITHUB_REPO",
            localProps = localProps,
            localName = "githubRepo",
            gradleName = "githubRepo",
            missingMessage = "GITHUB_REPO env var or githubRepo in local.properties / ~/.gradle/gradle.properties required"
        ),
        version = version,
        tagName = "v$version"
    )
}

fun String.escapeJson(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

fun releaseBody(config: GithubReleaseConfig): String =
    """## Portfolio Helper ${config.tagName}

### Downloads
- **Self-contained app** (no Java required): `portfolio-helper-jpackage-${config.version}.zip`"""

fun HttpURLConnection.githubApiDefaults(config: GithubReleaseConfig, contentType: String) {
    setRequestProperty("Authorization", "Bearer ${config.token}")
    setRequestProperty("Content-Type", contentType)
    setRequestProperty("Accept", "application/vnd.github+json")
    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
}

fun HttpURLConnection.responseText(): String =
    if (responseCode in 200..299) {
        inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    } else {
        errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
    }

fun createGithubRelease(config: GithubReleaseConfig): String {
    val payload = """{"tag_name":"${config.tagName}","name":"Portfolio Helper ${config.tagName}","body":"${releaseBody(config).escapeJson()}","draft":false,"prerelease":false}"""
    val conn = URI("https://api.github.com/repos/${config.repo}/releases").toURL().openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.githubApiDefaults(config, "application/json")
    conn.doOutput = true
    conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

    val code = conn.responseCode
    val text = conn.responseText()
    if (code !in 200..299) error("Failed to create release (HTTP $code): $text")
    return text
}

fun uploadBaseUrlFromRelease(responseText: String): String =
    Regex("\"upload_url\"\\s*:\\s*\"([^\"]+)\"").find(responseText)?.groupValues?.get(1)
        ?.substringBefore("{")
        ?: error("No upload_url in response: $responseText")

fun githubReleaseArtifacts(config: GithubReleaseConfig): List<GithubReleaseArtifact> {
    val distDir = layout.buildDirectory.dir("distributions").get().asFile
    val shadowJarFile = tasks.shadowJar.get().archiveFile.get().asFile
    return listOf(
        GithubReleaseArtifact(
            File(distDir, "${project.name}-jpackage-${config.version}.zip"),
            "application/zip",
            "${project.name}-jpackage-${config.version}.zip"
        ),
        GithubReleaseArtifact(
            shadowJarFile,
            "application/java-archive",
            "${project.name}-jpackage-${config.version}.jar"
        )
    )
}

fun uploadGithubReleaseArtifact(
    config: GithubReleaseConfig,
    uploadBaseUrl: String,
    artifact: GithubReleaseArtifact
) {
    val (file, contentType, uploadName) = artifact
    if (!file.exists()) error("Artifact not found: ${file.absolutePath}")
    println("Uploading $uploadName (${"%.1f".format(file.length() / 1024.0 / 1024.0)} MB)...")

    val uploadUrl = URI("$uploadBaseUrl?name=${URLEncoder.encode(uploadName, "UTF-8")}").toURL()
    val conn = uploadUrl.openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.githubApiDefaults(config, contentType)
    conn.doOutput = true
    conn.setFixedLengthStreamingMode(file.length())
    conn.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }

    val code = conn.responseCode
    if (code !in 200..299) {
        error("Failed to upload $uploadName (HTTP $code): ${conn.responseText()}")
    }
    println("  Uploaded $uploadName")
}

// GitHub Release Task
tasks.register("githubRelease") {
    group = "distribution"
    description = "Creates a GitHub release and uploads distribution artifacts. Requires GITHUB_TOKEN env var and a pre-existing git tag matching v{version}."
    dependsOn(tasks.named("jpackageDistZip"), tasks.shadowJar)
    doLast {
        val config = githubReleaseConfig()
        println("Creating GitHub release ${config.tagName} on ${config.repo}...")
        val uploadBaseUrl = uploadBaseUrlFromRelease(createGithubRelease(config))
        println("Release created. Uploading artifacts...")

        for (artifact in githubReleaseArtifacts(config)) {
            uploadGithubReleaseArtifact(config, uploadBaseUrl, artifact)
        }
        println("GitHub release ${config.tagName} created successfully!")
    }
}

// ── Frontend (React SPA) build integration ────────────────────────────────────

/** Install node_modules from the committed lockfile */
val frontendInstall = tasks.register<NpmTask>("frontendInstall") {
    group = "build"
    description = "Installs frontend npm dependencies"
    args.set(listOf("ci"))
    inputs.file("frontend/package.json")
    inputs.file("frontend/package-lock.json")
    outputs.dir("frontend/node_modules")
}

/** Run `npm run build` → outputs to frontend/dist/ */
val frontendBuild = tasks.register<NpmTask>("frontendBuild") {
    dependsOn(frontendInstall)
    group = "build"
    description = "Builds the React SPA with Vite"
    args.set(listOf("run", "build"))
    inputs.dir("frontend/src")
    inputs.dir("frontend/public")
    inputs.file("frontend/index.html")
    inputs.file("frontend/vite.config.ts")
    inputs.file("frontend/tailwind.config.ts")
    outputs.dir(layout.buildDirectory.dir("generated/frontend/static"))
}

tasks.named("processResources") {
    dependsOn(frontendBuild)
}

// ── Convenience task for complete jpackage distribution ───────────────────────
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
