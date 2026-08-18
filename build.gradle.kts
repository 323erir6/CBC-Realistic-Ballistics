plugins {
    java
    id("net.neoforged.moddev") version "2.0.80"
}

version = property("mod_version")!!
group = "ua.ivan.cbcrealisticballistics"

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
    compileOnly(files("../_deps_CreateBigCannons/createbigcannons-5.11.7+mc.1.21.1.jar"))
    // BaseConfigScreen is supplied at runtime by Create's bundled Ponder library.
    compileOnly(files("../_deps_CreateBigCannons/ponder-neoforge-1.0.82+mc1.21.1.jar"))
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand("version" to project.version)
    }
}
