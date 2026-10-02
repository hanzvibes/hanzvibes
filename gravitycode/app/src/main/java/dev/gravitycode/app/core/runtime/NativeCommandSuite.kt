package dev.gravitycode.app.core.runtime

import android.content.Context
import java.io.File

/** Android-native PRoot launcher packaged inside the APK as executable jniLibs. */
class NativeCommandSuite(
    private val context: Context,
    private val runtimeDirectory: File,
) {
    data class Paths(
        val nativeDirectory: File,
        val proot: File,
        val loader: File,
        val loader32: File,
        val home: File,
        val tmp: File,
    ) {
        fun hostEnvironment(): Map<String, String> =
            mapOf(
                "LD_LIBRARY_PATH" to nativeDirectory.absolutePath,
                "PROOT_LOADER" to loader.absolutePath,
                "PROOT_LOADER_32" to loader32.absolutePath,
                "HOME" to home.absolutePath,
                "TMPDIR" to tmp.absolutePath,
                "PATH" to "/system/bin:/system/xbin",
            )
    }

    fun resolve(): Paths {
        val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
        val proot = File(nativeDirectory, "libgravity_proot.so")
        val loader = File(nativeDirectory, "libgravity_proot_loader.so")
        val loader32 = File(nativeDirectory, "libgravity_proot_loader32.so")
        require(proot.isFile && proot.canExecute()) { "PRoot launcher tidak tersedia di APK" }
        require(loader.isFile && loader.canExecute()) { "PRoot loader tidak tersedia di APK" }
        require(loader32.isFile && loader32.canExecute()) { "PRoot 32-bit loader tidak tersedia di APK" }
        val state = File(runtimeDirectory, "command-suite").apply { mkdirs() }
        return Paths(
            nativeDirectory = nativeDirectory,
            proot = proot,
            loader = loader,
            loader32 = loader32,
            home = File(state, "home").apply { mkdirs() },
            tmp = File(state, "tmp").apply { mkdirs() },
        )
    }
}
