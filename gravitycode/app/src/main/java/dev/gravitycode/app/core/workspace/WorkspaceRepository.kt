package dev.gravitycode.app.core.workspace

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File

class WorkspaceRepository(context: Context) {
    private val root = File(context.filesDir, "workspaces").apply { mkdirs() }

    val activeWorkspace: File
        get() = File(root, "default").apply {
            mkdirs()
            ensureStarterFiles()
        }

    val activeProjectRoot: File
        get() {
            val workspace = activeWorkspace
            return workspace.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory && File(it, ".git").exists() }
                ?.maxByOrNull { it.lastModified() }
                ?: workspace
        }

    fun localStatus(): ProjectStatus {
        val project = activeProjectRoot
        val git = File(project, ".git")
        val branch = File(git, "HEAD").takeIf { it.isFile }?.readTextOrNull()?.trim()?.let { head ->
            if (head.startsWith("ref:")) head.substringAfterLast('/') else "detached"
        }
        return ProjectStatus(
            projectName = if (project == activeWorkspace) "Workspace" else project.name,
            repository = git.exists(),
            branch = branch,
        )
    }

    fun files(): List<WorkspaceEntry> {
        val project = activeProjectRoot
        return project.walkTopDown()
            .onEnter { directory -> directory == project || directory.name !in IGNORED_DIRECTORIES }
            .maxDepth(MAX_DEPTH)
            .filter { it != project }
            .filterNot { file ->
                file.name == ".git" || file.path.contains("${File.separator}.git${File.separator}")
            }
            .take(MAX_ENTRIES)
            .map { file ->
                val relative = file.relativeTo(project).path.replace(File.separatorChar, '/')
                WorkspaceEntry(
                    name = file.name,
                    relativePath = relative,
                    directory = file.isDirectory,
                    depth = relative.count { it == '/' },
                    sizeBytes = if (file.isFile) file.length() else 0L,
                    extension = file.extension.lowercase().takeIf { file.isFile && it.isNotBlank() },
                )
            }
            .sortedBy { it.relativePath.lowercase() }
            .toList()
    }

    fun preview(relativePath: String): Result<FilePreview> = runCatching {
        val project = activeProjectRoot.canonicalFile
        val target = File(project, relativePath).canonicalFile
        require(target.path.startsWith(project.path + File.separator)) { "File berada di luar workspace" }
        require(target.isFile) { "File tidak ditemukan" }

        val output = ByteArrayOutputStream()
        target.inputStream().buffered().use { input ->
            val buffer = ByteArray(8 * 1024)
            var remaining = PREVIEW_LIMIT_BYTES
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
        val bytes = output.toByteArray()
        val binary = bytes.take(4096).any { it == 0.toByte() }
        FilePreview(
            path = relativePath,
            content = if (binary) "Binary file preview is not available." else String(bytes, Charsets.UTF_8),
            sizeBytes = target.length(),
            truncated = target.length() > bytes.size,
            binary = binary,
        )
    }

    private fun File.ensureStarterFiles() {
        val readme = File(this, "README.md")
        if (!readme.exists()) {
            readme.writeText("# GravityCode workspace\n\nWorkspace lokal untuk sesi Antigravity CLI.\n")
        }
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    companion object {
        private const val MAX_DEPTH = 8
        private const val MAX_ENTRIES = 350
        private const val PREVIEW_LIMIT_BYTES = 160 * 1024
        private val IGNORED_DIRECTORIES = setOf(
            ".git",
            ".gradle",
            ".idea",
            ".next",
            ".cache",
            "node_modules",
            "build",
            "dist",
            "coverage",
            "out",
            "target",
        )
    }
}

data class WorkspaceEntry(
    val name: String,
    val relativePath: String,
    val directory: Boolean,
    val depth: Int,
    val sizeBytes: Long,
    val extension: String?,
)

data class FilePreview(
    val path: String,
    val content: String,
    val sizeBytes: Long,
    val truncated: Boolean,
    val binary: Boolean,
)

data class ProjectStatus(
    val projectName: String,
    val repository: Boolean,
    val branch: String? = null,
    val changedFiles: Int = 0,
    val ahead: Int = 0,
    val behind: Int = 0,
    val head: String? = null,
    val latestCommit: String? = null,
    val statusKnown: Boolean = false,
)
