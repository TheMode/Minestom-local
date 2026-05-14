plugins {
    id("minestom.java-binary")
}

dependencies {
    implementation(rootProject)
    implementation(project(":web"))

    runtimeOnly(libs.bundles.logback)
}

application {
    mainClass.set("net.minestom.demo.Main")
    mainModule.set("net.minestom.demo")

    applicationDefaultJvmArgs += "-ea"

    // Javalin / its Jetty deps are automatic modules with no explicit requires from anyone;
    // ALL-MODULE-PATH makes the JVM resolve every module on the module path so they load.
    applicationDefaultJvmArgs = listOf("--add-modules", "ALL-MODULE-PATH")
}

tasks.named<JavaExec>("run") {
    jvmArgs("--add-modules", "ALL-MODULE-PATH")
}
