import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    base
    id("com.gradleup.shadow") version "9.0.0" apply false
}

allprojects {
    group = "com.ydxc20091.fluidcore"
    version = "0.1.0-SNAPSHOT"
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.momirealms.net/releases/")
        maven("https://repo.momirealms.net/snapshots/")
    }
}

// Java 21 on Windows decodes launcher argument files using the platform charset.
// ASCII build paths keep test workers usable when the source directory contains Chinese.
val windowsUnicodePath = System.getProperty("os.name").startsWith("Windows") && rootDir.path.any { it.code > 127 }
if (windowsUnicodePath || providers.gradleProperty("fluidcoreBuildRoot").isPresent) {
    val cacheRoot = providers.gradleProperty("fluidcoreBuildRoot").orNull
        ?: rootDir.toPath().root.resolve("DevCaches/FluidCoreBuild").toString()
    allprojects { layout.buildDirectory.set(file("$cacheRoot/${project.name}")) }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        withSourcesJar()
        withJavadocJar()
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
    }
    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).apply {
            encoding = "UTF-8"
            addStringOption("Xdoclint:none", "-quiet")
        }
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
        testLogging { events("failed", "skipped") }
    }
    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.12.2")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        manifest.attributes["Implementation-Vendor"] = "ydxc20091"
        manifest.attributes["Implementation-Version"] = project.version
        from(rootProject.file(if (project.name == "api") "LICENSE-API" else "LICENSE")) {
            into("META-INF/licenses")
            rename { if (project.name == "api") "FluidCore-API-Apache-2.0.txt" else "FluidCore-GPL-3.0.txt" }
        }
    }
    extensions.configure<PublishingExtension> {
        publications.create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "fluidcore-${project.name}"
            pom {
                name.set("FluidCore ${project.name}")
                description.set("Independent CraftEngine fluid API by ydxc20091")
                developers { developer { id.set("ydxc20091"); name.set("ydxc20091") } }
                licenses { license {
                    name.set(if (project.name == "api") "Apache-2.0" else "GPL-3.0-only")
                    url.set(if (project.name == "api") "https://www.apache.org/licenses/LICENSE-2.0" else "https://www.gnu.org/licenses/gpl-3.0.html")
                } }
            }
        }
    }
}

project(":core") { dependencies { "api"(project(":api")) } }

val configuredCeJar = providers.gradleProperty("ceJar")
    .orElse(providers.environmentVariable("FLUIDCORE_CE_JAR"))
val ceJarFile = configuredCeJar.orNull?.let(::file)
val verifyCeJar by tasks.registering {
    if (ceJarFile != null) inputs.file(ceJarFile)
    doLast {
        check(ceJarFile != null && ceJarFile.isFile) {
            "A pinned CraftEngine 26.9.2 or 26.10 JAR is required. Supply -PceJar=/absolute/path/CraftEngine.jar."
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(ceJarFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest in setOf(providers.gradleProperty("ceSha256").get(), providers.gradleProperty("ceSnapshotSha256").get())) {
            "CraftEngine JAR checksum mismatch. Use a pinned 26.9.2 release or 26.10 snapshot."
        }
    }
}
val cacheCeJar by tasks.registering(Copy::class) {
    dependsOn(verifyCeJar)
    if (ceJarFile != null) from(ceJarFile)
    into(layout.buildDirectory.dir("ce/baseline"))
    rename { "craft-engine-pinned.jar" }
    onlyIf { ceJarFile != null }
}
val pinnedCeFiles = files(layout.buildDirectory.file("ce/baseline/craft-engine-pinned.jar")).builtBy(cacheCeJar)
val nativeCeJarFile = providers.gradleProperty("ceNativeJar").orNull?.let(::file)
    ?: ceJarFile?.takeIf { providers.gradleProperty("ceSnapshotSha256").get() == MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte) } }
