plugins {
    java
}

group = "net.mtree"
version = "0.6.0-stage6"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
}

dependencies {
    // Paper API for the exact target version. NOT bundled into the jar (server provides it).
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // Paper already ships Gson at runtime; compileOnly keeps the jar small.
    compileOnly("com.google.code.gson:gson:2.11.0")

    // Tests cover the Bukkit-free core only (compileOnly deps are NOT on the test classpath on purpose).
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.46.1.3")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.add("-Xlint:all,-processing")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand(mapOf("version" to project.version.toString()))
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = false }
}
