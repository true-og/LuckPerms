import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.kotlin.dsl.provideDelegate

/* ------------------------------ Plugins ------------------------------ */
plugins {
    id("java") // Import Java plugin.
    id("java-library") // Import Java Library plugin.
    id("com.diffplug.spotless") version "7.0.4" // Import Spotless plugin.
    id("com.gradleup.shadow") version "8.3.8" // Import Shadow plugin.
    id("checkstyle") // Import Checkstyle plugin.
    eclipse // Import Eclipse plugin.
    kotlin("jvm") version "2.1.21" // Import Kotlin JVM plugin.
}

extra["kotlinAttribute"] = Attribute.of("kotlin-tag", Boolean::class.javaObjectType)

val kotlinAttribute: Attribute<Boolean> by rootProject.extra

/* --------------------------- JDK / Kotlin ---------------------------- */
java {
    sourceCompatibility = JavaVersion.VERSION_17 // Compile with JDK 17 compatibility.
    toolchain { // Select Java toolchain.
        languageVersion.set(JavaLanguageVersion.of(17)) // Use JDK 17.
        vendor.set(JvmVendorSpec.GRAAL_VM) // Use GraalVM CE.
    }
}

kotlin { jvmToolchain(17) }

/* ----------------------------- Metadata ------------------------------ */
group = "me.lucko.luckperms" // Declare bundle identifier.

version = "5.5-SNAPSHOT" // Declare plugin version (will be in .jar).

val apiVersion = "1.19" // Declare minecraft server target version.

/* ----------------------------- Resources ----------------------------- */
tasks.named<ProcessResources>("processResources") {
    val props = mapOf("version" to version, "apiVersion" to apiVersion)
    inputs.properties(props) // Indicates to rerun if version changes.
    filesMatching("plugin.yml") { expand(props) }
    from("LICENSE") { into("/") } // Bundle licenses into jarfiles.
}

/* ---------------------------- Repos ---------------------------------- */
repositories {
    // Fix issue with lwjgl-freetype not being found on macOS / ForgeGradle issue
    maven("https://libraries.minecraft.net") { content { includeModule("org.lwjgl", "lwjgl-freetype") } }
    mavenCentral()
    maven { url = uri("https://repo.lucko.me/") }
    maven { url = uri("https://libraries.minecraft.net/") }
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
    maven { url = uri("file://${System.getProperty("user.home")}/.m2/repository") }
    System.getProperty("SELF_MAVEN_LOCAL_REPO")?.let {
        val dir = file(it)
        if (dir.isDirectory) {
            println("Using SELF_MAVEN_LOCAL_REPO at: $it")
            maven { url = uri("file://${dir.absolutePath}") }
        } else {
            mavenLocal()
        }
    }
}

/* ---------------------- Java project deps ---------------------------- */
dependencies {
    // Root project carries no direct compile deps; subprojects declare their own.
}

/* ---------------------- Reproducible jars ---------------------------- */
tasks.withType<AbstractArchiveTask>().configureEach { // Ensure reproducible .jars
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

/* ----------------------------- Shadow -------------------------------- */
tasks.shadowJar {
    archiveClassifier.set("") // Use empty string instead of null.
    minimize()
}

tasks.jar { archiveClassifier.set("part") } // Applies to root jarfile only.

tasks.build { dependsOn(tasks.spotlessApply) } // Build depends on spotless and shadow.

/* --------------------------- Javac opts ------------------------------- */
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters") // Enable reflection for java code.
    options.isFork = true // Run javac in its own process.
    options.compilerArgs.add("-Xlint:deprecation") // Trigger deprecation warning messages.
    options.encoding = "UTF-8" // Use UTF-8 file encoding.
}

/* ----------------------------- Auto Formatting ------------------------ */
spotless {
    java {
        eclipse().configFile("config/formatter/eclipse-java-formatter.xml") // Eclipse java formatting.
        leadingTabsToSpaces() // Convert leftover leading tabs to spaces.
        removeUnusedImports() // Remove imports that aren't being called.
    }
    kotlinGradle {
        ktfmt().kotlinlangStyle().configure { it.setMaxWidth(120) } // JetBrains Kotlin formatting.
        target("build.gradle.kts", "settings.gradle.kts") // Gradle files to format.
    }
}

checkstyle {
    toolVersion = "10.18.1" // Declare checkstyle version to use.
    configFile = file("config/checkstyle/checkstyle.xml") // Point checkstyle to config file.
    isIgnoreFailures = true // Don't fail the build if checkstyle does not pass.
    isShowViolations = true // Show the violations in any IDE with the checkstyle plugin.
}

tasks.named("compileJava") {
    dependsOn("spotlessApply") // Run spotless before compiling with the JDK.
}

tasks.named("spotlessCheck") {
    dependsOn("spotlessApply") // Run spotless before checking if spotless ran.
}

