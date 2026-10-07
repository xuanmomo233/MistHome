import org.gradle.api.tasks.compile.JavaCompile

plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "dev.mist"
version = providers.gradleProperty("version").getOrElse("0.1.0")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
    // Spigot API（Mohist 实现的是 Bukkit/Spigot 层，不用 paper-api）
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    // Vault + querz-nbt（JitPack 发布）
    maven("https://jitpack.io") {
        content {
            includeGroup("com.github.MilkBowl")
            includeGroup("com.github.Querz")
        }
    }
    // WorldEdit（worldedit-libs 等子组也要放行）
    maven("https://maven.enginehub.org/repo/") {
        content { includeGroupByRegex("com\\.sk89q(\\..*)?") }
    }
    // ProtocolLib
    maven("https://repo.dmulloy2.net/repository/public/") {
        content { includeGroup("com.comphenix.protocol") }
    }
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.20.1-R0.1-SNAPSHOT")
    compileOnly("org.jetbrains:annotations:24.1.0")

    // 软依赖：经济 / 模板粘贴 / 边界包
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.2.15")
    compileOnly("com.comphenix.protocol:ProtocolLib:5.3.0")

    // 存储层：shade 进 jar（HikariCP + SQLite + MySQL）
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")            // 不重定位，避免 native 资源路径错位
    implementation("com.mysql:mysql-connector-j:8.2.0")          // 保留 protobuf-java 等传递依赖
    // NBT 读写：换槽位恢复时重写 mca 内实体绝对坐标（region 容器自实现，querz 只做 NBT 层）
    implementation("com.github.Querz:NBT:6.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.45.3.0")
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(
            mapOf(
                "version" to project.version,
                "name" to "MistHome",
                "main" to "dev.mist.home.MistHomePlugin"
            )
        )
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(17)
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveClassifier.set("")
    //  relocate 防止与其他插件内嵌依赖冲突
    relocate("com.zaxxer.hikari", "dev.mist.home.libs.hikari")
    // org.sqlite 不重定位：sqlite-jdbc 的 native 库资源路径与类包强关联，重定位后可能加载失败
    relocate("com.mysql", "dev.mist.home.libs.mysql")
    relocate("com.google.protobuf", "dev.mist.home.libs.protobuf")  // mysql-connector-j 的传递依赖
    relocate("net.querz", "dev.mist.home.libs.querz")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
