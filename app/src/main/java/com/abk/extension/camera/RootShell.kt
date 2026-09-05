package com.abk.extension.camera

import com.topjohnwu.superuser.Shell

internal object RootShell {
    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
    ) {
        val success: Boolean get() = exitCode == 0
    }

    @Volatile
    private var initialized = false
    private val initLock = Any()

    fun init() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            Shell.enableVerboseLogging = false
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(10)
            )
            initialized = true
        }
    }

    fun isRootAvailable(): Boolean {
        val result = run("id -u")
        return result.success && result.stdout.trim() == "0"
    }

    fun readTextFile(path: String): CommandResult = run("cat " + shellQuote(path))

    fun writeTextFile(path: String, payload: String): CommandResult =
        run("printf '%s' " + shellQuote(payload) + " > " + shellQuote(path))

    fun run(script: String, timeoutSeconds: Long = 10L): CommandResult {
        init()
        return try {
            val output = mutableListOf<String>()
            val result = createRootShell(timeoutSeconds = timeoutSeconds).use { shell ->
                shell.newJob().to(output, output).add(script).exec()
            }
            CommandResult(
                exitCode = if (result.isSuccess) 0 else 1,
                stdout = output.joinToString("\n")
            )
        } catch (t: Throwable) {
            CommandResult(exitCode = 127, stdout = t.message.orEmpty())
        }
    }

    private fun createRootShell(timeoutSeconds: Long): Shell {
        val builder = Shell.Builder.create()
            .setFlags(Shell.FLAG_MOUNT_MASTER or Shell.FLAG_REDIRECT_STDERR)
            .setTimeout(timeoutSeconds)
        val candidates = arrayOf(
            arrayOf("/data/adb/ksud", "debug", "su", "-g"),
            arrayOf("ksud", "debug", "su", "-g"),
            arrayOf("su", "-mm"),
            arrayOf("su")
        )
        candidates.forEach { command ->
            try {
                val shell = builder.build(*command)
                if (isShellRoot(shell)) return shell
                shell.close()
            } catch (_: Throwable) {
            }
        }
        val shell = builder.build()
        if (isShellRoot(shell)) return shell
        shell.close()
        throw IllegalStateException("Root shell unavailable")
    }

    private fun isShellRoot(shell: Shell): Boolean {
        val output = mutableListOf<String>()
        val result = shell.newJob().to(output, output).add("id -u").exec()
        return result.isSuccess && output.firstOrNull()?.trim() == "0"
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
