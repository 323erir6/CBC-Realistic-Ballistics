plugins {
    java
    id("net.neoforged.moddev") version "2.0.80"
}

version = property("mod_version")!!
group = "dev.cbcrealisticballistics"

base {
    archivesName.set("cbc-realistic-ballistics")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
}

neoForge {
    version = property("neo_version") as String
    mods {
        create("cbc_realistic_ballistics") {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    compileOnly(files("../_deps_CreateBigCannons/create-1.21.1-6.0.10.jar"))
    compileOnly(files("../_deps_CreateBigCannons/createbigcannons-5.11.7+mc.1.21.1.jar"))
    // BaseConfigScreen is supplied at runtime by Create's bundled Ponder library.
    compileOnly(files("../_deps_CreateBigCannons/ponder-neoforge-1.0.82+mc1.21.1.jar"))
    // Optional compatibility: repair stale Sable holding-sublevel references.
    compileOnly(files("../_deps_sable_2_0_5/sable-neoforge-1.21.1-2.0.5.jar"))
    compileOnly(files("../_deps_sable/META-INF/jarjar/sable-companion-common-1.21.1-1.6.0.jar"))
    // Optional nuclear-shell compatibility. The classes are only touched by
    // optional pseudo mixins and the mod remains usable without this JAR.
    compileOnly(files("../_tmp_selected_mods/CBCmoreshells-1.5.1.jar"))
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand("version" to project.version)
    }
}

// Deterministic geometry regressions, no Minecraft server or downloaded test library.
sourceSets.test {
    compileClasspath += sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().compileClasspath
}
val collisionRegressionTest by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.cbcrealisticballistics.CollisionSegmentsTest")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}
val nuclearScorchRegressionTest by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.cbcrealisticballistics.NuclearScorchChunkSupportTest")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}
val nuclearBlastProfileRegressionTest by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.cbcrealisticballistics.NuclearBlastProfileTest")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}
val nuclearAftermathGeometryRegressionTest by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.cbcrealisticballistics.NuclearAftermathGeometryTest")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}
tasks.check {
    dependsOn(collisionRegressionTest)
    dependsOn(nuclearScorchRegressionTest)
    dependsOn(nuclearBlastProfileRegressionTest)
    dependsOn(nuclearAftermathGeometryRegressionTest)
}
