import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

plugins {
    id("minestom.java-library")
    application
}

dependencies {
    api(rootProject)
    api(libs.bundles.adventure)

    implementation("io.javalin:javalin:7.2.0")
    implementation("org.xerial:sqlite-jdbc:3.50.1.0")
    implementation(libs.gson)
    implementation(libs.slf4j)

    // Pulled in only for `:web:run` (and the distribution); library consumers bring their own
    // SLF4J binding and won't see this on their compile/runtime classpath.
    "runtimeOnly"(libs.bundles.logback)

    testImplementation(project(":testing"))
}

application {
    mainClass.set("net.minestom.web.cli.Main")
    mainModule.set("net.minestom.web")

    // Javalin / Jetty are automatic modules without explicit `requires`; ALL-MODULE-PATH makes
    // the JVM resolve every module on the module path so they load.
    applicationDefaultJvmArgs = listOf("--add-modules", "ALL-MODULE-PATH")
}

tasks.named<JavaExec>("run") {
    jvmArgs("--add-modules", "ALL-MODULE-PATH")
}

val mcVersion: String = libs.versions.data.get().substringBefore("-")

/// Downloads the official Mojang client.jar for the configured Minecraft version, verifies its
/// SHA-1 against the launcher manifest, and extracts the texture / sound / lang assets that the
/// web dashboard reuses (items, blocks, mob_effect, hud sprites, en_us.json). Nothing
/// Mojang-owned is committed to source — assets land in build/generated-assets and are bundled
/// into the resources jar.
@CacheableTask
abstract class VanillaAssetsTask : DefaultTask() {
    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /// Cache root for the downloaded Mojang client.jar. Set at configuration time so the task
    /// action stays configuration-cache compatible (no `project.*` access at execution).
    @get:Input
    abstract val downloadCacheRoot: Property<String>

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile
        out.deleteRecursively(); out.mkdirs()
        val webAssets = out.resolve("web/assets").apply { mkdirs() }

        // Always write the stub registries so the resources tree is non-empty even when offline.
        webAssets.resolve("registry").mkdirs()
        webAssets.resolve("registry/items.json").writeText("{}\n")
        webAssets.resolve("registry/effects.json").writeText("{}\n")
        webAssets.resolve("lang").mkdirs()
        webAssets.resolve("README.txt").writeText(
            "Vanilla Minecraft assets for ${minecraftVersion.get()} are fetched from launchermeta.mojang.com\n" +
                    "at build time. Nothing Mojang-owned is committed to this repository.\n"
        )

        val version = minecraftVersion.get()
        val cacheRoot = File(downloadCacheRoot.get(), "minestom-web/vanilla-assets/$version")
        cacheRoot.mkdirs()
        val clientJar = cacheRoot.resolve("client.jar")
        val manifestFile = cacheRoot.resolve("client.json")

