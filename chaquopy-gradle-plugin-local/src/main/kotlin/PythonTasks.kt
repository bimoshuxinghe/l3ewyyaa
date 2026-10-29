package com.chaquo.python

import com.android.build.api.variant.*
import com.chaquo.python.internal.*
import org.apache.commons.compress.archivers.zip.*
import org.gradle.api.*
import org.gradle.api.artifacts.*
import org.gradle.api.file.*
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.process.*
import org.gradle.process.internal.*
import org.json.*
import java.io.*
import java.security.*
import java.util.*
import javax.inject.Inject
import kotlin.reflect.*


internal class TaskBuilder(
    val plugin: PythonPlugin, val variant: Variant, val python: PythonExtension,
    val abis: List<String>
) {
    val project = plugin.project
    lateinit var buildPackagesTask: Provider<BuildPackagesTask>
    lateinit var srcTask: Provider<OutputDirTask>
    lateinit var reqsTask: Provider<OutputDirTask>

    fun build() {
        createConfigs()
        buildPackagesTask = createBuildPackagesTask()
        srcTask = createSrcTask()
        reqsTask = createReqsTask()
        createProxyTask()
        createAssetsTasks()
        createJniLibsTasks()
    }

    fun createConfigs() {
        plugin.addRuntimeDependency(
            "bootstrap", Common.assetZip(Common.ASSET_BOOTSTRAP), variant, python)
        plugin.addTargetDependency(
            "stdlib", variant, python,
            if (python.pyc.stdlib == false) "stdlib" else "stdlib-pyc")

        for (abi in abis) {
            plugin.addRuntimeDependency(
                "jni", "libchaquopy_java.so", variant, python, abi)
            plugin.addRuntimeDependency(
                "modules", "chaquopy.so", variant, python, abi)
            plugin.addTargetDependency("native", variant, python, abi)
        }
    }

    fun createBuildPackagesTask() =
        registerTask("extract", "buildPackages", BuildPackagesTask::class) {
            var bp: List<String>?
            try {
                bp = findBuildPython()
            } catch (e: BuildPythonException) {
                bp = null
                exception = e
            }
            inputs.property("buildPython", bp).optional(true)

            // Keep the path short to avoid the the Windows 260-character limit.
            outputFiles = project.fileTree(plugin.buildSubdir("env", variant)) {
                exclude("**/__pycache__")
            }
            // 上面是直接给 outputFiles 赋值（带 exclude 过滤），绕过了 outputDir 的
            // setter，因此这里需要手动同步 outputDirProperty。
            outputDirProperty.set(plugin.buildSubdir("env", variant))

            if (bp != null) {
                doLast {
                    plugin.execOps.exec {
                        commandLine(bp)
                        args("-m", "venv", "--without-pip", outputDir)
                    }

                    val zipPath = plugin.extractResource(
                        "gradle/build-packages.zip", plugin.buildSubdir())
                    project.copy {
                        from(project.zipTree(zipPath))
                        into(sitePackages)
                    }
                    project.delete(zipPath)
                }
            }
        }

    fun createSrcTask() =
        registerTask("merge", "sources") {
            inputs.files(buildPackagesTask)
            inputs.property("pyc", python.pyc.src).optional(true)

            val dirSets = ArrayList<SourceDirectorySet>()
            for (name in sourceSetNames()) {
                val dirSet = plugin.extension.sourceSets.findByName(name)
                if (dirSet != null) {
                    dirSets += dirSet
                    inputs.files(dirSet)
                }
            }

            outputDir = plugin.buildSubdir("sources", variant)
            doLast {
                project.copy {
                    for (dirSet in dirSets) {
                        for (srcDir in dirSet.srcDirs) {
                            from(srcDir) {
                                exclude(dirSet.excludes)
                                include(dirSet.includes)
                            }
                        }
                    }
                    duplicatesStrategy = DuplicatesStrategy.FAIL  // Overridden below

                    exclude("**/*.pyc", "**/*.pyo")
                    exclude("**/*.egg-info")  // See ExtractPackages.test_change
                    into(outputDir)

                    // Allow duplicates for empty files (e.g. __init__.py)
                    eachFile {
                        if (file.length() == 0L) {
                            val destFile = File(outputDir, path)
                            if (destFile.exists() && destFile.length() == 0L) {
                                duplicatesStrategy = DuplicatesStrategy.INCLUDE
                            }
                        }
                    }
                }
                compilePyc(python.pyc.src, outputDir)
            }
        }

    fun sourceSetNames() = sequence {
        val buildType = variant.buildType!!
        yield("main")
        yield(buildType)

        for ((_, flavor) in variant.productFlavors) {
            yield(flavor)
            yield(flavor + buildType.capitalize())
        }

        if (variant.productFlavors.size >= 2) {
            val flavorName = variant.flavorName  // All flavors combined
            if (flavorName != null) {
                yield(flavorName)
                yield(flavorName + buildType.capitalize())
            }
        }
    }

    fun createReqsTask() =
        registerTask("generate", "requirements") {
            inputs.files(buildPackagesTask)
            inputs.property("abis", abis)
            inputs.property("minApiLevel", variant.minSdk.apiLevel)
            inputs.property("pip", python.pip)
            inputs.property("pyc", python.pyc.pip).optional(true)

            // Keep the path short to avoid the the Windows 260-character limit.
            outputDir = plugin.buildSubdir("pip", variant)

            val reqsArgs = ArrayList<String>()
            for (req in python.pip.reqs) {
                reqsArgs += listOf("--req", req)
                addReqInput(inputs, req, project.projectDir)
            }
            for (reqFile in python.pip.reqFiles) {
                reqsArgs += listOf("--req-file", reqFile)
                inputs.files(reqFile)
                try {
                    project.file(reqFile).forEachLine { line ->
                        // # is used for comments, and ; for environment markers.
                        val req = line.replace(Regex("[#;].*"), "").trim { it <= ' ' }
                        if (! req.isEmpty()) {
                            addReqInput(inputs, req, project.projectDir)
                        }
                    }
                } catch (_: FileNotFoundException) {}
            }

            doLast {
                if (!reqsArgs.isEmpty()) {
                    // If the user passes  a custom index url, disable our repository as
                    // well as the default one.
                    val customIndexUrl = listOf("--index-url", "-i").any {
                        it in python.pip.options
                    }

                    execBuildPython {
                        args("-m", "chaquopy.pip_install")
                        args("--target", outputDir)
                        args("--android-abis", *abis.toTypedArray())
                        args("--min-api-level", variant.minSdk.apiLevel)
                        args(reqsArgs)
                        args("--")
                        args("--disable-pip-version-check")
                        if (!customIndexUrl) {
                            args("--extra-index-url", "https://chaquo.com/pypi-13.1")
                        }
                        args("--implementation", Common.PYTHON_IMPLEMENTATION)
                        args("--python-version", pythonVersionInfo(python).key)
                        args("--abi", (Common.PYTHON_IMPLEMENTATION +
                                       python.version!!.replace(".", "")))
                        args("--no-compile")
                        args(python.pip.options)
                    }
                    compilePyc(python.pyc.pip, outputDir)
                }

                // Requirements subdirectories must exist, or their ZIPs won't be created,
                // and the app will crash (#5631).
                for (subdirName in listOf(Common.ABI_COMMON) + abis) {
                    val subdir = File(outputDir, subdirName)
                    if (!subdir.exists()) {
                        if (reqsArgs.isEmpty()) {
                            project.mkdir(subdir)
                        } else {
                            throw GradleException("$subdir was not created: please " +
                                                  "check your buildPython setting")
                        }
                    }
                }
            }
        }

    // TODO #719: Detect changes to indirect requirements or constraints files. The
    // `baseDir` argument will be useful for that, because pip resolves `-r` and `-c`
    // lines relative to the location of the containing requirements file, while paths
    // to actual requirements are resolved relative to the working directory
    // (https://github.com/pypa/pip/pull/4208#issuecomment-429120743).
    fun addReqInput(inputs: TaskInputs, req: String, baseDir: File) {
        var file: File?
        try {
            file = File(req)
            if (! file.isAbsolute) {
                // Passing two absolute paths to the File constructor will simply
                // concatenate them rather than returning the second one.
                file = File(baseDir, req)
            }
            if (! file.exists()) {
                file = null
            }
        } catch (e: Exception) {
            // In case any of the above code throws on an invalid filename.
            file = null
        }

        // Do this outside of the try block to avoid hiding exceptions.
        if (file != null) {
            if (file.isDirectory) {
                inputs.files(project.fileTree(file) {
                    // Ignore any files which may be written while installing.
                    exclude("build", "dist", "**/*.dist-info", "**/*.egg-info")
                    exclude("**/__pycache__") // See test_pep517_backend_path
                })
            } else {
                inputs.files(file)
            }
        }
    }

    fun createProxyTask() {
        val outputDir = plugin.buildSubdir("proxies", variant)
        val task = registerTask("generate", "proxies") {
            inputs.files(buildPackagesTask, reqsTask, srcTask)
            inputs.property("staticProxy", python.staticProxy)

            this.outputDir = outputDir
            doLast {
                if (!python.staticProxy.isEmpty()) {
                    execBuildPython {
                        args("-m", "chaquopy.static_proxy")
                        args("--path",
                             listOf(
                                srcTask.get().outputDir,
                                "${reqsTask.get().outputDir}/common"
                            ).joinToString(File.pathSeparator))
                        args("--java", outputDir)
                        args(python.staticProxy)
                    }
                }
            }
        }

        // 把生成的 Java 代理类目录注册到 variant 的 Java 源目录中。
        //
        // 旧版做法是反射调用 getLibraryVariants()/getApplicationVariants() 再调
        // registerJavaGeneratingTask，但这些 API 在 AGP 9 中已被移除
        // （实现类 LibraryExtensionImpl$AgpDecorated_Decorated 上不再存在
        // getLibraryVariants 方法，调用会抛 NoSuchMethodException）。
        //
        // AGP 7.3+ 提供的 variant.sources.java 是官方支持的替代方案。
        // addGeneratedSourceDirectory 会给 outputDirProperty 设置一个 convention
        // （build/generated 下的子目录），而我们显式 set() 的值优先级更高，
        // 所以仍然可以保持 build/python 下的短路径。
        variant.sources.java!!.addGeneratedSourceDirectory(task) { it.outputDirProperty }
    }

    fun createAssetsTasks() {
        val excludePy = { fte: FileTreeElement ->
            if (fte.name.endsWith(".py") &&
                File(fte.file.parent, fte.name + "c").exists()
            ) {
                ! python.extractPackages.any {
                    fte.path.replace("/", ".").startsWith(it + ".")
                }
            } else false
        }

        val srcAssetsTask = assetTask("source") {
            inputs.files(srcTask)
            inputs.property("extractPackages", python.extractPackages)
            doLast {
                makeZip(project.fileTree(srcTask.get().outputDir)
                            .matching { exclude(excludePy) },
                        File(assetDir, Common.assetZip(Common.ASSET_APP)))
            }
        }

        val reqsAssetsTask = assetTask("requirements") {
            inputs.files(reqsTask)
            inputs.property("extractPackages", python.extractPackages)
            doLast {
                for (subdir in reqsTask.get().outputDir.listFiles()!!) {
                    makeZip(
                        project.fileTree(subdir).matching { exclude(excludePy) },
                        File(assetDir, Common.assetZip(Common.ASSET_REQUIREMENTS, subdir.name)))
                }
            }
        }

        val miscAssetsTask = assetTask("misc") {
            val runtimeBootstrap = plugin.getConfig("runtimeBootstrap", variant)
            val runtimeModules = plugin.getConfig("runtimeModules", variant)
            val targetStdlib = plugin.getConfig("targetStdlib", variant)
            val targetNative = plugin.getConfig("targetNative", variant)
            inputs.files(runtimeBootstrap, runtimeModules, targetStdlib, targetNative)

            doLast {
                project.copy {
                    fromRuntimeArtifact(runtimeBootstrap)
                    from(targetStdlib) {
                        rename { Common.assetZip(Common.ASSET_STDLIB, Common.ABI_COMMON) }
                    }
                    into(assetDir)
                }

                // The following stdlib native modules are needed during bootstrap and are
                // pre-extracted by AndroidPlatform so they can be loaded with the
                // standard FileFinder. All other native modules are loaded from a .zip using
                // AssetFinder.
                //
                // If this list changes, search for references to this variable name to
                // find the tests that need to be updated.
                val BOOTSTRAP_NATIVE_STDLIB = listOf(
                    "_bz2.so",  // zipfile < importer
                    "_ctypes.so",  // java.primitive and importer
                    "_datetime.so",  // calendar < importer (see test_datetime)
                    "_lzma.so",  // zipfile < importer
                    "_random.so",  // random < tempfile < zipimport
                    "_sha2.so",  // random < tempfile < zipimport (Python >= 3.12)
                    "_sha512.so",  // random < tempfile < zipimport (Python <= 3.11)
                    "_struct.so",  // zipfile < importer
                    "binascii.so",  // zipfile < importer
                    "math.so",  // datetime < calendar < importer
                    "mmap.so",  // elftools < importer
                    "zlib.so"  // zipimport
                )

                for (abi in abis) {
                    project.copy {
                        from(project.zipTree(resolveArtifact(targetNative, abi).file))
                        include("lib-dynload/**")
                        into(assetDir)
                    }
                    makeZip(project.fileTree("$assetDir/lib-dynload/$abi")
                                .matching { exclude(BOOTSTRAP_NATIVE_STDLIB) },
                            File(assetDir, Common.assetZip(Common.ASSET_STDLIB, abi)))

                    val bootstrapDir = "$assetDir/${Common.ASSET_BOOTSTRAP_NATIVE}/$abi"
                    project.copy {
                        from("$assetDir/lib-dynload/$abi")
                        include(BOOTSTRAP_NATIVE_STDLIB)
                        into(bootstrapDir)
                    }
                    project.delete("$assetDir/lib-dynload")

                    project.copy {
                        fromRuntimeArtifact(runtimeModules, abi)
                        into("$bootstrapDir/java")
                    }
                }
                plugin.extractResource(Common.ASSET_CACERT, assetDir)
            }
        }

        assetTask("build") {
            val tasks = arrayOf(srcAssetsTask, reqsAssetsTask, miscAssetsTask)
            inputs.files(*tasks)
            doLast {
                val buildJson = JSONObject()
                buildJson.put("python_version", python.version)
                buildJson.put("assets", hashAssets(*tasks))
                buildJson.put("extract_packages", JSONArray(python.extractPackages))
                File(assetDir, Common.ASSET_BUILD_JSON).writeText(buildJson.toString(4))
            }
        }

    }

    fun assetTask(
        name: String, configure: AssetDirTask.() -> Unit
    ): TaskProvider<AssetDirTask> {
        val outputDir = plugin.buildSubdir("assets/$name", variant)
        val task = registerTask("generate", "${name}Assets", AssetDirTask::class) {
            this.outputDir = outputDir
            configure()
        }

        // 把资源目录注册到 variant 的 assets 源目录中，替代旧版对 merge/package
        // 任务名的反射（见 extendMergeTask）。
        variant.sources.assets!!.addGeneratedSourceDirectory(task) {
            it.outputDirProperty
        }
        return task
    }

    // AGP 7.3+ 的 variant.sources API 已可同时处理 Java 文件（7.2 起）、assets 与
    // native libs（7.3 起），因此不再需要按任务名查找 AGP 的 merge 任务并反射调用
    // getOutputDir。相关注册分别见 createProxyTask / assetTask / createJniLibsTasks。
    @Suppress("unused")
    fun <T: OutputDirTask> extendMergeTask(
        verb: String, noun: String, inputTask: Provider<T>
    ) {
        project.tasks.named("$verb${variant.name.capitalize()}${noun.capitalize()}") {
            inputs.files(inputTask)
            val mergeTask = this
            val getOutputDir = mergeTask.javaClass.getMethod("getOutputDir")
            doLast {
                project.copy {
                    from(inputTask.get().outputDir)
                    into(getOutputDir.invoke(mergeTask) as DirectoryProperty)
                }
            }
        }
    }

    fun createJniLibsTasks() {
        val outputDir = plugin.buildSubdir("jniLibs", variant)
        val task = registerTask("generate", "jniLibs") {
            val runtimeJni = plugin.getConfig("runtimeJni", variant)
            val targetNative = plugin.getConfig("targetNative", variant)
            inputs.files(runtimeJni, targetNative)

            this.outputDir = outputDir
            doLast {
                val artifacts = targetNative.resolvedConfiguration.resolvedArtifacts
                for (art in artifacts) {
                    // Copy jniLibs/<arch>/ in the ZIP to jniLibs/<variant>/<arch>/ in
                    // the build directory.
                    // (https://discuss.gradle.org/t/copyspec-support-for-moving-files-directories/7412/1)
                    project.copy {
                        from(project.zipTree(art.file))
                        include("jniLibs/**")
                        into(outputDir)
                        eachFile {
                            relativePath = RelativePath(
                                !file.isDirectory(),
                                *relativePath.segments.let {
                                    it.sliceArray(1 until it.size)
                                })
                        }
                        includeEmptyDirs = false
                    }
                }

                for (abi in abis) {
                    project.copy {
                        fromRuntimeArtifact(runtimeJni, abi)
                        into("$outputDir/$abi")
                    }
                }
            }
        }

        // 把原生库目录注册到 variant 的 jniLibs 源目录中，替代旧版对
        // mergeJniLibFolders 任务名的反射（见 extendMergeTask）。
        variant.sources.jniLibs!!.addGeneratedSourceDirectory(task) {
            it.outputDirProperty
        }
    }

    // We can't remove the .py files here because the static proxy generator needs them.
    // Instead, they'll be excluded when we call makeZip.
    fun compilePyc(setting: Boolean?, dir: File) {
        if (setting != false) {
            try {
                execBuildPython {
                    args("-m", "chaquopy.pyc")
                    args("--python", python.version)
                    args("--quiet") // TODO #5411: option to display syntax errors
                    if (setting != true) {
                        args("--warning")
                    }
                    args(dir)
                }
            } catch (e: BuildPythonException) {
                if (setting == true) {
                    throw e
                } else {
                    // Messages should be formatted the same as those from chaquopy.pyc.
                    warn("Failed to compile to .pyc format: ${e.shortMessage} See " +
                        "https://chaquo.com/chaquopy/doc/current/android.html#android-bytecode")
                }
            }
        }
    }

    fun warn(message: String) {
        // This prefix causes Android Studio to show the line as a warning in tree view.
        println("Warning: $message")
    }

    fun registerTask(
        verb: String, noun: String, configure: OutputDirTask.() -> Unit
    ) = registerTask(verb, noun, OutputDirTask::class, configure)

    fun <T: OutputDirTask> registerTask(
        verb: String, noun: String, cls: KClass<T>, configure: T.() -> Unit
    ): TaskProvider<T> {
        // This matches the format of the AGP's own task names.
        //
        // 注意：这里必须显式把 configure 转成 Action<T>。
        // Kotlin 的 T.() -> Unit 会同时匹配 TaskContainer.register 的多个重载，
        // 若不显式转换，Gradle 会把该 lambda 误当成任务类的构造参数，
        // 从而报 "Unable to determine constructor argument #1"。
        return project.tasks.register(
            "$verb${variant.name.capitalize()}Python${noun.capitalize()}",
            cls, Action<T> { configure(this) })
    }

    fun resolveArtifact(config: Configuration, classifier: String): ResolvedArtifact {
        return config.resolvedConfiguration.resolvedArtifacts.find {
            it.classifier == classifier
        }!!
    }

    fun CopySpec.fromRuntimeArtifact(
        config: Configuration, abi: String? = null
    ) {
        val art = resolveArtifact(config, runtimeClassifier(python, abi))
        from(art.file) {
            rename { "${art.name}.${art.extension}" }
        }
    }

    fun execBuildPython(configure: ExecSpec.() -> Unit) {
        try {
            plugin.execOps.exec {
                executable(buildPackagesTask.get().pythonExecutable)
                configure()
            }
        } catch (e: ExecException) {
            // Message will be something like "Process 'command 'py'' finished with
            // non-zero exit value 1", so we need to tell the user how to see the
            // command output.
            throw BuildPythonException(
                e.message!!,
                "\n\nTo view full details in Android Studio:\n" +
                "* Click the 'Build: failed' caption to the left of this message.\n" +
                "* Then scroll up to see the full output.")
        }
    }

    fun findBuildPython(): List<String> {
        val bpSetting = python.buildPython
        val bps = sequence {
            if (bpSetting != null) {
                yield(bpSetting)

                // Backward compatibility for when buildPython only took a single string
                if (bpSetting.size == 1) {
                    yield(bpSetting[0].split(Regex("""\s+""")))
                }
            } else {
                val version = python.version!!
                for (suffix in listOf(version, version.split(".")[0])) {
                    if (Common.osName() == "windows") {
                        // See PEP 397. After running the official Windows installer
                        // with default settings, this will be the only Python thing on
                        // the PATH.
                        yield(listOf("py", "-$suffix"))
                    } else {
                        // See PEP 394.
                        yield(listOf("python$suffix"))
                    }
                }

                // On Windows, both venv and conda environments contain only a `python`
                // executable, not `pythonX`, `pythonX.Y`, or `py`. It's also reasonable
                // to use this as a final fallback on Unix (#752).
                yield(listOf("python"))
            }
        }

        for (bp in bps) {
            try {
                plugin.execOps.exec {
                    commandLine(bp)
                    args("--version")
                    standardOutput = ByteArrayOutputStream()
                    errorOutput = ByteArrayOutputStream()
                }
                return bp
            } catch (_: ExecException) {}
        }
        if (bpSetting != null) {
            throw BuildPythonException(
                "$bpSetting does not appear to be a valid Python command.",
                BUILD_PYTHON_ADVICE)
        } else {
            throw BuildPythonException("Couldn't find Python.", BUILD_PYTHON_ADVICE)
        }
    }
}


