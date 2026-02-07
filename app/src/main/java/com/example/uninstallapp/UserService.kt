package com.example.uninstallapp

class UserService : IUserService.Stub() {

    companion object {
        // Only allow pm uninstall commands with valid package names
        private val ALLOWED_COMMAND = Regex("""^pm uninstall --user 0 [a-zA-Z][a-zA-Z0-9._]*$""")
    }

    override fun execCommand(command: String): String {
        if (!ALLOWED_COMMAND.matches(command)) {
            return "-1\nCommand not allowed: $command"
        }
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            "$exitCode\n$output$error"
        } catch (e: Exception) {
            "-1\n${e.message}"
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