        val ok = try {
            ensureManifest(version, manifestFile)
            val (url, sha1) = readClientArtifact(manifestFile)
            ensureClientJar(clientJar, url, sha1)
            extractAssets(clientJar, webAssets)
            buildManifest(webAssets, version)
            true
        } catch (e: Exception) {
            logger.lifecycle("Vanilla assets: degraded (${e.message}). Dashboard will fall back to letter-based item icons.")
            false
        }
        if (!ok) {
            // ensure even the stub atlases exist so static file handler doesn't 404
            for (name in listOf("items.json", "blocks.json", "effects.json", "manifest.json")) {
                val f = webAssets.resolve(name)
                if (!f.exists()) f.writeText("{}\n")
            }
        } else {
            logger.lifecycle("Vanilla assets ready (mc=$version) → ${webAssets.absolutePath}")
        }
    }

    private fun ensureManifest(version: String, manifestFile: File) {
        if (manifestFile.exists() && manifestFile.length() > 0) return
        val versionManifestUrl = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
        val manifestRoot = URI(versionManifestUrl).toURL().readText()
        val versionsArrayPos = manifestRoot.indexOf("\"versions\"")
        require(versionsArrayPos >= 0) { "no versions in manifest" }
        // crude scan: find {"id":"<version>",...,"url":"..."}
        val idMarker = "\"id\": \"$version\""
        val altMarker = "\"id\":\"$version\""
        val idx = manifestRoot.indexOf(idMarker).let { if (it >= 0) it else manifestRoot.indexOf(altMarker) }
        require(idx >= 0) { "version $version not found in Mojang manifest" }
        val urlKey = "\"url\""
        val urlStart = manifestRoot.indexOf(urlKey, idx)
        val urlOpenQuote = manifestRoot.indexOf('"', urlStart + urlKey.length + 1)
        val urlCloseQuote = manifestRoot.indexOf('"', urlOpenQuote + 1)
        val url = manifestRoot.substring(urlOpenQuote + 1, urlCloseQuote)
        manifestFile.writeText(URI(url).toURL().readText())
    }

    private fun readClientArtifact(manifestFile: File): Pair<String, String> {
        val txt = manifestFile.readText()
        val clientKey = "\"client\""
        val clientPos = txt.indexOf(clientKey)
        require(clientPos >= 0) { "no client artifact in version manifest" }
        // tiny field reader
        fun stringField(after: Int, key: String): String? {
            val k = "\"$key\""
            val kp = txt.indexOf(k, after)
            if (kp < 0) return null
            val open = txt.indexOf('"', kp + k.length + 1)
            val close = txt.indexOf('"', open + 1)
            return txt.substring(open + 1, close)
        }
        val sha1 = stringField(clientPos, "sha1") ?: error("no sha1")
        val url = stringField(clientPos, "url") ?: error("no client url")
        return url to sha1
    }

    private fun ensureClientJar(target: File, url: String, expectedSha1: String) {
        if (target.exists() && sha1Of(target).equals(expectedSha1, ignoreCase = true)) return
        logger.lifecycle("Vanilla assets: downloading client.jar for ${minecraftVersion.get()}")
        URI(url).toURL().openStream().use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        val actual = sha1Of(target)
        require(actual.equals(expectedSha1, ignoreCase = true)) {
            "client.jar SHA-1 mismatch (got $actual, expected $expectedSha1)"
        }
    }

    private fun sha1Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val r = input.read(buf); if (r < 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /// Pull only the assets the dashboard needs: item textures, mob_effect textures, hud HUD
    /// sprites (heart/food/xp), gui container backgrounds, and the english lang file. We also
    /// build a flat `items.json` / `effects.json` listing what we have, so the frontend can do
    /// a fast existence check without a HEAD request.
    /// Entity texture folders used by block/item special renderers (bed, banner, chest, …).
    /// Mob directories are omitted — they are not used for inventory icons.
    private val iconEntityDirs = setOf(
        "banner", "bed", "beacon", "bell", "boat", "chest", "chest_boat", "conduit",
        "copper_golem", "creeper", "decorated_pot", "enderdragon", "end_crystal",
        "piglin", "shield", "shulker", "signs", "skeleton", "zombie",
    )

    private fun extractAssets(clientJar: File, webAssets: File) {
        val itemDir   = webAssets.resolve("textures/item").apply { mkdirs() }
        val blockDir  = webAssets.resolve("textures/block").apply { mkdirs() }
        val effectDir = webAssets.resolve("textures/mob_effect").apply { mkdirs() }
        val hudDir    = webAssets.resolve("textures/gui/sprites/hud").apply { mkdirs() }
        val guiDir    = webAssets.resolve("textures/gui/sprites").apply { mkdirs() }
        val containerDir = webAssets.resolve("textures/gui/container").apply { mkdirs() }
        val skinDir   = webAssets.resolve("textures/entity/player").apply { mkdirs() }
        val entityDir = webAssets.resolve("textures/entity").apply { mkdirs() }
        val mapDir    = webAssets.resolve("textures/map/decorations").apply { mkdirs() }
        val itemsJsonDir = webAssets.resolve("items").apply { mkdirs() }
        val blockModelsDir = webAssets.resolve("models/block").apply { mkdirs() }
        val itemModelsDir = webAssets.resolve("models/item").apply { mkdirs() }
        val langDir   = webAssets.resolve("lang").apply { mkdirs() }
        val fontDir   = webAssets.resolve("font").apply { mkdirs() }

        val items = mutableListOf<String>()
        val blocks = mutableListOf<String>()
        val effects = mutableListOf<String>()
        val hud = mutableListOf<String>()

        clientJar.inputStream().use { fis ->
            ZipInputStream(fis).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name
                    val target = when {
                        name.startsWith("assets/minecraft/textures/item/") && name.endsWith(".png") -> {
                            val base = name.substringAfterLast('/')
                            items.add(base.removeSuffix(".png"))
                            itemDir.resolve(base)
                        }
                        name.startsWith("assets/minecraft/textures/block/") && name.endsWith(".png") -> {
                            val base = name.substringAfterLast('/')
                            blocks.add(base.removeSuffix(".png"))
                            blockDir.resolve(base)
                        }
                        name.startsWith("assets/minecraft/textures/mob_effect/") && name.endsWith(".png") -> {
                            val base = name.substringAfterLast('/')
                            effects.add(base.removeSuffix(".png"))
                            effectDir.resolve(base)
                        }
                        name.startsWith("assets/minecraft/textures/gui/sprites/hud/") && name.endsWith(".png") -> {
                            val rel = name.removePrefix("assets/minecraft/textures/gui/sprites/hud/")
                            hud.add(rel.removeSuffix(".png"))
                            hudDir.resolve(rel).apply { parentFile.mkdirs() }
                        }
                        name.startsWith("assets/minecraft/textures/gui/sprites/") && name.endsWith(".png") -> {
                            val rel = name.removePrefix("assets/minecraft/textures/gui/sprites/")
                            guiDir.resolve(rel).apply { parentFile.mkdirs() }
                        }
                        name.startsWith("assets/minecraft/textures/gui/container/") && name.endsWith(".png") -> {
                            val rel = name.removePrefix("assets/minecraft/textures/gui/container/")
                            containerDir.resolve(rel).apply { parentFile.mkdirs() }
                        }
                        name.startsWith("assets/minecraft/textures/entity/player/") && name.endsWith(".png") -> {
                            val rel = name.removePrefix("assets/minecraft/textures/entity/player/")
                            skinDir.resolve(rel).apply { parentFile.mkdirs() }
                        }
                        name.startsWith("assets/minecraft/textures/entity/") && name.endsWith(".png") -> {
                            val rel = name.removePrefix("assets/minecraft/textures/entity/")
                            val topDir = rel.substringBefore('/')
                            if (topDir !in iconEntityDirs) null
                            else entityDir.resolve(rel).apply { parentFile.mkdirs() }
                        }
                        name.startsWith("assets/minecraft/textures/map/decorations/") && name.endsWith(".png") -> {
                            val rel = name.removePrefix("assets/minecraft/textures/map/decorations/")
                            mapDir.resolve(rel)
                        }
                        name.startsWith("assets/minecraft/items/") && name.endsWith(".json") -> {
                            val rel = name.removePrefix("assets/minecraft/items/")
                            itemsJsonDir.resolve(rel)
                        }
                        name.startsWith("assets/minecraft/models/block/") && name.endsWith(".json") -> {
                            val rel = name.removePrefix("assets/minecraft/models/block/")
                            blockModelsDir.resolve(rel)
                        }
                        name.startsWith("assets/minecraft/models/item/") && name.endsWith(".json") -> {
                            val rel = name.removePrefix("assets/minecraft/models/item/")
                            itemModelsDir.resolve(rel)
                        }
                        name == "assets/minecraft/lang/en_us.json" -> langDir.resolve("en_us.json")
                        name == "assets/minecraft/font/default.json" -> fontDir.resolve("default.json")
                        else -> null
                    } ?: continue
                    target.parentFile.mkdirs()
                    target.outputStream().use { out -> zip.copyTo(out) }
                }
            }
        }

        // Flat existence-check lists for the frontend.
        webAssets.resolve("items.json").writeText(toJsonArray(items.sorted()))
        webAssets.resolve("blocks.json").writeText(toJsonArray(blocks.sorted()))
        webAssets.resolve("effects.json").writeText(toJsonArray(effects.sorted()))
        webAssets.resolve("hud.json").writeText(toJsonArray(hud.sorted()))
    }

    private fun toJsonArray(items: List<String>): String =
        items.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }

    private fun buildManifest(webAssets: File, version: String) {
        webAssets.resolve("manifest.json").writeText(
            "{\"minecraftVersion\":\"$version\",\"generatedAt\":${System.currentTimeMillis()}}\n"
        )
    }
}

