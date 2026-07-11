package com.zhousl.aether.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AetherToolExecutorTest {
    @Test
    fun hostToolDefinitionsExposeInitialPiToolSlice() {
        val definitions = AetherToolExecutor.hostToolDefinitions()
        val names = buildList {
            for (index in 0 until definitions.length()) {
                add(definitions.getJSONObject(index).getString("name"))
            }
        }

        assertTrue(AetherToolExecutor.hostToolNames.containsAll(names))
        val bash = (0 until definitions.length())
            .map { definitions.getJSONObject(it) }
            .first { it.getString("name") == "bash" }
        assertEquals("sequential", bash.getString("execution_mode"))
        assertEquals("object", bash.getJSONObject("parameters").getString("type"))
        assertEquals(
            listOf("command"),
            bash.getJSONObject("parameters").getJSONArray("required").toStringList(),
        )
        assertEquals(
            "string",
            bash.getJSONObject("parameters")
                .getJSONObject("properties")
                .getJSONObject("working_directory")
                .getString("type"),
        )

        val activateSkill = (0 until definitions.length())
            .map { definitions.getJSONObject(it) }
            .first { it.getString("name") == "activate_skill" }
        assertEquals("sequential", activateSkill.getString("execution_mode"))
        assertEquals(
            listOf("name"),
            activateSkill.getJSONObject("parameters").getJSONArray("required").toStringList(),
        )

        val fetchWebUrl = (0 until definitions.length())
            .map { definitions.getJSONObject(it) }
            .first { it.getString("name") == "fetch_web_url" }
        assertEquals("parallel", fetchWebUrl.getString("execution_mode"))
        assertEquals(
            listOf("url"),
            fetchWebUrl.getJSONObject("parameters").getJSONArray("required").toStringList(),
        )
    }

    @Test
    fun sanitizeAgentDisplayOutputRemovesScreenshotBytes() {
        val sanitized = AetherToolExecutor.sanitizeToolOutputForConversation(
            toolName = "agent_display",
            output = JSONObject().apply {
                put("ok", true)
                put("screenshot_base64", "abc123")
                put("screenshot_mime_type", "image/png")
            }.toString(),
        )

        val json = JSONObject(sanitized)
        assertFalse(json.has("screenshot_base64"))
        assertTrue(json.getBoolean("screenshot_injected_into_next_model_request"))
        assertEquals("image/png", json.getString("screenshot_mime_type"))
    }

    @Test
    fun dynamicHostToolDefinitionsIncludeMcpAndAgentMode() {
        val definitions = AetherToolExecutor.hostToolDefinitions(
            selfManagementTool = null,
            mcpClientManager = null,
            mcpToolBindings = listOf(
                McpToolBinding(
                    serverId = "docs",
                    serverName = "Docs",
                    toolName = "search",
                    description = "Search documentation.",
                    inputSchema = JSONObject().apply {
                        put("type", "object")
                        put(
                            "properties",
                            JSONObject().put(
                                "query",
                                JSONObject().put("type", "string"),
                            ),
                        )
                    },
                )
            ),
            agentModeEnabled = true,
        )
        val names = (0 until definitions.length())
            .map { definitions.getJSONObject(it).getString("name") }

        assertTrue("mcp_list_tools" in names)
        assertTrue("mcp__docs__search" in names)
        assertTrue("agent_display" in names)
        assertEquals(
            "sequential",
            (0 until definitions.length())
                .map { definitions.getJSONObject(it) }
                .first { it.getString("name") == "agent_display" }
                .getString("execution_mode"),
        )
    }

    @Test
    fun inferToolOutputOkHonorsAetherJsonFlags() {
        assertTrue(AetherToolExecutor.inferToolOutputOk("""{"ok":true}"""))
        assertFalse(AetherToolExecutor.inferToolOutputOk("""{"ok":false}"""))
        assertFalse(AetherToolExecutor.inferToolOutputOk("""{"err":true}"""))
        assertTrue(AetherToolExecutor.inferToolOutputOk("plain text"))
    }
}

private fun org.json.JSONArray.toStringList(): List<String> =
    (0 until length()).map(::getString)
