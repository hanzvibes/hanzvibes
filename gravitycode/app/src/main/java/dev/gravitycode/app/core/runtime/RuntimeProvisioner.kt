package dev.gravitycode.app.core.runtime

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class RuntimeProvisioner(private val context: Context) {
    val runtimeDirectory = File(context.filesDir, "gravity-runtime-v2").apply { mkdirs() }
    val rootfs = File(runtimeDirectory, "alpine")
    private val cache = File(runtimeDirectory, "cache").apply { mkdirs() }
    private val marker = File(runtimeDirectory, ".ready")

    fun suite(): NativeCommandSuite.Paths = NativeCommandSuite(context, runtimeDirectory).resolve()

    fun agyBinary(): File = File(rootfs, "usr/local/bin/agy")

    fun isReady(): Boolean =
        marker.readTextOrNull()?.trim() == READY_MARKER &&
            rootfs.isDirectory && agyBinary().isFile && agyBinary().canExecute() &&
            runCatching { suite() }.isSuccess

    fun statusMessage(): String = when {
        isReady() -> "Antigravity CLI $AGY_VERSION siap di runtime lokal"
        Build.SUPPORTED_ABIS.none { it == "arm64-v8a" } -> "v0.2 saat ini membutuhkan Android ARM64"
        else -> "Runtime belum disiapkan. Tap Setup runtime sekali untuk memasang engine lokal."
    }

    suspend fun install(onProgress: (Float, String) -> Unit) = withContext(Dispatchers.IO) {
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) { "Perangkat ini bukan ARM64" }
        require(runtimeDirectory.usableSpace >= MIN_FREE_BYTES) { "Butuh minimal 350 MB ruang kosong untuk runtime" }
        val suite = suite()
        if (isReady()) {
            onProgress(1f, "Runtime sudah siap")
            return@withContext
        }

        onProgress(0.02f, "Mengunduh Alpine Linux mini rootfs")
        val alpineArchive = File(cache, ALPINE_FILE)
        val alpineSha = downloadText("$ALPINE_URL.sha256").trim().split(Regex("\\s+"), limit = 2).first()
        if (!alpineArchive.isFile || runCatching { RuntimeArchive.verifySha256(alpineArchive, alpineSha) }.isFailure) {
            download(ALPINE_URL, alpineArchive) { fraction -> onProgress(0.02f + fraction * 0.18f, "Mengunduh Alpine Linux") }
            RuntimeArchive.verifySha256(alpineArchive, alpineSha)
        }

        onProgress(0.22f, "Mengekstrak Linux environment")
        val staging = File(runtimeDirectory, "alpine.new-${System.nanoTime()}").apply { mkdirs() }
        try {
            alpineArchive.inputStream().use { RuntimeArchive.extractTarGz(it, staging) }
            configureRootfs(staging)
            rootfs.deleteRecursively()
            require(staging.renameTo(rootfs)) { "Gagal mengaktifkan Alpine rootfs" }
        } finally {
            staging.deleteRecursively()
        }

        onProgress(0.35f, "Memasang Git, Node.js, Python dan PTY tools")
        installGuestPackages(suite)
        onProgress(0.62f, "Mengunduh Antigravity CLI $AGY_VERSION")
        installAntigravity { fraction -> onProgress(0.62f + fraction * 0.32f, "Mengunduh Antigravity CLI $AGY_VERSION") }

        File(rootfs, "root/.config/antigravity").mkdirs()
        File(rootfs, "root/.gemini").mkdirs()
        marker.writeText(READY_MARKER)
        onProgress(1f, "Runtime siap")
    }

    private fun configureRootfs(target: File) {
        listOf("root", "tmp", "workspace", "dev", "proc", "sys", "system").forEach { File(target, it).mkdirs() }
        File(target, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        File(target, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(target, "etc/apk/repositories").writeText(
            "https://dl-cdn.alpinelinux.org/alpine/v3.22/main\n" +
                "https://dl-cdn.alpinelinux.org/alpine/v3.22/community\n",
        )
    }

    private fun installGuestPackages(suite: NativeCommandSuite.Paths) {
        val command = baseProotCommand(suite, rootfs) + listOf(
            "-w", "/root", "/bin/sh", "-lc",
            "export HOME=/root TMPDIR=/tmp PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; " +
                "apk update && apk add --no-cache ca-certificates bash git curl ripgrep openssh-client python3 nodejs npm util-linux-misc libstdc++ libgcc",
        )
        val log = File(runtimeDirectory, "guest-install.log")
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(log))
            .apply { environment().putAll(hostEnvironment(suite)) }
            .start()
        val completed = process.waitFor(8, java.util.concurrent.TimeUnit.MINUTES)
        if (!completed) process.destroyForcibly()
        require(completed && process.exitValue() == 0) {
            "Gagal memasang tool Linux: ${log.readTextOrNull()?.takeLast(2400).orEmpty()}"
        }
        require(File(rootfs, "usr/bin/script").isFile) { "PTY utility /usr/bin/script gagal dipasang" }
    }

    private fun installAntigravity(onProgress: (Float) -> Unit) {
        val archive = File(cache, AGY_FILE)
        if (!archive.isFile || runCatching { RuntimeArchive.verifySha256(archive, AGY_SHA256) }.isFailure) {
            download(AGY_URL, archive, onProgress)
            RuntimeArchive.verifySha256(archive, AGY_SHA256)
        } else {
            onProgress(1f)
        }
        val extraction = File(runtimeDirectory, "agy-extract-${System.nanoTime()}").apply { mkdirs() }
        try {
            archive.inputStream().use { RuntimeArchive.extractTarGz(it, extraction) }
            val source = extraction.walkTopDown().firstOrNull { it.isFile && (it.name == "agy" || it.name == "antigravity") }
                ?: error("Archive resmi Antigravity tidak berisi binary agy")
            val destination = agyBinary()
            destination.parentFile?.mkdirs()
            source.copyTo(destination, overwrite = true)
            require(destination.setExecutable(true, false) || destination.canExecute()) { "Gagal menandai agy executable" }
        } finally {
            extraction.deleteRecursively()
        }
    }

    private fun download(url: String, destination: File, onProgress: (Float) -> Unit = {}) {
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, destination.name + ".partial")
        partial.delete()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", "GravityCode-Android/0.2")
        }
        connection.connect()
        require(connection.responseCode in 200..299) { "Download HTTP ${connection.responseCode}: $url" }
        val total = connection.contentLengthLong
        connection.inputStream.buffered().use { input ->
            partial.outputStream().buffered().use { output ->
                val buffer = ByteArray(128 * 1024)
                var written = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                    if (total > 0) onProgress((written.toDouble() / total).toFloat().coerceIn(0f, 1f))
                }
            }
        }
        require(partial.renameTo(destination)) { "Gagal menyimpan ${destination.name}" }
        connection.disconnect()
    }

    private fun downloadText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "GravityCode-Android/0.2")
        }
        return connection.inputStream.bufferedReader().use { it.readText() }.also { connection.disconnect() }
    }

    fun baseProotCommand(suite: NativeCommandSuite.Paths, root: File = rootfs): List<String> =
        listOf(
            suite.proot.absolutePath,
            "--kill-on-exit", "--link2symlink", "-0", "-r", root.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/system",
        )

    fun hostEnvironment(suite: NativeCommandSuite.Paths): Map<String, String> =
        suite.hostEnvironment() + mapOf("PROOT_TMP_DIR" to File(runtimeDirectory, "proot-tmp").apply { mkdirs() }.absolutePath)

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    companion object {
        const val AGY_VERSION = "1.2.14"
        private const val ALPINE_FILE = "alpine-minirootfs-3.22.5-aarch64.tar.gz"
        private const val ALPINE_URL = "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/$ALPINE_FILE"
        private const val AGY_FILE = "agy_cli_linux_arm64_musl.tar.gz"
        private const val AGY_URL = "https://github.com/google-antigravity/antigravity-cli/releases/download/$AGY_VERSION/$AGY_FILE"
        private const val AGY_SHA256 = "5a5e0e6565ad5d93ab27539136ccf60dc8547371e014a11aae99cf08558e330e"
        private const val READY_MARKER = "alpine-3.22.5+agy-$AGY_VERSION"
        private const val MIN_FREE_BYTES = 350L * 1024L * 1024L
    }
}