val BUILD_PYTHON_ADVICE =
    "See https://chaquo.com/chaquopy/doc/current/android.html#buildpython"

class BuildPythonException(val shortMessage: String, suffix: String) :
    GradleException("$shortMessage $suffix")




fun hashAssets(vararg tasks: TaskProvider<AssetDirTask>): JSONObject {
    val json = JSONObject()
    for (task in tasks) {
        hashAssets(json, task.get().assetDir, "")
    }
    return json
}

fun hashAssets(json: JSONObject, dir: File, prefix: String) {
    for (file in dir.listFiles()!!) {
        val path = prefix + file.name
        if (file.isDirectory()) {
            hashAssets(json, file, path + "/")
        } else {
            // These files may be hundreds of megabytes, so read them in chunks.
            val digest = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { stream ->
                val buf = ByteArray(1024 * 1024)
                while (true) {
                    val len = stream.read(buf)
                    if (len > 0) {
                        digest.update(buf, 0, len)
                    } else {
                        break
                    }
                }
            }
            json.put(path, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}


// Based on org/gradle/api/internal/file/archive/ZipCopyAction.java. This isn't part of
// the Gradle public API except via the Zip task, which we're not using because we'd
// need to refactor to have one task per ZIP.
//
// The usual alternative is to use ant.zip, but that has other problems:
//   * It only takes simple exclusion patterns, so there's no way to say "exclude .py
//     files which have a corresponding .pyc".
//   * It has no equivalent to preserveFileTimestamps, so we'd have to actually set the
//     timestamps of all the input files.
fun makeZip(tree: FileTree, outFile: File) {
    ZipArchiveOutputStream(outFile).use { zip ->
        // UTF-8 filename encoding is apparently on by default on Linux and off by
        // default on Windows: this alters the resulting ZIP file even if all filenames
        // are ASCII.
        zip.setEncoding("UTF-8")

        // This is the same timestamp used by Gradle's preserveFileTimestamps setting.
        // The UTC timestamp generated here will vary according to the current timezone,
        // but the local time will be constant, and that's what gets stored in the ZIP.
        val timestamp = GregorianCalendar(1980, Calendar.FEBRUARY, 1, 0, 0, 0)
            .getTimeInMillis()

        tree.visit(object : ReproducibleFileVisitor {
            override fun isReproducibleFileOrder(): Boolean {
                return true
            }
            override fun visitDir(details: FileVisitDetails) {
                val entry = ZipArchiveEntry(details.path + "/")
                entry.setTime(timestamp)
                zip.putArchiveEntry(entry)
                zip.closeArchiveEntry()
            }
            override fun visitFile(details: FileVisitDetails) {
                val entry = ZipArchiveEntry(details.path)
                entry.setTime(timestamp)
                zip.putArchiveEntry(entry)
                details.copyTo(zip)
                zip.closeArchiveEntry()
            }
        })
    }
}


fun assertExists(f: File) : File {
    if (!f.exists()) {
        throw GradleException("$f does not exist")
    }
    return f
}


// 注意：OutputDirTask / AssetDirTask 必须定义在顶层（而不是 TaskBuilder 内部类中）。
// Gradle 通过反射实例化任务类时，只能正确识别顶层类的构造器参数；
// 嵌套类的构造器签名会被解析成错误的参数类型，导致
// "Unable to determine constructor argument #1" 之类的错误。
open class OutputDirTask @Inject constructor(
    objects: ObjectFactory
) : DefaultTask() {
    @get:OutputFiles
    lateinit var outputFiles: ConfigurableFileTree

    @get:Internal
    var outputDir: File
        get() = outputFiles.dir
        set(value) {
            outputFiles = project.fileTree(value)
            // 保持与 AGP 注册用的 DirectoryProperty 同步。
            outputDirProperty.set(value)
        }

    // AGP 7.3+ 的 variant.sources.* API 要求任务暴露一个 DirectoryProperty，
    // 而不是旧版 API 使用的 File。两者保持同步：
    //   * outputDir 供插件内部沿用原逻辑使用；
    //   * outputDirProperty 供 addGeneratedSourceDirectory 注册给 AGP。
    //
    // 注意：必须标注为 @OutputDirectory。AGP 内部通过
    // taskProvider.flatMap { wiredWith(it) } 来解析该属性，把它当作任务的
    // 输出目录；若缺少输出注解，Gradle 会把它当成普通值，
    // 从而报 "Cannot convert ... to a task"。
    //
    // 通过构造函数注入 ObjectFactory 来创建，而不是在属性初始化时调用
    // project.objects —— 后者会在 Gradle 反射构造任务对象期间执行，
    // 此时 project 字段尚未赋值。
    @get:OutputDirectory
    val outputDirProperty: DirectoryProperty = objects.directoryProperty()

    @TaskAction
    open fun run() {
        project.delete(outputDir)
        project.mkdir(outputDir)
    }
}


open class AssetDirTask @Inject constructor(
    objects: ObjectFactory
) : OutputDirTask(objects) {
    @get:Internal
    val assetDir
        get() = File(outputDir, Common.ASSET_DIR)

    @TaskAction
    override fun run() {
        super.run()
        project.mkdir(assetDir)
    }
}


// 注意：与 OutputDirTask / AssetDirTask 一样，BuildPackagesTask 也必须定义在顶层，
// 否则 Gradle 反射实例化任务时会错误解析其构造器参数。
open class BuildPackagesTask @Inject constructor(
    objects: ObjectFactory
) : OutputDirTask(objects) {
    @get:Internal
    lateinit var exception: Exception

    @get:Internal
    val pythonExecutable by lazy {
        if (::exception.isInitialized) {
            throw exception
        } else if (Common.osName() == "windows") {
            outputDir.resolve("Scripts/python.exe")
        } else {
            outputDir.resolve("bin/python")
        }
    }

    @get:Internal
    val sitePackages by lazy {
        val libPythonDir = if (Common.osName() == "windows") {
            assertExists(outputDir.resolve("Lib"))
        } else {
            val libDir = assertExists(outputDir.resolve("lib"))
            val pythonDirs = libDir.listFiles()!!.filter {
                it.name.startsWith("python")
            }
            if (pythonDirs.size != 1) {
                throw GradleException(
                    "found ${pythonDirs.size} python directories in $libDir")
            }
            pythonDirs[0]
        }
        libPythonDir.resolve("site-packages")
    }
}
