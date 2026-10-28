import com.xuncorp.spw.workshop.gradle.PluginPermission
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.kotlin.kapt") version "2.4.20"
    id("com.xuncorp.spw.workshop") version "0.1.0-dev22"
}

group = "com.saltbridge"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    jvmToolchain(25)

    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    // 宿主提供，不能打进 .spmod
    compileOnly(kotlin("stdlib"))
    compileOnly("com.github.Moriafly:spw-workshop-api:0.1.0-dev23")
    kapt("com.github.Moriafly:spw-workshop-api:0.1.0-dev23")

    // 需要随插件分发，会进 .spmod 的 lib/
    implementation("com.google.code.gson:gson:2.15.0") {
        // 仅编译期注解，运行时不需要，避免打进包
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }
    // 宿主 JRE 实测缺少 jdk.httpserver，HTTP 只能自带实现
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // M3 状态核心：纯 Kotlin，不依赖宿主 API
    testImplementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // M4 适配层测试需要构造宿主类型；compileOnly 不会传递给 test classpath
    testImplementation("com.github.Moriafly:spw-workshop-api:0.1.0-dev23")
}

tasks.test {
    useJUnitPlatform()
}

spmod {
    PluginClass = "com.saltbridge.plugin.SaltBridgePlugin"
    PluginId = "com.saltbridge"
    PluginName = "SaltBridge"
    PluginDescription = "SaltBridge 盐桥：向本机应用提供播放状态、歌词、封面与播放控制（HTTP API）"
    PluginVersion = project.version.toString()
    PluginProvider = "SaltBridge"
    PluginHasConfig = true
    PluginPermissions = listOf(PluginPermission.LIBRARY_READ)
    PluginOpenSourceUrl = "https://github.com/Luoning77/saltbridge"
}
