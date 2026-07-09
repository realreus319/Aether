package com.zhousl.aether.data

import com.zhousl.aether.runtime.RuntimeFilesystemTool
import com.zhousl.aether.runtime.RuntimeRouter
import com.zhousl.aether.runtime.RuntimeShellTool
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private const val MaxToolSleepDurationMillis = 10 * 60 * 1000L

data class AetherToolExecutionResult(
    val toolName: String,
    val argumentsJson: String,
    val rawOutput: String,
    val visibleOutput: String = AetherToolExecutor.sanitizeToolOutputForConversation(toolName, rawOutput),
) {
    val isError: Boolean = !AetherToolExecutor.inferToolOutputOk(visibleOutput)
}

class AetherToolExecutor(
    runtimeRouter: RuntimeRouter,
) {
    private val filesystemTool = RuntimeFilesystemTool(runtimeRouter)
    private val shellTool = RuntimeShellTool(runtimeRouter)

    suspend fun execute(
        settings: AppSettings,
        workspaceDirectory: String,
        toolName: String,
        argumentsJson: String,
        onProgress: (suspend (String) -> Unit)? = null,
    ): AetherToolExecutionResult {
        val rawOutput = when (toolName) {
            "read" -> filesystemTool.executeRead(
                settings,
                injectDefaultWorkingDirectory(argumentsJson, workspaceDirectory),
            )
            "edit" -> filesystemTool.executeEdit(
                settings,
                injectDefaultWorkingDirectory(argumentsJson, workspaceDirectory),
            )
            "write" -> filesystemTool.executeWrite(
                settings,
                injectDefaultWorkingDirectory(argumentsJson, workspaceDirectory),
            )
            "grep" -> filesystemTool.executeGrep(
                settings,
                injectDefaultWorkingDirectory(argumentsJson, workspaceDirectory),
            )
            "find" -> filesystemTool.executeFind(
                settings,
                injectDefaultWorkingDirectory(argumentsJson, workspaceDirectory),
            )
            "ls" -> filesystemTool.executeLs(
                settings,
                injectDefaultWorkingDirectory(argumentsJson, workspaceDirectory),
            )
            "bash" -> shellTool.execute(
                settings,
                injectDefaultWorkingDirectory(argumentsJson, workspaceDirectory),
                onProgress = onProgress,
            )
            "fetch_bash_output" -> shellTool.fetch(settings, argumentsJson)
            "kill_bash" -> shellTool.kill(settings, argumentsJson)
            "sleep" -> executeSleep(argumentsJson)
            else -> unknownToolOutput(toolName)
        }
        return AetherToolExecutionResult(
            toolName = toolName,
            argumentsJson = argumentsJson,
            rawOutput = rawOutput,
        )
    }

    private suspend fun executeSleep(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Arguments were not valid JSON.")
            }.toString()

        val durationMillis = arguments.takeIf { it.has("duration_ms") }?.optLong("duration_ms")
            ?: arguments.takeIf { it.has("durationMs") }?.optLong("durationMs")
            ?: -1L

        if (durationMillis < 0L) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "Missing required 'duration_ms' argument.")
            }.toString()
        }
        if (durationMillis > MaxToolSleepDurationMillis) {
            return JSONObject().apply {
                put("ok", false)
                put("errmsg", "'duration_ms' must be between 0 and $MaxToolSleepDurationMillis.")
            }.toString()
        }

        delay(durationMillis)
        return JSONObject().apply {
            put("ok", true)
            put("duration_ms", durationMillis)
            put("stdout", "Slept for ${durationMillis}ms.")
        }.toString()
    }

    companion object {
        val hostToolNames: Set<String> = setOf(
            "read",
            "edit",
            "write",
            "grep",
            "find",
            "ls",
            "bash",
            "fetch_bash_output",
            "kill_bash",
            "sleep",
        )

        fun supports(toolName: String): Boolean = toolName in hostToolNames

        fun hostToolDefinitions(): JSONArray = JSONArray().apply {
            toolDefinition(
                name = "read",
                description = "Read a text file from the selected local runtime with optional line-based offset and limit. path accepts ~ or ~/... for that runtime's home directory.",
                properties = JSONObject().apply {
                    put("environment", runtimeEnvironmentProperty())
                    put("path", stringProperty("The file path to read."))
                    put("offset", integerProperty("Optional zero-based line offset to start reading from."))
                    put("limit", integerProperty("Optional maximum number of lines to return."))
                    put("showLineNumbers", booleanProperty("Whether stdout should prefix each returned line with its original 1-based line number."))
                    put("show_line_numbers", booleanProperty("Alias of showLineNumbers."))
                    put("workingDirectory", stringProperty("Optional working directory used to resolve relative paths."))
                    put("working_directory", stringProperty("Alias of workingDirectory."))
                },
                required = listOf("path"),
                executionMode = "parallel",
            ).also(::put)
            toolDefinition(
                name = "edit",
                description = "Precisely edit a text file in the selected local runtime using exact oldText/newText replacements. For multiple edits use only edits[]. path accepts ~ or ~/... for that runtime's home directory.",
                properties = JSONObject().apply {
                    put("environment", runtimeEnvironmentProperty())
                    put("path", stringProperty("The file path to edit."))
                    put("oldText", stringProperty("For a single edit only, the exact text to replace. Omit this when using edits[]."))
                    put("newText", stringProperty("For a single edit only, the replacement text. Omit this when using edits[]."))
                    put(
                        "edits",
                        JSONObject().apply {
                            put("type", "array")
                            put("description", "For multiple edits only, a list of non-overlapping precise replacements.")
                            put(
                                "items",
                                JSONObject().apply {
                                    put("type", "object")
                                    put(
                                        "properties",
                                        JSONObject().apply {
                                            put("oldText", stringProperty("The exact text to replace."))
                                            put("newText", stringProperty("The replacement text."))
                                        },
                                    )
                                    put("required", JSONArray().put("oldText").put("newText"))
                                    put("additionalProperties", false)
                                },
                            )
                        },
                    )
                    put("workingDirectory", stringProperty("Optional working directory used to resolve relative paths."))
                    put("working_directory", stringProperty("Alias of workingDirectory."))
                },
                required = listOf("path"),
                executionMode = "sequential",
            ).also(::put)
            toolDefinition(
                name = "write",
                description = "Create a new text file or completely overwrite an existing text file in the selected local runtime. path accepts ~ or ~/... for that runtime's home directory.",
                properties = JSONObject().apply {
                    put("environment", runtimeEnvironmentProperty())
                    put("path", stringProperty("The file path to create or overwrite."))
                    put("content", stringProperty("The full file contents to write."))
                    put("workingDirectory", stringProperty("Optional working directory used to resolve relative paths."))
                    put("working_directory", stringProperty("Alias of workingDirectory."))
                },
                required = listOf("path", "content"),
                executionMode = "sequential",
            ).also(::put)
            toolDefinition(
                name = "grep",
                description = "Search for text or a regex pattern inside a file or directory tree in the selected local runtime. path accepts ~ or ~/... for that runtime's home directory.",
                properties = JSONObject().apply {
                    put("environment", runtimeEnvironmentProperty())
                    put("path", stringProperty("The file or directory path to search."))
                    put("pattern", stringProperty("The text or regex pattern to search for."))
                    put("isRegex", booleanProperty("Whether pattern should be treated as a regex."))
                    put("caseSensitive", booleanProperty("Whether the search should be case-sensitive."))
                    put("maxResults", integerProperty("Optional maximum number of matches to return."))
                    put("workingDirectory", stringProperty("Optional working directory used to resolve relative paths."))
                    put("working_directory", stringProperty("Alias of workingDirectory."))
                },
                required = listOf("path", "pattern"),
                executionMode = "parallel",
            ).also(::put)
            toolDefinition(
                name = "find",
                description = "Find files or directories by glob pattern in the selected local runtime. path accepts ~ or ~/... for that runtime's home directory.",
                properties = JSONObject().apply {
                    put("environment", runtimeEnvironmentProperty())
                    put("path", stringProperty("The directory path to search in."))
                    put("pattern", stringProperty("The glob pattern to match, such as *.kt."))
                    put("type", stringProperty("Optional match type: any, file, or directory."))
                    put("caseSensitive", booleanProperty("Whether the glob match should be case-sensitive."))
                    put("maxDepth", integerProperty("Optional maximum search depth."))
                    put("maxResults", integerProperty("Optional maximum number of results to return."))
                    put("workingDirectory", stringProperty("Optional working directory used to resolve relative paths."))
                    put("working_directory", stringProperty("Alias of workingDirectory."))
                },
                required = listOf("path", "pattern"),
                executionMode = "parallel",
            ).also(::put)
            toolDefinition(
                name = "ls",
                description = "List the contents of a directory or inspect a file path in the selected local runtime. path accepts ~ or ~/... for that runtime's home directory.",
                properties = JSONObject().apply {
                    put("environment", runtimeEnvironmentProperty())
                    put("path", stringProperty("The file or directory path to list."))
                    put("recursive", booleanProperty("Whether to list recursively."))
                    put("includeHidden", booleanProperty("Whether to include hidden files and directories."))
                    put("maxDepth", integerProperty("Optional maximum recursion depth."))
                    put("maxEntries", integerProperty("Optional maximum number of entries to return."))
                    put("workingDirectory", stringProperty("Optional working directory used to resolve relative paths."))
                    put("working_directory", stringProperty("Alias of workingDirectory."))
                },
                required = listOf("path"),
                executionMode = "parallel",
            ).also(::put)
            toolDefinition(
                name = "bash",
                description = "Execute a bash command in the selected local runtime. If it is still running after the live window, the tool returns status=running and a runtime-prefixed run_id.",
                properties = JSONObject().apply {
                    put("environment", runtimeEnvironmentProperty())
                    put("command", stringProperty("The bash command or script to execute."))
                    put("working_directory", stringProperty("Optional working directory inside the selected runtime."))
                    put("workingDirectory", stringProperty("Alias of working_directory."))
                },
                required = listOf("command"),
                executionMode = "sequential",
            ).also(::put)
            toolDefinition(
                name = "fetch_bash_output",
                description = "Fetch the latest stdout/stderr snapshot and status for a previously started long-running bash command by runtime-prefixed run_id.",
                properties = JSONObject().apply {
                    put("run_id", stringProperty("The run_id returned by bash when it reported status=running."))
                    put("runId", stringProperty("Alias of run_id."))
                    put("environment", runtimeEnvironmentProperty())
                    put("tail_bytes", integerProperty("Optional maximum number of bytes to return from the end of stdout and stderr."))
                    put("tailBytes", integerProperty("Alias of tail_bytes."))
                },
                required = listOf("run_id"),
                executionMode = "sequential",
            ).also(::put)
            toolDefinition(
                name = "kill_bash",
                description = "Stop a previously started long-running bash command by runtime-prefixed run_id and return its latest logs.",
                properties = JSONObject().apply {
                    put("run_id", stringProperty("The run_id returned by bash when it reported status=running."))
                    put("runId", stringProperty("Alias of run_id."))
                    put("environment", runtimeEnvironmentProperty())
                    put("tail_bytes", integerProperty("Optional maximum number of bytes to return from the end of stdout and stderr."))
                    put("tailBytes", integerProperty("Alias of tail_bytes."))
                },
                required = listOf("run_id"),
                executionMode = "sequential",
            ).also(::put)
            toolDefinition(
                name = "sleep",
                description = "Pause the agent for a fixed duration so a long-running bash command can continue before you fetch logs again.",
                properties = JSONObject().apply {
                    put("duration_ms", integerProperty("How long to sleep in milliseconds."))
                    put("durationMs", integerProperty("Alias of duration_ms."))
                },
                required = listOf("duration_ms"),
                executionMode = "sequential",
            ).also(::put)
        }

        fun sanitizeToolOutputForConversation(
            toolName: String,
            output: String,
        ): String {
            if (toolName != "agent_display") return output
            val parsed = runCatching { JSONObject(output) }.getOrNull() ?: return output
            if (!parsed.has("screenshot_base64")) return output
            parsed.remove("screenshot_base64")
            parsed.put("screenshot_injected_into_next_model_request", true)
            return parsed.toString()
        }

        fun inferToolOutputOk(output: String): Boolean {
            val parsed = runCatching { JSONObject(output) }.getOrNull() ?: return true
            return parsed.optBoolean("ok", !parsed.optBoolean("err", false))
        }
    }
}