val verifyNativeCeJar by tasks.registering {
    if (nativeCeJarFile != null) inputs.file(nativeCeJarFile)
    doLast {
        check(nativeCeJarFile != null && nativeCeJarFile.isFile) {
            "Building optional native sleep support requires -PceNativeJar=/absolute/path/craft-engine-paper-plugin-26.10-SNAPSHOT.jar."
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(nativeCeJarFile.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == providers.gradleProperty("ceSnapshotSha256").get()) { "Optional native adapter requires the pinned CE 26.10 snapshot." }
    }
}
val cacheNativeCeJar by tasks.registering(Copy::class) {
    dependsOn(verifyNativeCeJar)
    if (nativeCeJarFile != null) from(nativeCeJarFile)
    into(layout.buildDirectory.dir("ce/native"))
    rename { "craft-engine-native-sleep.jar" }
}
val nativeCeFiles = files(layout.buildDirectory.file("ce/native/craft-engine-native-sleep.jar")).builtBy(cacheNativeCeJar)
val ceProxy = layout.buildDirectory.file("ce/baseline-proxy/craft-engine-proxy.jar")
val nativeCeProxy = layout.buildDirectory.file("ce/native-proxy/craft-engine-proxy.jar")
val extractNativeCeProxy by tasks.registering {
    dependsOn(verifyNativeCeJar)
    if (nativeCeJarFile != null) inputs.file(nativeCeJarFile)
    outputs.file(nativeCeProxy)
    doLast {
        ZipFile(nativeCeJarFile!!).use { zip ->
            val entry = zip.getEntry("proxy.jarinjar") ?: error("Pinned native CE JAR has no proxy.jarinjar")
            val output = nativeCeProxy.get().asFile
            output.parentFile.mkdirs()
            zip.getInputStream(entry).use { input -> output.outputStream().use { input.copyTo(it) } }
        }
    }
}
val extractCeProxy by tasks.registering {
    dependsOn(verifyCeJar)
    if (ceJarFile != null) inputs.file(ceJarFile)
    outputs.file(ceProxy)
    onlyIf { ceJarFile != null }
    doLast {
        ZipFile(ceJarFile!!).use { zip ->
            val entry = zip.getEntry("proxy.jarinjar") ?: error("Pinned CE JAR has no proxy.jarinjar")
            val output = ceProxy.get().asFile
            output.parentFile.mkdirs()
            zip.getInputStream(entry).use { input -> output.outputStream().use { input.copyTo(it) } }
        }
    }
}

val ceAdventure = configurations.create("ceAdventure")
dependencies { add(ceAdventure.name, "net.kyori:adventure-api:5.2.0") }
val prepareCeLibraries = project(":paper-ce").tasks.register<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("prepareCeLibraries") {
    configurations = listOf(ceAdventure)
    archiveFileName.set("ce-adventure-5.2.0-compile-only.jar")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("ce"))
    relocate("net.kyori", "net.momirealms.craftengine.libraries")
}
val ceLibraryFiles = files(prepareCeLibraries.flatMap { it.archiveFile }).builtBy(prepareCeLibraries)

listOf(":paper-ce", ":examples").forEach { path -> project(path) {
    dependencies {
        "compileOnly"("io.papermc.paper:paper-api:${providers.gradleProperty("paperVersion").get()}")
        "testImplementation"("io.papermc.paper:paper-api:${providers.gradleProperty("paperVersion").get()}")
        "compileOnly"(ceLibraryFiles)
        "testImplementation"(ceLibraryFiles)
        if (ceJarFile != null) {
            "compileOnly"(pinnedCeFiles)
            "compileOnly"(files(ceProxy).builtBy(extractCeProxy))
            "testImplementation"(pinnedCeFiles)
            "testImplementation"(files(ceProxy).builtBy(extractCeProxy))
        }
    }
    tasks.withType<JavaCompile>().configureEach { dependsOn(verifyCeJar) }
    tasks.withType<ProcessResources>().configureEach {
        inputs.property("version", project.version)
        filesMatching(listOf("paper-plugin.yml", "plugin.yml")) { expand("version" to project.version) }
    }
} }

