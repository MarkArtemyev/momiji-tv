import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { kotlin("jvm") version "2.0.21" }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    sourceSets.main {
        // Only the pure planning logic; the Android probe, tuner and frame-rate glue are compiled with the apps.
        kotlin.srcDirs("../../momiji-home/app/src/main/java", "../../momiji-browser/app/src/main/java", "src/main/kotlin")
        kotlin.include("app/momiji/home/display/ScreenPlan.kt", "app/momiji/tv/FrameRatePlan.kt", "*.kt")
    }
}

tasks.register<JavaExec>("displayCheck") {
    group = "verification"
    description = "Check Home screen presets and the native player's frame-rate choice"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("DisplayCheckKt")
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