/* --------------------------- LuckPerms modules ------------------------ */

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "eclipse")

    group = "me.lucko.luckperms"
    version = rootProject.version

    repositories {
        maven("https://libraries.minecraft.net") { content { includeModule("org.lwjgl", "lwjgl-freetype") } }
        mavenCentral()
        maven { url = uri("https://repo.lucko.me/") }
        maven { url = uri("https://libraries.minecraft.net/") }
        maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
        maven { url = uri("file://${System.getProperty("user.home")}/.m2/repository") }
        System.getProperty("SELF_MAVEN_LOCAL_REPO")?.let {
            val dir = file(it)
            if (dir.isDirectory) {
                println("Using SELF_MAVEN_LOCAL_REPO at: $it")
                maven { url = uri("file://${dir.absolutePath}") }
            } else {
                mavenLocal()
            }
        }
    }

    tasks.withType<Test>().configureEach {
        testLogging {
            events("PASSED", "FAILED", "SKIPPED")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
        useJUnitPlatform()
    }

    tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }

    tasks.withType<Jar>().configureEach { from(rootProject.file("LICENSE.txt")) }
}

/* :api */
project(":api") {
    dependencies {
        compileOnly("org.checkerframework:checker-qual:3.49.3")
        compileOnly("org.jetbrains:annotations:26.0.2")
    }
    tasks.jar { manifest { attributes(mapOf("Automatic-Module-Name" to "net.luckperms.api")) } }
}

/* :common */
project(":common") {
    apply(plugin = "jacoco")

    val patchedDir = layout.buildDirectory.dir("patchedSrc/commonMain/java")
    val patchCommonForJ17 =
        tasks.register<Copy>("patchCommonForJ17") {
            from("src/main/java")
            into(patchedDir)
            filteringCharset = "UTF-8"
            filesMatching("**/*.java") {
                filter { line ->
                    if (
                        line.contains(".reversed()") &&
                            !line.contains("Comparator") &&
                            !line.contains("comparing") &&
                            !line.contains("thenComparing") &&
                            !line.contains("INSTANCE") &&
                            !line.contains("normal()")
                    ) {
                        line.replace(
                            ".reversed()",
                            ".stream().collect(java.util.stream.Collectors.collectingAndThen(" +
                                "java.util.stream.Collectors.toList(), l -> { java.util.Collections.reverse(l); return l; }))",
                        )
                    } else line
                }
            }
        }

    sourceSets.named("main") {
        java.setSrcDirs(emptyList<Any>())
        java.srcDir(patchedDir)
    }
    tasks.named<JavaCompile>("compileJava") { dependsOn(patchCommonForJ17) }

    dependencies {
        testImplementation("org.slf4j:slf4j-simple:1.7.36")
        testImplementation("org.junit.jupiter:junit-jupiter:5.13.0")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        testImplementation("org.testcontainers:junit-jupiter:1.21.1")
        testImplementation("org.mockito:mockito-core:5.18.0")
        testImplementation("org.mockito:mockito-junit-jupiter:5.18.0")
        testImplementation("com.h2database:h2:2.1.214")
        testImplementation("org.mongodb:mongodb-driver-legacy:5.5.0")
        testImplementation("org.spongepowered:configurate-yaml:3.7.3")
        testImplementation("org.spongepowered:configurate-hocon:3.7.3")
        testImplementation("me.lucko.configurate:configurate-toml:3.7")
        testImplementation("net.luckperms:rest-api-java-client:0.1")

        api(project(":api"))
        api("org.checkerframework:checker-qual:3.12.0")
        compileOnly(project(":common:loader-utils"))
        compileOnly("com.mojang:brigadier:1.0.18")
        compileOnly("org.slf4j:slf4j-api:1.7.30")
        compileOnly("org.apache.logging.log4j:log4j-api:2.14.0")

        api("net.kyori:adventure-api:4.21.0") {
            exclude(module = "adventure-bom")
            exclude(module = "checker-qual")
            exclude(module = "annotations")
        }
        api("net.kyori:adventure-text-serializer-gson:4.21.0") {
            exclude(module = "adventure-bom")
            exclude(module = "adventure-api")
            exclude(module = "gson")
        }
        api("net.kyori:adventure-text-serializer-legacy:4.21.0") {
            exclude(module = "adventure-bom")
            exclude(module = "adventure-api")
        }
        api("net.kyori:adventure-text-serializer-plain:4.21.0") {
            exclude(module = "adventure-bom")
            exclude(module = "adventure-api")
        }
        api("net.kyori:adventure-text-minimessage:4.21.0") {
            exclude(module = "adventure-bom")
            exclude(module = "adventure-api")
        }
        api("net.kyori:event-api:3.0.0") {
            exclude(module = "checker-qual")
            exclude(module = "guava")
        }

        api("com.google.code.gson:gson:2.7")
        api("com.google.guava:guava:19.0")
        api("com.github.ben-manes.caffeine:caffeine:3.2.0")
        api("com.squareup.okhttp3:okhttp:3.14.9")
        api("com.squareup.okio:okio:1.17.6")
        api("net.bytebuddy:byte-buddy:1.15.11")

        api("org.spongepowered:configurate-core:3.7.3") { isTransitive = false }
        api("org.spongepowered:configurate-yaml:3.7.3") { isTransitive = false }
        api("org.spongepowered:configurate-gson:3.7.3") { isTransitive = false }
        api("org.spongepowered:configurate-hocon:3.7.3") { isTransitive = false }
        api("me.lucko.configurate:configurate-toml:3.7") { isTransitive = false }

        compileOnly("com.zaxxer:HikariCP:6.3.0")
        compileOnly("redis.clients:jedis:5.2.0")
        compileOnly("io.nats:jnats:2.21.1")
        compileOnly("com.rabbitmq:amqp-client:5.25.0")
        compileOnly("org.mongodb:mongodb-driver-legacy:5.5.0")
        compileOnly("org.postgresql:postgresql:42.7.6")
        compileOnly("org.yaml:snakeyaml:1.33")
        compileOnly("net.luckperms:rest-api-java-client:0.1")
    }
}

