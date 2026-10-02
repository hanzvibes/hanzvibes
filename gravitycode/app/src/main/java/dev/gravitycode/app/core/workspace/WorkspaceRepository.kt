package dev.gravitycode.app.core.workspace

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File

class WorkspaceRepository(context: Context) {
    private val root = File(context.filesDir, "workspaces").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("gravitycode-workspace", Context.MODE_PRIVATE)

    val activeWorkspace: File
        get() = File(root, "default").apply {
            mkdirs()
            ensureStarterFiles()
        }

    val activeProjectRoot: File
        get() {
            val workspace = activeWorkspace
            val selected = prefs.getString(KEY_ACTIVE_PROJECT, null)
            if (!selected.isNullOrBlank()) {
                val target = File(workspace, selected)
                if (target.isDirectory) return target
            }
            val fallback = workspace.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory && File(it, ".git").exists() }
                ?.maxByOrNull { it.lastModified() }
            if (fallback != null) prefs.edit().putString(KEY_ACTIVE_PROJECT, fallback.name).apply()
            return fallback ?: workspace
        }

    fun projects(): List<ProjectSummary> {
        val active = activeProjectRoot
        return activeWorkspace.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && File(it, ".git").exists() }
            ?.map { dir ->
                val head = File(dir, ".git/HEAD").readTextOrNull()?.trim().orEmpty()
                ProjectSummary(
                    name = dir.name,
                    branch = if (head.startsWith("ref:")) head.substringAfterLast('/') else if (head.isNotBlank()) "detached" else null,
                    lastModified = dir.lastModified(),
                    active = dir.canonicalPath == active.canonicalPath,
                )
            }
            ?.sortedWith(compareByDescending<ProjectSummary> { it.active }.thenByDescending { it.lastModified })
            ?.toList()
            .orEmpty()
    }

    fun switchProject(name: String): Result<Unit> = runCatching {
        val clean = requireProjectName(name)
        val target = File(activeWorkspace, clean).canonicalFile
        require(target.isDirectory) { "Project tidak ditemukan" }
        require(File(target, ".git").exists()) { "Folder bukan Git repository" }
        prefs.edit().putString(KEY_ACTIVE_PROJECT, clean).apply()
    }

    fun markActiveProject(name: String) {
        runCatching { requireProjectName(name) }.onSuccess { prefs.edit().putString(KEY_ACTIVE_PROJECT, it).apply() }
    }

    fun deleteProject(name: String): Result<Unit> = runCatching {
        val clean = requireProjectName(name)
        val target = File(activeWorkspace, clean).canonicalFile
        require(target.parentFile?.canonicalPath == activeWorkspace.canonicalPath) { "Project invalid" }
        require(target.isDirectory) { "Project tidak ditemukan" }
        require(target.deleteRecursively()) { "Gagal menghapus project" }
        if (prefs.getString(KEY_ACTIVE_PROJECT, null) == clean) prefs.edit().remove(KEY_ACTIVE_PROJECT).apply()
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

    fun search(query: String): List<SearchHit> {
        val needle = query.trim()
        if (needle.length < 2) return emptyList()
        val project = activeProjectRoot
        val hits = mutableListOf<SearchHit>()
        project.walkTopDown()
            .onEnter { directory -> directory == project || directory.name !in IGNORED_DIRECTORIES }
            .maxDepth(MAX_DEPTH)
            .filter { it.isFile && it.length() <= SEARCH_FILE_LIMIT_BYTES }
            .filterNot { it.path.contains("${File.separator}.git${File.separator}") }
            .take(MAX_SEARCH_FILES)
            .forEach { file ->
                if (hits.size >= MAX_SEARCH_RESULTS) return@forEach
                val relative = file.relativeTo(project).path.replace(File.separatorChar, '/')
                if (relative.contains(needle, ignoreCase = true)) {
                    hits += SearchHit(relative, 0, "File name match")
                    if (hits.size >= MAX_SEARCH_RESULTS) return@forEach
                }
                if (!isLikelyText(file)) return@forEach
                runCatching {
                    file.bufferedReader().useLines { lines ->
                        lines.take(MAX_SEARCH_LINES_PER_FILE).forEachIndexed { index, line ->
                            if (hits.size < MAX_SEARCH_RESULTS && line.contains(needle, ignoreCase = true)) {
                                hits += SearchHit(relative, index + 1, line.trim().take(220))
                            }
                        }
                    }
                }
            }
        return hits.distinctBy { Triple(it.path, it.line, it.snippet) }.take(MAX_SEARCH_RESULTS)
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
        val temp = File(target.parentFile, ".${target.name}.gravity-tmp")
        temp.writeText(content, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            temp.delete()
        }
        FilePreview(
            path = relativePath,
            content = content,
            sizeBytes = target.length(),
            truncated = false,
            binary = false,
            kind = PreviewKind.TEXT,
        )
    }

    fun createPath(relativePath: String, directory: Boolean): Result<Unit> = runCatching {
        val target = resolveNewProjectFile(relativePath)
        require(!target.exists()) { "Path sudah ada" }
        require(!relativePath.split('/').contains(".git")) { "Tidak boleh membuat path di .git" }
        if (directory) require(target.mkdirs()) { "Gagal membuat folder" }
        else {
            target.parentFile?.mkdirs()
            require(target.createNewFile()) { "Gagal membuat file" }
        }
    }

    fun renamePath(oldPath: String, newPath: String): Result<Unit> = runCatching {
        val old = resolveProjectFile(oldPath)
        val target = resolveNewProjectFile(newPath)
        require(!oldPath.split('/').contains(".git") && !newPath.split('/').contains(".git")) { "Path .git dilindungi" }
        require(!target.exists()) { "Tujuan sudah ada" }
        target.parentFile?.mkdirs()
        require(old.renameTo(target)) { "Gagal rename path" }
    }

    fun deletePath(relativePath: String): Result<Unit> = runCatching {
        require(!relativePath.split('/').contains(".git")) { "Path .git dilindungi" }
        val target = resolveProjectFile(relativePath)
        require(target.deleteRecursively()) { "Gagal menghapus path" }
    }

    fun safeProjectFile(relativePath: String): Result<File> = runCatching { resolveProjectFile(relativePath) }

    private fun resolveProjectFile(relativePath: String): File {
        val project = activeProjectRoot.canonicalFile
        val target = File(project, relativePath).canonicalFile
        require(target.path.startsWith(project.path + File.separator)) { "File berada di luar workspace" }
        return target
    }

    private fun resolveNewProjectFile(relativePath: String): File {
        val clean = relativePath.trim().trimStart('/').replace('\\', '/')
        require(clean.isNotBlank()) { "Path kosong" }
        require(!clean.split('/').any { it == ".." || it.isBlank() }) { "Path invalid" }
        val project = activeProjectRoot.canonicalFile
        val target = File(project, clean).canonicalFile
        require(target.path.startsWith(project.path + File.separator)) { "Path berada di luar workspace" }
        return target
    }

    private fun isLikelyText(file: File): Boolean {
        if (file.extension.lowercase() in TEXT_EXTENSIONS) return true
        return runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(2048)
                val count = input.read(buffer)
                count <= 0 || buffer.take(count).none { it == 0.toByte() }
            }
        }.getOrDefault(false)
    }

    private fun requireProjectName(name: String): String {
        val clean = name.trim()
        require(clean.matches(Regex("[A-Za-z0-9._-]+"))) { "Nama project invalid" }
        return clean
    }

    private fun File.ensureStarterFiles() {
        val readme = File(this, "README.md")
        if (!readme.exists()) readme.writeText("# GravityCode workspace\n\nWorkspace lokal untuk sesi Antigravity CLI.\n")
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    companion object {
        private const val KEY_ACTIVE_PROJECT = "active_project"
        private const val MAX_DEPTH = 12
        private const val MAX_ENTRIES = 1200
        private const val PREVIEW_LIMIT_BYTES = 512 * 1024
        private const val EDIT_LIMIT_BYTES = 2 * 1024 * 1024
        private const val IMAGE_PREVIEW_LIMIT_BYTES = 12L * 1024L * 1024L
        private const val SEARCH_FILE_LIMIT_BYTES = 1024L * 1024L
        private const val MAX_SEARCH_FILES = 500
        private const val MAX_SEARCH_RESULTS = 120
        private const val MAX_SEARCH_LINES_PER_FILE = 20_000
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
        private val TEXT_EXTENSIONS = setOf("kt", "kts", "java", "js", "jsx", "ts", "tsx", "json", "yaml", "yml", "toml", "md", "txt", "py", "c", "h", "cpp", "cc", "html", "css", "scss", "xml", "gradle", "properties", "sh", "go", "rs", "sql")
        private val IGNORED_DIRECTORIES = setOf(".git", ".gradle", ".idea", ".next", ".cache", ".turbo", ".parcel-cache", "node_modules", "build", "dist", "coverage", "out", "target")
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

data class ProjectSummary(
    val name: String,
    val branch: String?,
    val lastModified: Long,
    val active: Boolean,
)

data class SearchHit(
    val path: String,
    val line: Int,
    val snippet: String,
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
    val staged: Boolean = false,
    val working: Boolean = true,
)

data class GitDiff(
    val path: String,
    val status: String,
    val patch: String,
    val additions: Int,
    val deletions: Int,
    val binary: Boolean = false,
)

data class GitCommit(
    val hash: String,
    val date: String,
    val subject: String,
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
