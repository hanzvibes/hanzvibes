package dev.gravitycode.app.core.runtime

import java.io.File

object AntigravitySandbox {
    fun agyCommand(
        provisioner: RuntimeProvisioner,
        workspace: File,
        arguments: List<String>,
        pty: Boolean,
    ): List<String> {
        val suite = provisioner.suite()
        return buildList {
            addAll(provisioner.baseProotCommand(suite))
            add("-b")
            add("${workspace.absolutePath}:/workspace")
            add("-w")
            add("/workspace")
            if (pty) {
                add("/usr/bin/script")
                add("-qefc")
                add("stty rows 24 cols 1000 2>/dev/null; exec " + (listOf("/usr/local/bin/agy") + arguments).joinToString(" ", transform = ::shellQuote))
                add("/dev/null")
            } else {
                add("/usr/local/bin/agy")
                addAll(arguments)
            }
        }
    }

    fun guestCommand(
        provisioner: RuntimeProvisioner,
        workspace: File,
        executable: String,
        arguments: List<String>,
    ): List<String> {
        val suite = provisioner.suite()
        return buildList {
            addAll(provisioner.baseProotCommand(suite))
            add("-b")
            add("${workspace.absolutePath}:/workspace")
            add("-w")
            add("/workspace")
            add(executable)
            addAll(arguments)
        }
    }

    fun environment(provisioner: RuntimeProvisioner): Map<String, String> {
        val suite = provisioner.suite()
        return provisioner.hostEnvironment(suite) + mapOf(
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin",
            "HOME" to "/root",
            "TERM" to "xterm-256color",
            "AGY_CLI_DISABLE_AUTO_UPDATE" to "1",
            "AGY_CLI_HIDE_ACCOUNT_INFO" to "1",
            "SSL_CERT_FILE" to "/etc/ssl/certs/ca-certificates.crt",
            "SSL_CERT_DIR" to "/etc/ssl/certs",
            "SSH_CONNECTION" to "127.0.0.1 22 127.0.0.1 22",
            "SSH_CLIENT" to "127.0.0.1 22 22",
            "SSH_TTY" to "/dev/pts/0",
        )
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
