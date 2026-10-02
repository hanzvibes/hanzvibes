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
            workspacePath = if (project == activeWorkspace) "/workspace" else "/workspace/${project.name}",
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
            .filterNot { file -> file.name == ".git" || file.path.contains("${File.separator}.git${File.separator}") }
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
        val target = resolveProjectFile(relativePath)
        require(target.isFile) { "File tidak ditemukan" }
        val extension = target.extension.lowercase()
        if (extension in IMAGE_EXTENSIONS && target.length() <= IMAGE_PREVIEW_LIMIT_BYTES) {
            val bytes = target.readBytes()
            return@runCatching FilePreview(
                path = relativePath,
                content = "",
                sizeBytes = target.length(),
                truncated = false,
                binary = true,
                kind = PreviewKind.IMAGE,
                imageBytes = bytes,
            )
        }

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
            kind = if (binary) PreviewKind.BINARY else PreviewKind.TEXT,
        )
    }

    fun saveText(relativePath: String, content: String): Result<FilePreview> = runCatching {
        require(content.toByteArray(Charsets.UTF_8).size <= EDIT_LIMIT_BYTES) { "File terlalu besar untuk editor mobile" }
        val target = resolveProjectFile(relativePath)
        require(target.isFile) { "File tidak ditemukan" }
        require(target.extension.lowercase() !in IMAGE_EXTENSIONS) { "Image tidak dapat disimpan sebagai text" }
        require(target.length() <= PREVIEW_LIMIT_BYTES) { "File terlalu besar untuk diedit dengan aman. Gunakan terminal atau agent." }
        target.writeText(content, Charsets.UTF_8)
        FilePreview(
            path = relativePath,
            content = content,
            sizeBytes = target.length(),
            truncated = false,
            binary = false,
            kind = PreviewKind.TEXT,
        )
    }

    fun safeProjectFile(relativePath: String): Result<File> = runCatching { resolveProjectFile(relativePath) }

    private fun resolveProjectFile(relativePath: String): File {
        val project = activeProjectRoot.canonicalFile
        val target = File(project, relativePath).canonicalFile
        require(target.path.startsWith(project.path + File.separator)) { "File berada di luar workspace" }
        return target
    }

    private fun File.ensureStarterFiles() {
        val readme = File(this, "README.md")
        if (!readme.exists()) {
            readme.writeText("# GravityCode workspace\n\nWorkspace lokal untuk sesi Antigravity CLI.\n")
        }
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    companion object {
        private const val MAX_DEPTH = 10
        private const val MAX_ENTRIES = 600
        private const val PREVIEW_LIMIT_BYTES = 512 * 1024
        private const val EDIT_LIMIT_BYTES = 2 * 1024 * 1024
        private const val IMAGE_PREVIEW_LIMIT_BYTES = 12L * 1024L * 1024L
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
        private val IGNORED_DIRECTORIES = setOf(
            ".git",
            ".gradle",
            ".idea",
            ".next",
            ".cache",
            ".turbo",
            ".parcel-cache",
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

enum class PreviewKind { TEXT, IMAGE, BINARY }

data class FilePreview(
    val path: String,
    val content: String,
    val sizeBytes: Long,
    val truncated: Boolean,
    val binary: Boolean,
    val kind: PreviewKind = if (binary) PreviewKind.BINARY else PreviewKind.TEXT,
    val imageBytes: ByteArray? = null,
)

data class GitChange(
    val status: String,
    val path: String,
)

data class GitDiff(
    val path: String,
    val status: String,
    val patch: String,
    val additions: Int,
    val deletions: Int,
    val binary: Boolean = false,
)

data class ProjectStatus(
    val projectName: String,
    val workspacePath: String = "/workspace",
    val repository: Boolean,
    val branch: String? = null,
    val changedFiles: Int = 0,
    val changedPaths: List<GitChange> = emptyList(),
    val ahead: Int = 0,
    val behind: Int = 0,
    val head: String? = null,
    val latestCommit: String? = null,
    val statusKnown: Boolean = false,
)