val generatedAssetsDir = layout.buildDirectory.dir("generated-assets")

val gradleCachesDir = gradle.gradleUserHomeDir.resolve("caches").absolutePath

val downloadVanillaAssets by tasks.registering(VanillaAssetsTask::class) {
    minecraftVersion.set(mcVersion)
    outputDir.set(generatedAssetsDir)
    downloadCacheRoot.set(gradleCachesDir)
}

sourceSets.main {
    resources.srcDir(downloadVanillaAssets.map { it.outputDir })
}

/// Build the Svelte frontend bundle. `npm ci` runs on first build (when node_modules is empty);
/// `npm run build` bundles the SPA into src/main/resources/web/app.js (the same path the Javalin
/// static-file handler serves). Gradle's input/output tracking re-runs only when sources change.
val frontendDir = layout.projectDirectory.dir("frontend")
val bundleOut   = layout.projectDirectory.file("src/main/resources/web/app.js")

/// Resolve an executable name to an absolute path at configuration time. The Gradle daemon can
/// inherit a sparse PATH, and Windows launchers commonly live beside extensionless shims that
/// `ProcessBuilder` cannot start directly. Walk PATH plus common install locations and prefer
/// native executable names for the current OS.
fun resolveExec(name: String): String {
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    val executableNames = if (isWindows && !name.contains('.')) {
        val pathExt = (System.getenv("PATHEXT") ?: ".COM;.EXE;.BAT;.CMD")
            .split(File.pathSeparatorChar, ';')
            .filter { it.isNotBlank() }
        pathExt.map { name + it.lowercase() } + pathExt.map { name + it.uppercase() } + name
    } else {
        listOf(name)
    }

    val commonDirs = if (isWindows) {
        listOfNotNull(
            System.getenv("ProgramFiles")?.let { File(it, "nodejs").absolutePath },
            System.getenv("ProgramFiles(x86)")?.let { File(it, "nodejs").absolutePath },
            System.getenv("LOCALAPPDATA")?.let { File(it, "Programs/nodejs").absolutePath },
        )
    } else {
        listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin")
    }

    val pathDirs = (System.getenv("PATH") ?: "")
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }

    return (pathDirs + commonDirs).asSequence()
        .flatMap { dir -> executableNames.asSequence().map { File(dir, it) } }
        .firstOrNull { it.isFile && (isWindows || it.canExecute()) }
        ?.absolutePath
        ?: name
}
val npm = resolveExec("npm")

