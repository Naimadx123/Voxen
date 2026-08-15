plugins {
    kotlin("jvm")
    `maven-publish`
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    compileOnly("org.jetbrains:annotations:26.0.2")
}

kotlin {
    jvmToolchain(21)
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("api") {
            from(components["java"])
            artifactId = "voxen-api"
        }
    }
    repositories {
        maven {
            name = "vao"
            val releases = "https://repo.vao.zone/releases"
            val snapshots = "https://repo.vao.zone/snapshots"
            url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshots else releases)
            credentials {
                username = providers.gradleProperty("vaoUsername")
                    .orElse(providers.environmentVariable("VAO_REPO_USERNAME")).orNull
                password = providers.gradleProperty("vaoPassword")
                    .orElse(providers.environmentVariable("VAO_REPO_PASSWORD")).orNull
            }
        }
    }
}
