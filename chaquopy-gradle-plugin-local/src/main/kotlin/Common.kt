package com.chaquo.python.internal

import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale

/**
 * 常量与工具类，供 Gradle 插件与运行时共用。
 *
 * 本文件由 Chaquopy 15.0.1 的 `product/buildSrc/src/main/java/com/chaquo/python/internal/Common.java`
 * 移植而来（原文件为 Java，移植到 Kotlin 是为了避免 Kotlin/Java 混合编译的顺序依赖）。
 * 逻辑与原实现保持一致。
 */
object Common {
    // 最低支持的 Android Gradle plugin 版本
    const val MIN_AGP_VERSION = "7.0.0"

    // 该值应与 target/build-common.sh 中的 api_level 保持一致。
    const val MIN_SDK_VERSION = 21

    const val COMPILE_SDK_VERSION = 34

    // 版本号 -> 构建号
    @JvmField
    val PYTHON_VERSIONS: Map<String, String> = linkedMapOf(
        "3.8.18" to "0",
        "3.9.18" to "0",
        "3.10.13" to "0",
        "3.11.6" to "0",
        "3.12.1" to "0",
    )

    @JvmField
    val PYTHON_VERSIONS_SHORT: List<String> =
        PYTHON_VERSIONS.keys.map { it.substring(0, it.lastIndexOf('.')) }

    // 该版本在仓库中可用的原生包最齐全。
    const val DEFAULT_PYTHON_VERSION = "3.8"

    // Wheel 标签（PEP 425）
    const val PYTHON_IMPLEMENTATION = "cp"  // CPython

    @JvmStatic
    fun supportedAbis(pythonVersion: String): List<String> {
        if (!PYTHON_VERSIONS_SHORT.contains(pythonVersion)) {
            throw IllegalArgumentException("Unknown Python version: '$pythonVersion'")
        }
        val result = mutableListOf<String>()
        result.add("arm64-v8a")
        result.add("x86_64")
        if (listOf("3.8", "3.9", "3.10", "3.11").contains(pythonVersion)) {
            result.add("armeabi-v7a")
            result.add("x86")
        }
        result.sort()
        return result
    }

    // 在 assets、getFilesDir()、getCacheDir() 中使用的子目录名
    const val ASSET_DIR = "chaquopy"

    @JvmStatic
    fun assetZip(type: String): String = assetZip(type, null)

    @JvmStatic
    fun assetZip(type: String, abi: String?): String {
        // ZIP 文件不能在 APK 内被压缩（里面的内容本身已压缩），
        // 否则会严重拖慢 AssetFinder 的随机读取性能。
        // .zip 不在 frameworks/base/tools/aapt2/cmd/Link.cpp 的默认 noCompress 列表里，
        // 所以这里改用一个冷门扩展名。
        val ext = ".imy"
        return if (abi == null) "$type$ext" else "$type-$abi$ext"
    }

    // assetZip 的参数
    const val ABI_COMMON = "common"
    const val ASSET_BOOTSTRAP = "bootstrap"
    const val ASSET_APP = "app"
    const val ASSET_REQUIREMENTS = "requirements"
    const val ASSET_STDLIB = "stdlib"

    // 其他 assets
    const val ASSET_BOOTSTRAP_NATIVE = "bootstrap-native"
    const val ASSET_BUILD_JSON = "build.json"
    const val ASSET_CACERT = "cacert.pem"

    @JvmStatic
    fun osName(): String {
        val property = System.getProperty("os.name")
        for (name in listOf("linux", "mac", "windows")) {
            if (property.lowercase(Locale.ENGLISH).startsWith(name)) return name
        }
        throw RuntimeException("unknown os.name: $property")
    }

    @JvmStatic
    @Throws(FileNotFoundException::class)
    fun findExecutable(name: String): String {
        var file = File(name)
        if (file.isAbsolute) {
            if (!file.exists()) throw FileNotFoundException("'$name' does not exist")
            return name
        }

        // Mac 上 PATH 的默认值是 /usr/bin:/bin:/usr/sbin:/sbin，但 App 无法安装到这些位置，
        // 因此 python.org 的安装程序会使用 /usr/local/bin。
        val path = mutableListOf<String>()
        val osName = System.getProperty("os.name").lowercase()
        if (osName.startsWith("mac")) {
            val etcPaths = "/etc/paths"
            try {
                BufferedReader(File(etcPaths).readText().reader()).use { reader ->
                    reader.lineSequence().forEach { path.add(it) }
                }
            } catch (e: IOException) {
                println("Warning: while reading $etcPaths: $e")
            }
        }
        path.addAll(System.getenv("PATH").split(File.pathSeparator))

        val exts = mutableListOf("")
        if (osName.startsWith("win")) {
            exts.add(".exe")
            exts.add(".bat")
        }

        for (dir in path) {
            for (ext in exts) {
                file = File(dir, name + ext)
                if (file.exists()) return file.toString()
            }
        }
        throw FileNotFoundException("Couldn't find '$name' on PATH")
    }
}
