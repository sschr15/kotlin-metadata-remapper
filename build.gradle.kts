import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.0"
}

group = "sschr15.tools"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://maven.quiltmc.org/repository/release/") {
        name = "QuiltMC Releases"
    }
}

val include by configurations.creating
configurations {
    compileClasspath.get().extendsFrom(include)
    runtimeClasspath.get().extendsFrom(include)
}

dependencies {
    include(kotlin("stdlib"))
    include("org.quiltmc:tiny-mappings-parser:0.3.0")
    listOf(
        "asm",
        "asm-commons",
        "asm-util",
        "asm-tree",
        "asm-analysis",
    ).forEach { include("org.ow2.asm:$it:9.2") }
    include("org.jetbrains:annotations:23.0.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.jar {
    from(configurations.runtimeClasspath.get().files.map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/maven/**", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "**/module-info.class")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes("Main-Class" to "sschr15.tools.remapper.KotlinMetadataRemapperKt")
    }
}
