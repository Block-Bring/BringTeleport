plugins {
    java
    `maven-publish`
    id("com.gradleup.shadow") version "9.0.0-beta12"
}

group = "top.imbring"
version = "0.6.1"

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    // 使用最低目标版本（1.21.1）的 Paper API 编译，API 向后兼容，
    // 编译产物可在 1.21.1 ~ 26.2 的所有 Paper 服务器上运行。
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
}

java {
    // Using JDK from JAVA_HOME; source/target managed by toolchain
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        // 1.21.1 服务器运行于 Java 21；26.x 需要 Java 25。
        // 固定为 Java 21 字节码，两个世代都能加载。
        options.release = 21
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
                "apiVersion" to "1.21",
                "author" to "Block_Bring"
            )
        }
    }

    shadowJar {
        archiveFileName.set("BringTeleport-Paper-${version}.jar")
        mergeServiceFiles()
    }

    build {
        dependsOn(shadowJar)
    }
}
