package dev.gravitycode.app.core.runtime

import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object RuntimeArchive {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifySha256(file: File, expected: String) {
        val actual = sha256(file)
        require(actual.equals(expected, ignoreCase = true)) {
            "SHA-256 mismatch untuk ${file.name}: expected $expected, got $actual"
        }
    }

    fun extractTarGz(input: InputStream, destination: File) {
        GzipCompressorInputStream(input.buffered()).use { gzip -> extractTar(gzip, destination) }
    }

    private fun extractTar(input: InputStream, destination: File) {
        destination.mkdirs()
        val canonicalRoot = destination.canonicalFile
        val pendingLinks = mutableListOf<Pair<File, String>>()
        TarArchiveInputStream(input).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val target = File(destination, entry.name).canonicalFile
                require(target.path == canonicalRoot.path || target.path.startsWith(canonicalRoot.path + File.separator)) {
                    "Archive entry escapes destination: ${entry.name}"
                }
                when {
                    entry.isDirectory -> target.mkdirs()
                    entry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        target.delete()
                        pendingLinks += target to entry.linkName
                    }
                    entry.isLink -> {
                        val source = File(destination, entry.linkName).canonicalFile
                        require(source.path == canonicalRoot.path || source.path.startsWith(canonicalRoot.path + File.separator)) {
                            "Archive hard link escapes destination: ${entry.linkName}"
                        }
                        target.parentFile?.mkdirs()
                        target.delete()
                        runCatching { Os.link(source.absolutePath, target.absolutePath) }
                            .onFailure { source.copyTo(target, overwrite = true) }
                    }
                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { output -> tar.copyTo(output) }
                        target.setReadable(true, false)
                        target.setWritable(true, true)
                        if (entry.mode and 0b001_001_001 != 0) target.setExecutable(true, false)
                    }
                }
                entry = tar.nextEntry
            }
        }

        pendingLinks.sortByDescending { (target, _) -> target.path.count { it == File.separatorChar } }
        repeat(pendingLinks.size + 1) {
            var resolved = 0
            pendingLinks.toList().forEach { (target, linkName) ->
                if (target.exists()) return@forEach
                val source = (
                    if (linkName.startsWith("/")) File(destination, linkName.removePrefix("/"))
                    else File(target.parentFile, linkName)
                ).canonicalFile
                if (source.path != canonicalRoot.path && !source.path.startsWith(canonicalRoot.path + File.separator)) return@forEach
                when {
                    source.isFile -> {
                        target.parentFile?.mkdirs()
                        source.copyTo(target, overwrite = true)
                        target.setExecutable(source.canExecute(), false)
                        resolved++
                    }
                    source.isDirectory -> {
                        source.copyRecursively(target, overwrite = true)
                        source.walkTopDown().forEach { original ->
                            val copy = File(target, original.relativeTo(source).path)
                            if (original.isFile && copy.isFile) copy.setExecutable(original.canExecute(), false)
                        }
                        resolved++
                    }
                }
            }
            pendingLinks.removeAll { it.first.exists() }
            if (resolved == 0) return@repeat
        }
    }
}
