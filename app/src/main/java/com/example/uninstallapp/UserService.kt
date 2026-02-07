package com.example.uninstallapp

class UserService : IUserService.Stub() {

    override fun execCommand(command: String): String {
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