fun Exec.prependExecutablePath(executable: String) {
    val executableDir = File(executable).parentFile?.absolutePath ?: return
    val pathKey = environment.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
    val currentPath = (environment[pathKey] as? String) ?: System.getenv("PATH").orEmpty()
    environment(pathKey, listOf(executableDir, currentPath).filter { it.isNotBlank() }.joinToString(File.pathSeparator))
}

val installFrontend by tasks.registering(Exec::class) {
    workingDir = frontendDir.asFile
    inputs.file(frontendDir.file("package.json"))
    inputs.file(frontendDir.file("package-lock.json"))
    outputs.file(frontendDir.dir("node_modules").file(".package-lock.json"))
    prependExecutablePath(npm)
    commandLine(npm, "ci", "--silent", "--no-audit", "--no-fund")
}

val buildFrontend by tasks.registering(Exec::class) {
    dependsOn(installFrontend)
    workingDir = frontendDir.asFile
    inputs.dir(frontendDir.dir("src"))
    inputs.file(frontendDir.file("esbuild.config.mjs"))
    inputs.file(frontendDir.file("package.json"))
    inputs.file(frontendDir.file("tsconfig.json"))
    outputs.file(bundleOut)
    prependExecutablePath(npm)
    commandLine(npm, "run", "build", "--silent")
}

tasks.named("processResources") { dependsOn(buildFrontend) }
// sourcesJar packages src/main/resources, into which buildFrontend writes the bundle, so it must
// run after the bundle exists — otherwise Gradle flags an undeclared inter-task dependency.
tasks.matching { it.name == "sourcesJar" }.configureEach { dependsOn(buildFrontend) }
