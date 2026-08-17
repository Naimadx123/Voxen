plugins {
    kotlin("jvm") version "2.4.20-Beta1"
    id("com.gradleup.shadow") version "9.4.3"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

val kotlinVersion = "2.4.20-Beta1"
val hikariVersion = "6.3.0"
val sqliteVersion = "3.50.1.0"
val mysqlVersion = "9.3.0"
val jedisVersion = "5.2.0"
val natsVersion = "2.20.5"
val amqpVersion = "5.25.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/")
    maven("https://jitpack.io")
}

dependencies {
    implementation(project(":api"))
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("io.github.miniplaceholders:miniplaceholders-api:2.2.3")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly("net.luckperms:api:5.4")
    compileOnly("com.zaxxer:HikariCP:$hikariVersion")
    compileOnly("redis.clients:jedis:$jedisVersion")
    compileOnly("io.nats:jnats:$natsVersion")
    compileOnly("com.rabbitmq:amqp-client:$amqpVersion")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.bstats:bstats-bukkit:3.2.1")

    testImplementation("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("com.zaxxer:HikariCP:$hikariVersion")
    testImplementation("org.xerial:sqlite-jdbc:$sqliteVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

val adventure5Version = "5.2.0"
val adventure5TestClasspath: Configuration by configurations.creating {
    extendsFrom(configurations.testRuntimeClasspath.get())
    resolutionStrategy.eachDependency {
        if (requested.group == "net.kyori" && requested.name.startsWith("adventure-")) {
            useVersion(adventure5Version)
        }
    }
}

val testAdventure5 by tasks.registering(Test::class) {
    description = "Runs the test suite against Adventure $adventure5Version."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].output + sourceSets["main"].output + adventure5TestClasspath
    useJUnitPlatform()
}

tasks {
    shadowJar {
        archiveFileName.set("Voxen-v${project.version}.jar")
        minimize {
            exclude(project(":api"))
        }
        dependencies {
            exclude(dependency("org.jetbrains.kotlin:.*:.*"))
            exclude(dependency("org.jetbrains:annotations:.*"))
        }
        relocate("org.bstats", "${project.group}.voxen.thirdparties.bstats")
    }

    build {
        dependsOn(shadowJar)
    }

    check {
        dependsOn(testAdventure5)
    }

    test {
        useJUnitPlatform()
    }

    runServer {
        minecraftVersion("1.21.8-R0.1-SNAPSHOT")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf(
            "version" to version,
            "kotlinVersion" to kotlinVersion,
            "hikariVersion" to hikariVersion,
            "sqliteVersion" to sqliteVersion,
            "mysqlVersion" to mysqlVersion,
            "jedisVersion" to jedisVersion,
            "natsVersion" to natsVersion,
            "amqpVersion" to amqpVersion,
        )
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
