plugins {
    kotlin("jvm")
}

group = "com.portfoliohelper"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    api("org.jetbrains.exposed:exposed-core:0.61.0")
    api("org.jetbrains.exposed:exposed-jdbc:0.61.0")
    api("org.xerial:sqlite-jdbc:3.47.2.0")
    implementation("ch.qos.logback:logback-classic:1.5.32")
    implementation("org.flywaydb:flyway-core:12.1.1")
}

kotlin {
    jvmToolchain(17)
}
