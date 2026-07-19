plugins {
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "io.gitconflictradar"
version = "0.1.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 2024.2 avoids a malformed bundled Code With Me descriptor in the 2024.1 archive.
        intellijIdeaCommunity("2024.2.5")
        bundledPlugin("Git4Idea")
    }
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241"
            untilBuild = "251.*"
        }
    }
}

tasks {
    runIde {
        jvmArgs("-Xmx2g")
    }

    test {
        useJUnitPlatform()
    }
}
