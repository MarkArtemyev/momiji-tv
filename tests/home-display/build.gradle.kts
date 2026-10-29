import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { kotlin("jvm") version "2.0.21" }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    sourceSets.main {
        // Only the pure planning logic; the Android probe and tuner are compiled with the app.
        kotlin.srcDirs("../../momiji-home/app/src/main/java", "src/main/kotlin")
        kotlin.include("app/momiji/home/display/ScreenPlan.kt", "*.kt")
    }
}

tasks.register<JavaExec>("displayCheck") {
    group = "verification"
    description = "Check Momiji Home screen presets, refresh-rate choice, system setting plans and advice"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("DisplayCheckKt")
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