project(":paper-ce") {
    apply(plugin = "com.gradleup.shadow")
    dependencies {
        "api"(project(":api"))
        "implementation"(project(":core"))
        "implementation"("org.bstats:bstats-bukkit:${providers.gradleProperty("bstatsVersion").get()}")
        "implementation"("net.momirealms:sparrow-yaml:${providers.gradleProperty("sparrowYamlVersion").get()}")
        "implementation"("net.momirealms:sparrow-ui:${providers.gradleProperty("sparrowUiVersion").get()}") { isTransitive = false }
    }
    val sources = extensions.getByType<SourceSetContainer>()
    val nativeSource = sources.create("nativeCe")
    nativeSource.compileClasspath = nativeCeFiles + sources.getByName("main").output + sources.getByName("main").compileClasspath
    tasks.named<JavaCompile>(nativeSource.compileJavaTaskName) { dependsOn(verifyNativeCeJar) }
    dependencies { "testRuntimeOnly"(nativeSource.output) }
    if (providers.gradleProperty("ceTestRuntime").orNull == "snapshot") {
        tasks.withType<Test>().configureEach {
            val baselineJar = rootProject.layout.buildDirectory.file("ce/baseline/craft-engine-pinned.jar").get().asFile
            classpath = classpath.filter { it != baselineJar && it != ceProxy.get().asFile }
                .plus(nativeCeFiles).plus(files(nativeCeProxy).builtBy(extractNativeCeProxy))
        }
    }
    tasks.named<Jar>("sourcesJar") { from(nativeSource.allSource) }
    tasks.named<Jar>("jar") { archiveClassifier.set("thin"); from(nativeSource.output) }
    tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
        from(nativeSource.output)
        archiveBaseName.set("FluidCore")
        archiveClassifier.set("")
        relocate("net.momirealms.sparrow.yaml", "com.ydxc20091.fluidcore.libs.yaml")
        relocate("net.momirealms.sparrow.ui", "com.ydxc20091.fluidcore.libs.ui")
        relocate("org.bstats", "com.ydxc20091.fluidcore.libs.bstats")
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        mergeServiceFiles()
        from(rootProject.file("THIRD-PARTY-NOTICES.md")) { into("META-INF") }
        from(rootProject.file("LICENSES")) { into("META-INF/licenses") }
    }
    tasks.named("assemble") { dependsOn("shadowJar") }
}
project(":examples") {
    dependencies {
        "compileOnly"(project(":api"))
        "compileOnly"(project(":core"))
        "compileOnly"(project(":paper-ce"))
    }
    tasks.named<Jar>("jar") { archiveBaseName.set("FluidCore-Examples") }
}
project(":benchmarks") {
    dependencies {
        "implementation"(project(":core"))
        "implementation"("org.openjdk.jmh:jmh-core:1.37")
        "annotationProcessor"("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    }
    tasks.register<JavaExec>("jmh") {
        group = "verification"
        description = "Runs reproducible JMH benchmarks and writes raw JSON."
        classpath = project.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
        mainClass.set("org.openjdk.jmh.Main")
        val resultFile = layout.buildDirectory.file("results/jmh.json")
        doFirst { resultFile.get().asFile.parentFile.mkdirs() }
        val options = providers.gradleProperty("jmhArgs").orNull?.split(" ")?.filter(String::isNotBlank)
            ?: listOf("-f", "3", "-wi", "5", "-i", "10", "-w", "1s", "-r", "1s")
        args(options)
        args("-prof", "gc", "-rf", "json", "-rff", resultFile.get().asFile.absolutePath)
        javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    }

}
tasks.named("build") { dependsOn(subprojects.map { it.tasks.named("build") }) }
val distributionDirectory = providers.gradleProperty("fluidcoreDistributionRoot").orNull?.let(::file)
    ?: rootProject.file("dist")
tasks.register<Copy>("distribution") {
    group = "build"
    dependsOn(":paper-ce:shadowJar", ":examples:jar", ":api:jar", ":api:sourcesJar", ":core:jar", ":core:sourcesJar")
    from(project(":paper-ce").tasks.named("shadowJar"))
    from(project(":examples").tasks.named("jar"))
    from(project(":api").tasks.named("jar"), project(":api").tasks.named("sourcesJar"))
    from(project(":core").tasks.named("jar"), project(":core").tasks.named("sourcesJar"))
    into(distributionDirectory)
}

val sourceGitIgnoreFile = layout.buildDirectory.file("source-distribution/gitignore")
val prepareSourceGitIgnore by tasks.registering {
    inputs.file(rootProject.file(".gitignore"))
    outputs.file(sourceGitIgnoreFile)
    doLast {
        val destination = sourceGitIgnoreFile.get().asFile
        destination.parentFile.mkdirs()
        destination.writeBytes(rootProject.file(".gitignore").readBytes())
    }
}

val sourceDistribution = tasks.register<Zip>("sourceDistribution") {
    dependsOn(prepareSourceGitIgnore)
    from(sourceGitIgnoreFile) { rename { ".gitignore" } }
    group = "build"
    description = "Packages buildable corresponding source, examples, notices and verification records."
    archiveFileName.set("FluidCore-${project.version}-sources.zip")
    destinationDirectory.set(distributionDirectory)
    from(rootProject.projectDir) {
        includeEmptyDirs = false
        exclude(".git/**", ".gradle/**", "**/.gradle/**", ".local/**", "dist/**", "**/build/**", "**/__pycache__/**", ".idea/**", "*.iml", "**/*.log")
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.named("distribution") { dependsOn(sourceDistribution) }
tasks.register<Copy>("collectVerificationResults") {
    group = "verification"
    from(project(":benchmarks").layout.buildDirectory.file("results/jmh.json")) { rename { "jmh-native.json" } }
    listOf(":core", ":paper-ce").forEach { path ->
        from(project(path).layout.buildDirectory.dir("test-results/test")) { include("*.xml"); into("tests") }
    }
    into(rootProject.file("verification/results"))
}
