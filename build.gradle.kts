plugins {
    java
    `maven-publish`
    id("com.gradleup.shadow") version "9.0.0-beta12"
}

group = "top.imbring"
version = "0.4.1"

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
}

java {
    // Using JDK from JAVA_HOME; source/target managed by toolchain
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }

    javadoc {
        options.encoding = "UTF-8"
    }

    processResources {
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(
                "name" to rootProject.name,
                "version" to version,
                "apiVersion" to "26.1",
                "author" to "Block_Bring"
            )
        }
    }

    shadowJar {
        archiveFileName.set("PlayerWaypoints-Paper-26.1.2-${version}.jar")
        mergeServiceFiles()
    }

    build {
        dependsOn(shadowJar)
    }
}
