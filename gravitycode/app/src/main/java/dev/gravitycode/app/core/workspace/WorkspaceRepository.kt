package dev.gravitycode.app.core.workspace

import android.content.Context
import java.io.File

class WorkspaceRepository(context: Context) {
    private val root = File(context.filesDir, "workspaces").apply { mkdirs() }

    val activeWorkspace: File
        get() = File(root, "default").apply {
            mkdirs()
            ensureStarterFiles()
        }

    fun files(): List<WorkspaceEntry> = activeWorkspace
        .walkTopDown()
        .maxDepth(3)
        .filter { it != activeWorkspace }
        .map {
            WorkspaceEntry(
                relativePath = it.relativeTo(activeWorkspace).path,
                directory = it.isDirectory,
            )
        }
        .sortedWith(compareBy<WorkspaceEntry> { !it.directory }.thenBy { it.relativePath })
        .toList()

    private fun File.ensureStarterFiles() {
        val readme = File(this, "README.md")
        if (!readme.exists()) {
            readme.writeText(
                """# GravityCode workspace\n\nWorkspace lokal untuk sesi Antigravity CLI.\n""".trimIndent(),
            )
        }
    }
}

data class WorkspaceEntry(
    val relativePath: String,
    val directory: Boolean,
)