private fun toolDefinition(
    name: String,
    description: String,
    properties: JSONObject,
    required: List<String>,
    executionMode: String,
): JSONObject = JSONObject().apply {
    put("name", name)
    put("description", description)
    put("parameters", buildStrictHostToolParameters(properties, required))
    put("execution_mode", executionMode)
}

private fun buildStrictHostToolParameters(
    properties: JSONObject,
    required: List<String>,
): JSONObject {
    val requiredSet = required.toSet()
    val normalizedProperties = JSONObject()
    val normalizedRequired = JSONArray()
    val iterator = properties.keys()

    while (iterator.hasNext()) {
        val propertyName = iterator.next()
        normalizedRequired.put(propertyName)
        val propertySchema = JSONObject(properties.getJSONObject(propertyName).toString())
        normalizedProperties.put(
            propertyName,
            if (propertyName in requiredSet) {
                propertySchema
            } else {
                makeSchemaNullable(propertySchema)
            },
        )
    }

    return JSONObject().apply {
        put("type", "object")
        put("properties", normalizedProperties)
        put("required", normalizedRequired)
        put("additionalProperties", false)
    }
}

private fun makeSchemaNullable(schema: JSONObject): JSONObject =
    JSONObject(schema.toString()).apply {
        when (val typeValue = opt("type")) {
            is String -> {
                if (typeValue != "null") {
                    put("type", JSONArray().put(typeValue).put("null"))
                }
            }

            is JSONArray -> {
                var hasNull = false
                for (index in 0 until typeValue.length()) {
                    if (typeValue.optString(index) == "null") {
                        hasNull = true
                        break
                    }
                }
                if (!hasNull) {
                    typeValue.put("null")
                }
                put("type", typeValue)
            }
        }
    }

