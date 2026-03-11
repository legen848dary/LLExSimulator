import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
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
val sbeVersion       = "1.30.0"
val disruptorVersion = "4.0.0"
val vertxVersion     = "4.5.10"
val quickfixVersion  = "2.3.1"

// Separate classpath used only for SBE code-gen; not added to the compile scope
val sbeCodegen: Configuration by configurations.creating

repositories {
    mavenCentral()
}

dependencies {
    // ── FIX Engine ─────────────────────────────────────────────────────────────
    // QuickFIX/J: production-grade FIX engine supporting 4.2, 4.4, 5.0, 5.0SP2
    implementation("org.quickfixj:quickfixj-core:$quickfixVersion")
    implementation("org.quickfixj:quickfixj-messages-fix42:$quickfixVersion")
    implementation("org.quickfixj:quickfixj-messages-fix44:$quickfixVersion")
    implementation("org.quickfixj:quickfixj-messages-fix50:$quickfixVersion")
    implementation("org.quickfixj:quickfixj-messages-fix50sp2:$quickfixVersion")
    implementation("org.quickfixj:quickfixj-messages-fixt11:$quickfixVersion")

    // ── Aeron IPC transport (metrics pipeline) ─────────────────────────────────
    implementation("io.aeron:aeron-driver:$aeronVersion")
    implementation("io.aeron:aeron-client:$aeronVersion")

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

    // ── Logging (AsyncAppender, immediateFlush=false) ──────────────────────────
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // ── Testing ────────────────────────────────────────────────────────────────
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.vertx:vertx-junit5")
}

// ── SBE Code Generation ────────────────────────────────────────────────────────
val sbeOutputDir = layout.buildDirectory.dir("generated/sources/sbe/main/java")

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
        }
    }
}

tasks.compileJava {
    dependsOn(generateSbeSources)
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
}