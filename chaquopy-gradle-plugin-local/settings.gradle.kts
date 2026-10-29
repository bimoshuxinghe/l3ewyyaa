// rootProject.name 必须是 "gradle"，与官方 Chaquopy 插件的工程名一致。
// 插件内部通过 findPlugin("com.chaquo.python", "gradle") 在 buildscript
// classpath 中定位自身并解析出版本号（用于定位 com.chaquo.python.runtime:* 构件），
// 名字不一致会导致 "Failed to find plugin com.chaquo.python:gradle"。
rootProject.name = "gradle"

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
