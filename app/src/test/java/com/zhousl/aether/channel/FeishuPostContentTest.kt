package com.zhousl.aether.channel

import com.zhousl.aether.channel.feishu.buildFeishuPostContent
import com.zhousl.aether.channel.feishu.normalizeFeishuMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeishuPostContentTest {
    @Test
    fun postUsesMarkdownElementAndPreservesToolFormatting() {
        val content = buildFeishuPostContent(
            """
            🔧 **read**
            ```
            {"path":"/tmp/a"}
            ```
            """.trimIndent()
        )
        val rows = content.getJSONObject("zh_cn").getJSONArray("content")
        val element = rows.getJSONArray(0).getJSONObject(0)

        assertEquals("md", element.getString("tag"))
        assertTrue(element.getString("text").contains("**read**"))
        assertTrue(element.getString("text").contains("```"))
    }

    @Test
    fun markdownNormalizationPutsCodeFenceOnItsOwnLine() {
        assertEquals("before\n```\ncode\n```", normalizeFeishuMarkdown("before```\ncode\n```"))
    }
}
