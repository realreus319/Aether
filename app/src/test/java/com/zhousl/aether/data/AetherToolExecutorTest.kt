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

        assertEquals(AetherToolExecutor.hostToolNames, names.toSet())
        val bash = (0 until definitions.length())
            .map { definitions.getJSONObject(it) }
            .first { it.getString("name") == "bash" }
        assertEquals("sequential", bash.getString("execution_mode"))
        assertEquals("object", bash.getJSONObject("parameters").getString("type"))
        assertTrue(bash.getJSONObject("parameters").getJSONArray("required").toString().contains("command"))
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
    fun inferToolOutputOkHonorsAetherJsonFlags() {
        assertTrue(AetherToolExecutor.inferToolOutputOk("""{"ok":true}"""))
        assertFalse(AetherToolExecutor.inferToolOutputOk("""{"ok":false}"""))
        assertFalse(AetherToolExecutor.inferToolOutputOk("""{"err":true}"""))
        assertTrue(AetherToolExecutor.inferToolOutputOk("plain text"))
    }
}
