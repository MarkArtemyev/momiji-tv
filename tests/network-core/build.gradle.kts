import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { kotlin("jvm") version "2.0.21" }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    sourceSets.main {
        kotlin.srcDirs("../../momiji-network-core/src/main/kotlin", "src/main/kotlin")
        kotlin.include("app/momiji/net/DomainTable.kt", "app/momiji/net/RequestRules.kt", "app/momiji/net/TlsHello.kt", "app/momiji/net/LoopbackProxy.kt", "NetworkCoreCheck.kt")
    }
}

val fixture = layout.buildDirectory.file("fixtures/localhost.p12")
val createFixture by tasks.registering(Exec::class) {
    outputs.file(fixture)
    onlyIf { !fixture.get().asFile.exists() }
    doFirst { fixture.get().asFile.parentFile.mkdirs() }
    val executable = if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"
    commandLine("${System.getProperty("java.home")}/bin/$executable", "-genkeypair", "-alias", "localhost",
        "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12", "-keystore", fixture.get().asFile.absolutePath,
        "-storepass", "momiji-test", "-keypass", "momiji-test", "-validity", "7", "-dname", "CN=fixture.momiji.test",
        "-ext", "SAN=dns:fixture.momiji.test,dns:localhost,ip:127.0.0.1", "-noprompt")
}

tasks.register<JavaExec>("networkCheck") {
    group = "verification"
    description = "Run local filtering, TLS and proxy integration checks"
    dependsOn(tasks.classes, createFixture)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("NetworkCoreCheckKt")
    jvmArgs("--add-modules", "jdk.httpserver", "-Xmx256m")
    args(fixture.get().asFile.absolutePath)
}
