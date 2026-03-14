import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("java")
    id("jacoco")
    id("com.gradleup.shadow") version "8.3.6"
}

group = "com.llexsimulator"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

val aeronVersion     = "1.44.0"
val agronaVersion    = "1.22.0"
val artioVersion     = "0.154"
val sbeVersion       = "1.30.0"
val disruptorVersion = "4.0.0"
val vertxVersion     = "4.5.10"
val quickfixVersion  = "2.3.1"

// Separate classpath used only for SBE code-gen; not added to the compile scope
val sbeCodegen: Configuration by configurations.creating
val artioCodegen: Configuration by configurations.creating
val fixDictionarySpec: Configuration by configurations.creating

repositories {
    mavenCentral()
}

configure<JacocoPluginExtension> {
    toolVersion = "0.8.12"
}

dependencies {
    // ── Artio — low-latency FIX engine ───────────────────────────────────────
    implementation("uk.co.real-logic:artio-codecs:$artioVersion")
    implementation("uk.co.real-logic:artio-core:$artioVersion")
    implementation("org.quickfixj:quickfixj-core:$quickfixVersion")
    implementation("org.quickfixj:quickfixj-messages-fix44:$quickfixVersion")
    artioCodegen("uk.co.real-logic:artio-codecs:$artioVersion")
    artioCodegen("org.agrona:agrona:$agronaVersion")
    artioCodegen("uk.co.real-logic:sbe-tool:1.32.1")
    fixDictionarySpec("org.quickfixj:quickfixj-messages-fix44:$quickfixVersion") {
        isTransitive = false
    }

    // ── Aeron IPC transport (metrics pipeline) ─────────────────────────────────
    implementation("io.aeron:aeron-driver:1.45.0")
    implementation("io.aeron:aeron-client:1.45.0")

    // ── Agrona — off-heap data structures ──────────────────────────────────────
    implementation("org.agrona:agrona:$agronaVersion")

    // ── SBE — flyweight binary codec for internal messages ─────────────────────
    implementation("uk.co.real-logic:sbe-all:$sbeVersion")
    sbeCodegen("uk.co.real-logic:sbe-all:$sbeVersion")

    // ── LMAX Disruptor — lock-free order processing ring buffer ────────────────
    implementation("com.lmax:disruptor:$disruptorVersion")

    // ── Vert.x — async web server, REST API, WebSocket ────────────────────────
    implementation(platform("io.vertx:vertx-stack-depchain:$vertxVersion"))
    implementation("io.vertx:vertx-core")
    implementation("io.vertx:vertx-web")

    // ── Latency histograms (p50 / p99 / p999) ──────────────────────────────────
    implementation("org.hdrhistogram:HdrHistogram:2.2.2")

    // ── JSON serialization (used in REST/WebSocket layer only) ─────────────────
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")

    // ── Logging — SLF4J 2 + Log4j2 fully-async (LMAX Disruptor ring buffer) ───
    // log4j-slf4j2-impl bridges SLF4J 2.x calls to Log4j2.
    // LMAX Disruptor (already on classpath) is picked up automatically by Log4j2
    // when AsyncLoggerContextSelector is active, giving lock-free logging on
    // the hot path with near-zero allocation.
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("org.apache.logging.log4j:log4j-core:2.24.3")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.24.3")

    // ── Testing ────────────────────────────────────────────────────────────────
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.vertx:vertx-junit5")
}

// ── SBE Code Generation ────────────────────────────────────────────────────────
val sbeOutputDir = layout.buildDirectory.dir("generated/sources/sbe/main/java")
val artioFixSpecDir = layout.buildDirectory.dir("generated/resources/artio-fix")
val artioOutputDir = layout.buildDirectory.dir("generated/sources/artio/main/java")

val extractArtioFixDictionary by tasks.registering(Sync::class) {
    group = "build"
    description = "Extract the FIX44 XML dictionary used as the Artio codec-generation input"
    from(zipTree(fixDictionarySpec.singleFile)) {
        include("**/FIX44.xml")
        eachFile { path = name }
        includeEmptyDirs = false
    }
    into(artioFixSpecDir)
}

val generateArtioSources by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Generate Artio FIX44 dictionary codecs from the FIX XML specification"
    dependsOn(extractArtioFixDictionary)
    classpath = artioCodegen
    mainClass.set("uk.co.real_logic.artio.dictionary.CodecGenerationTool")
    args(
        artioOutputDir.get().asFile.absolutePath,
        artioFixSpecDir.get().file("FIX44.xml").asFile.absolutePath
    )
    doFirst {
        artioOutputDir.get().asFile.mkdirs()
    }
}

val generateSbeSources by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Generate SBE flyweight codec classes from fix-messages.xml"
    classpath = sbeCodegen
    mainClass.set("uk.co.real_logic.sbe.SbeTool")
    args = listOf("src/main/resources/sbe/fix-messages.xml")
    systemProperties(
        mapOf(
            "sbe.output.dir"               to sbeOutputDir.get().asFile.absolutePath,
            "sbe.target.language"          to "Java",
            "sbe.target.namespace"         to "com.llexsimulator.sbe",
            "sbe.validation.stop.on.error" to "true",
            "sbe.validation.warnings.fatal" to "false",
            "sbe.java.generate.interfaces" to "true"
        )
    )
    doFirst {
        sbeOutputDir.get().asFile.mkdirs()
    }
}

sourceSets {
    main {
        java {
            srcDir(sbeOutputDir)
            srcDir(artioOutputDir)
        }
    }
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.compileJava {
    dependsOn(generateSbeSources, generateArtioSources)
    options.compilerArgs.addAll(
        listOf("--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED")
    )
}

tasks.withType<ShadowJar> {
    archiveClassifier.set("")
    manifest {
        attributes["Main-Class"] = "com.llexsimulator.Main"
    }
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED"
    )
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}