/* :common:loader-utils */
project(":common:loader-utils") {
    // nothing special
}

/* :bukkit */
project(":bukkit") {
    apply(plugin = "com.gradleup.shadow")
    dependencies {
        implementation(project(":common"))
        compileOnly(project(":common:loader-utils"))

        compileOnly("com.destroystokyo.paper:paper-api:1.15.2-R0.1-SNAPSHOT")
        compileOnly("net.kyori:adventure-platform-bukkit:4.4.0") {
            exclude(module = "adventure-bom")
            exclude(module = "adventure-api")
            exclude(module = "adventure-nbt")
        }
        compileOnly("me.lucko:commodore:2.0")
        compileOnly("net.milkbowl.vault:VaultAPI:1.7") { exclude(module = "bukkit") }
        compileOnly("lilypad.client.connect:api:0.0.1-SNAPSHOT")
    }
    tasks.named<ShadowJar>("shadowJar") {
        archiveFileName.set("luckperms-bukkit.jarinjar")
        dependencies { include(dependency("me.lucko.luckperms:.*")) }
        relocate("net.kyori.adventure", "me.lucko.luckperms.lib.adventure")
        relocate("net.kyori.event", "me.lucko.luckperms.lib.eventbus")
        relocate("com.github.benmanes.caffeine", "me.lucko.luckperms.lib.caffeine")
        relocate("okio", "me.lucko.luckperms.lib.okio")
        relocate("okhttp3", "me.lucko.luckperms.lib.okhttp3")
        relocate("net.bytebuddy", "me.lucko.luckperms.lib.bytebuddy")
        relocate("me.lucko.commodore", "me.lucko.luckperms.lib.commodore")
        relocate("org.mariadb.jdbc", "me.lucko.luckperms.lib.mariadb")
        relocate("com.mysql", "me.lucko.luckperms.lib.mysql")
        relocate("org.postgresql", "me.lucko.luckperms.lib.postgresql")
        relocate("com.zaxxer.hikari", "me.lucko.luckperms.lib.hikari")
        relocate("com.mongodb", "me.lucko.luckperms.lib.mongodb")
        relocate("org.bson", "me.lucko.luckperms.lib.bson")
        relocate("redis.clients.jedis", "me.lucko.luckperms.lib.jedis")
        relocate("io.nats.client", "me.lucko.luckperms.lib.nats")
        relocate("com.rabbitmq", "me.lucko.luckperms.lib.rabbitmq")
        relocate("org.apache.commons.pool2", "me.lucko.luckperms.lib.commonspool2")
        relocate("ninja.leaping.configurate", "me.lucko.luckperms.lib.configurate")
    }
}

/* :bukkit:loader */
project(":bukkit:loader") {
    apply(plugin = "com.gradleup.shadow")
    dependencies {
        compileOnly("com.destroystokyo.paper:paper-api:1.15.2-R0.1-SNAPSHOT")
        implementation(project(":api"))
        implementation(project(":common:loader-utils"))
    }
    tasks.processResources {
        filesMatching("plugin.yml") { expand(mapOf("pluginVersion" to rootProject.version.toString())) }
    }
    tasks.named<ShadowJar>("shadowJar") {
        archiveFileName.set("LuckPerms-${rootProject.version}.jar")
        from(project(":bukkit").tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
        manifest { attributes(mapOf("paperweight-mappings-namespace" to "mojang")) }
        destinationDirectory.set(rootProject.layout.buildDirectory.dir("libs"))
    }
}

/* Build root produces the server jar */
tasks.named("build") { dependsOn(project(":bukkit:loader").tasks.named("shadowJar")) }
