import java.net.URI
import java.util.zip.ZipFile

// 本地构建的 Chaquopy Gradle 插件。
//
// 背景：本项目使用 Gradle 9.6 + AGP 9.4，而官方 Chaquopy 15.0.1 插件依赖
// `org.gradle.util.VersionNumber`（Gradle 9 已移除）与 `Project.exec`
// （Gradle 9 已移除），直接使用会在配置阶段抛 NoClassDefFoundError。
//
// 本目录是 Chaquopy 15.0.1 插件源码的一份副本，来源：
//   https://github.com/chaquo/chaquopy/tree/15.0.1/product/gradle-plugin
// 在其基础上应用了官方 upstream 的三处 Gradle 9 适配补丁：
//   1. 4e5aad4997  Replace org.gradle.util.VersionNumber with
//                   java.lang.module.ModuleDescriptor.Version (#1096)
//   2. 8c56efe0e1  Replace Project.exec with ExecOperations.exec (#1096)
//   3. buildscript 中自定义 configuration 改用 detachedConfiguration
//      （Gradle 9 不允许在 buildscript context 创建 configuration）
//
// 运行时资源（Python 解释器）仍使用官方发布的 15.0.1 版本，
// 即 Python 3.11.6 + minSdk 21，可在 Android 5.0/5.1 上运行。

plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
}

group = "com.chaquo.python"

// 必须与官方发布的 Chaquopy 插件版本号一致。
//
// 本插件通过 composite build (includeBuild) 引入，Gradle 会把
// com.chaquo.python:gradle 替换成本工程自身。插件内部通过
// findPlugin("com.chaquo.python", "gradle") 解析出该坐标的版本号，
// 再据此定位 com.chaquo.python.runtime:*:<version> 等运行时构件。
// 若不显式声明版本号，会解析成 "unspecified" 而导致构件找不到。
version = "15.0.1"

gradlePlugin {
    plugins {
        create("gradle") {
            id = "com.chaquo.python"
            implementationClass = "com.chaquo.python.PythonPlugin"
            displayName = "Chaquopy"
            description = "The Python SDK for Android"
        }
    }
}

dependencies {
    // gradleApi() 由 java-gradle-plugin 自动添加。
    //
    // 编译期需要 AGP 9.4 的 API：本插件使用 variant.sources.java/assets/jniLibs
    // 来向 AGP 注册生成目录（AGP 9 已移除 getLibraryVariants 等旧版 variant API）。
    // 这里用 compileOnly，运行时由宿主项目（app / chaquo 模块）提供实际的 AGP 版本。
    compileOnly("com.android.tools.build:gradle-api:9.4.0")
    implementation("org.apache.commons:commons-compress:1.18")
    implementation("org.json:json:20160810")
}

java {
    // Gradle 9.x / AGP 9.x 的基线是 Java 17，Kotlin DSL 插件也要求 17。
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets.main {
    java.srcDir("src/main/java")
}

// 官方 15.0.1 插件 jar 内置了三个运行时资源：
//   com/chaquo/python/proguard-rules.pro  —— 混淆规则
//   com/chaquo/python/cacert.pem          —— CA 证书
//   com/chaquo/python/gradle/build-packages.zip —— pip 等构建期 Python 资源
//
// 注意：这里不能通过 Gradle 依赖来获取官方 jar —— 本项目自身的坐标就是
// com.chaquo.python:gradle:15.0.1，在 composite build 中会被 Gradle 替换为
// 本地工程自身（自替换），导致取到尚未构建的自身 jar。
// 因此改为在构建期直接从 Maven Central 下载官方 jar 并从中取出这些资源。
val officialPluginJarUrl =
    "https://repo1.maven.org/maven2/com/chaquo/python/gradle/15.0.1/gradle-15.0.1.jar"

// 官方 jar 中需要提取的资源条目（jar 内路径 -> 提取后的相对路径）
val officialResources = mapOf(
    "com/chaquo/python/proguard-rules.pro" to "proguard-rules.pro",
    "com/chaquo/python/cacert.pem" to "cacert.pem",
    "com/chaquo/python/gradle/build-packages.zip" to "gradle/build-packages.zip",
)

tasks.register("extractOfficialResources") {
    val outputDir = layout.buildDirectory.dir("chaquopy-resources/com/chaquo/python")
    outputs.dir(outputDir)
    doLast {
        val outRoot = outputDir.get().asFile
        val tmpJar = File(outRoot.parentFile.parentFile.parentFile, "gradle-15.0.1.jar")
        tmpJar.parentFile.mkdirs()
        URI(officialPluginJarUrl).toURL().openStream().use { input ->
            tmpJar.outputStream().use { output -> input.copyTo(output) }
        }
        ZipFile(tmpJar).use { zip ->
            for ((entryName, targetPath) in officialResources) {
                val entry = zip.getEntry(entryName)
                    ?: throw GradleException("官方插件 jar 中缺少资源 $entryName")
                val outFile = File(outRoot, targetPath)
                outFile.parentFile.mkdirs()
                val tmp = File(outFile.absolutePath + ".tmp")
                zip.getInputStream(entry).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                if (!tmp.renameTo(outFile)) { tmp.copyTo(outFile, overwrite = true); tmp.delete() }
            }
        }
        tmpJar.delete()
    }
}

tasks.processResources {
    dependsOn("extractOfficialResources")
    // extractOfficialResources 已把上述三个资源放到了
    // chaquopy-resources/com/chaquo/python/ 下，
    // 与官方 15.0.1 插件 jar 内的路径完全一致。
    from(layout.buildDirectory.dir("chaquopy-resources"))
}

