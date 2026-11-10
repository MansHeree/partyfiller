plugins {
    // Minecraft 26.x ships deobfuscated, so plain loom with no mappings() step (same as
    // PackDisabler's non-obfuscated path). Pre-26 versions would need fabric-loom-remap.
    id("net.fabricmc.fabric-loom") version "1.15-SNAPSHOT"
    java
}

group = "com.filler"
version = "1.0.0"
base { archivesName.set("partyfiller") }

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

dependencies {
    // Non-obfuscated env: plain `implementation`, not `modImplementation` (no remap needed).
    "minecraft"("com.mojang:minecraft:26.1.2")
    "implementation"("net.fabricmc:fabric-loader:0.19.3")
    "implementation"("net.fabricmc.fabric-api:fabric-api:0.155.2+26.1.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
