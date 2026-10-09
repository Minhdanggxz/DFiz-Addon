plugins {
    alias(libs.plugins.fabric.loom)
}

base {
    archivesName.set("dfizaddon")
}

group = "com.nnpg"
version = libs.versions.mod.version.get()

repositories {
    mavenCentral()
    maven {
        name = "Meteor Releases"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "Meteor Snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
}

dependencies {
    minecraft(libs.minecraft)
    mappings("net.fabricmc:yarn:${libs.versions.yarn.get()}:v2")
    modImplementation(libs.fabric.loader)
    modImplementation(libs.meteor.client)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks {
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("fabric.mod.json") {
            expand(props)
        }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}
