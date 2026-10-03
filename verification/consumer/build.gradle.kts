/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
plugins { java }
repositories {
    mavenLocal()
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
if (System.getProperty("os.name").startsWith("Windows") && rootDir.path.any { it.code > 127 }) {
    val consumerCache = providers.gradleProperty("fluidcoreConsumerBuildRoot").orNull
        ?: rootDir.toPath().root.resolve("DevCaches/FluidCoreConsumer").toString()
    layout.buildDirectory.set(file(consumerCache))
}
dependencies {
    compileOnly("com.ydxc20091.fluidcore:fluidcore-api:0.1.0-SNAPSHOT")
    compileOnly("com.ydxc20091.fluidcore:fluidcore-core:0.1.0-SNAPSHOT")
    compileOnly("com.ydxc20091.fluidcore:fluidcore-paper-ce:0.1.0-SNAPSHOT")
    compileOnly("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")
}
tasks.withType<JavaCompile>().configureEach { options.release.set(21); options.encoding = "UTF-8" }
tasks.register<JavaExec>("verifyStandardApi") {
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().output + configurations.compileClasspath.get()
    mainClass.set("com.ydxc20091.consumer.Consumer")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