private fun stringProperty(description: String): JSONObject = JSONObject().apply {
    put("type", "string")
    put("description", description)
}

private fun integerProperty(description: String): JSONObject = JSONObject().apply {
    put("type", "integer")
    put("description", description)
}

private fun booleanProperty(description: String): JSONObject = JSONObject().apply {
    put("type", "boolean")
    put("description", description)
}

private fun runtimeEnvironmentProperty(): JSONObject = stringProperty(
    "Optional local runtime: default, termux, or alpine. Use alpine for the built-in Linux VM and Termux for Android/phone integration.",
)

private fun unknownToolOutput(toolName: String): String =
    JSONObject().apply {
        put("ok", false)
        put("error", "Unknown tool '$toolName'.")
    }.toString()

private fun injectDefaultWorkingDirectory(
    argumentsJson: String,
    workspaceDirectory: String,
): String {
    val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return argumentsJson

    val snake = arguments.cleanOptionalString("working_directory")
    val camel = arguments.cleanOptionalString("workingDirectory")

    arguments.remove("working_directory")
    arguments.remove("workingDirectory")

    when {
        snake.isNotBlank() -> arguments.put("working_directory", snake)
        camel.isNotBlank() -> arguments.put("working_directory", camel)
        else -> arguments.put("working_directory", workspaceDirectory)
    }

    return arguments.toString()
}

private fun JSONObject.cleanOptionalString(key: String): String {
    if (!has(key) || isNull(key)) return ""
    val value = optString(key).trim()
    return value.takeUnless { it.equals("null", ignoreCase = true) || it.equals("undefined", ignoreCase = true) }
        .orEmpty()
}
