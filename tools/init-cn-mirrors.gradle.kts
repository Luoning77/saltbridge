// 国内网络环境的 Gradle 初始化脚本：优先阿里云镜像，避免 dl.google.com / Maven Central 超时。
// 用法（不修改被构建仓库本身）：
//   ./gradlew -I "/Users/luoning/Desktop/code/salt mc/saltbridge/tools/init-cn-mirrors.gradle.kts" publishToMavenLocal
// 说明：仅替换仓库顺序，不改依赖版本；如需直连官方源，去掉 -I 参数即可。

settingsEvaluated {
    pluginManagement.repositories.apply {
        clear()
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        gradlePluginPortal()
    }
    dependencyResolutionManagement.repositories.apply {
        clear()
        mavenLocal()
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        mavenCentral()
    }
}
