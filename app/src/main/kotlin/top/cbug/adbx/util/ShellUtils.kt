package top.cbug.adbx.util

object ShellUtils {

    fun execute(command: String): Result {
        return try {
            val parts = command.split("\\s+".toRegex())
            val proc = ProcessBuilder(parts)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            Result(proc.exitValue(), out)
        } catch (e: Exception) {
            Result(-1, e.message ?: "")
        }
    }

    fun executeSu(command: String): Result {
        val suVariants = listOf("su -c", "su")
        for (su in suVariants) {
            val fullCmd = "$su $command"
            val result = execute(fullCmd)
            if (result.exitCode == 0) return result
        }
        return Result(-1, "su not available")
    }

    fun hasRoot(): Boolean {
        val result = executeSu("id")
        return result.exitCode == 0 && result.output.contains("uid=0")
    }

    data class Result(val exitCode: Int, val output: String) {
        fun isSuccess(): Boolean = exitCode == 0
    }
}
