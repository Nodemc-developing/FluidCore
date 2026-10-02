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
// ASCII build paths support source directories with non-ASCII characters.
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
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
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
    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        manifest.attributes["Implementation-Vendor"] = "ydxc20091"
        manifest.attributes["Implementation-Version"] = project.version
        from(rootProject.file(if (project.name == "api") "LICENSE-API" else "LICENSE")) {
            into("META-INF"); rename { "LICENSE" }
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
            "The pinned CraftEngine 26.10 JAR is required. Supply -PceJar=/absolute/path/craft-engine-paper-plugin-26.10-SNAPSHOT.jar."
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(ceJarFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == providers.gradleProperty("ceSha256").get()) {
            "CraftEngine JAR checksum mismatch. Use the pinned 26.10 snapshot or explicitly update the compatibility baseline."
        }
    }
}
val cacheCeJar by tasks.registering(Copy::class) {
    dependsOn(verifyCeJar)
    if (ceJarFile != null) from(ceJarFile)
    into(layout.buildDirectory.dir("ce"))
    rename { "craft-engine-pinned.jar" }
    onlyIf { ceJarFile != null }
}
val pinnedCeFiles = files(layout.buildDirectory.file("ce/craft-engine-pinned.jar")).builtBy(cacheCeJar)
val ceProxy = layout.buildDirectory.file("ce/craft-engine-proxy.jar")
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
        "compileOnly"(ceLibraryFiles)
        if (ceJarFile != null) {
            "compileOnly"(pinnedCeFiles)
            "compileOnly"(files(ceProxy).builtBy(extractCeProxy))
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
    tasks.named<Jar>("jar") { archiveClassifier.set("thin") }
    tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
        archiveBaseName.set("FluidCore")
        archiveClassifier.set("")
        relocate("net.momirealms.sparrow.yaml", "com.ydxc20091.fluidcore.libs.yaml")
        relocate("net.momirealms.sparrow.ui", "com.ydxc20091.fluidcore.libs.ui")
        relocate("org.bstats", "com.ydxc20091.fluidcore.libs.bstats")
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
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
tasks.named("build") { dependsOn(subprojects.map { it.tasks.named("build") }) }
tasks.register<Copy>("distribution") {
    group = "build"
    dependsOn(":paper-ce:shadowJar", ":examples:jar", ":api:jar", ":api:sourcesJar", ":core:jar", ":core:sourcesJar")
    from(project(":paper-ce").tasks.named("shadowJar"))
    from(project(":examples").tasks.named("jar"))
    from(project(":api").tasks.named("jar"), project(":api").tasks.named("sourcesJar"))
    from(project(":core").tasks.named("jar"), project(":core").tasks.named("sourcesJar"))
    into(rootProject.file("dist"))
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
    description = "Packages buildable source, examples and license notices."
    archiveFileName.set("FluidCore-${project.version}-sources.zip")
    destinationDirectory.set(rootProject.file("dist"))
    from(rootProject.projectDir) {
        includeEmptyDirs = false
        exclude("benchmarks/**", "verification/**", "**/src/test/**", ".git/**", ".gradle/**", "**/.gradle/**", ".local/**", "dist/**", "**/build/**", "**/__pycache__/**", ".idea/**", "*.iml")
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.named("distribution") { dependsOn(sourceDistribution) }
