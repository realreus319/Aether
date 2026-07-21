package com.zhousl.aether.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AetherAppExtensionsTest {
    @Test
    fun disabledExtensionIdsBecomeStableLoadFilters() {
        val options = loadOptionsForIds(
            setOf(
                "package:npm:demo-extension@1.0.0",
                "import:aether:/root/.aether/extensions/global-skills-mod",
            )
        )

        assertEquals(
            setOf("npm:demo-extension@1.0.0"),
            options.disabledPackageSources,
        )
        assertEquals(
            setOf("/root/.aether/extensions/global-skills-mod"),
            options.disabledExtensionPaths,
        )
    }

    @Test
    fun unknownOrBlankExtensionIdsStayEnabledByDefault() {
        val options = loadOptionsForIds(setOf("", "not-an-extension-id"))

        assertTrue(options.disabledExtensionPaths.isEmpty())
        assertTrue(options.disabledPackageSources.isEmpty())
    }

    @Test
    fun parsesExtensionSnapshotAndOrdersSlots() {
        val snapshot = parseAetherAppExtensionSnapshot(
            JSONObject(
                """
                {
                  "api_version": 2,
                  "version": 7,
                  "extensions": [
                    {"id":"demo:1","name":"Demo","path":"/demo/index.ts"}
                  ],
                  "components": [
                    {
                      "id":"demo:1:tray",
                      "extension_id":"demo:1",
                      "extension_name":"Demo",
                      "target":"chat.composer.actionTray",
                      "mode":"wrap",
                      "order":4,
                      "tree":{"type":"core"}
                    }
                  ],
                  "surfaces": [
                    {
                      "id":"demo:1:later",
                      "extension_id":"demo:1",
                      "extension_name":"Demo",
                      "slot":"chat.composer.top",
                      "order":20,
                      "tree":{"type":"text","text":"Later"}
                    },
                    {
                      "id":"demo:1:first",
                      "extension_id":"demo:1",
                      "extension_name":"Demo",
                      "slot":"chat.composer.top",
                      "order":1,
                      "tree":{"type":"text","text":"First"}
                    }
                  ],
                  "pages": [
                    {
                      "id":"demo:1:dashboard",
                      "local_id":"dashboard",
                      "extension_id":"demo:1",
                      "extension_name":"Demo",
                      "title":"Dashboard",
                      "subtitle":"Live",
                      "icon":"code",
                      "order":0,
                      "tree":{"type":"text","text":"Page"}
                    }
                  ],
                  "event_names":["before_send"],
                  "errors":[]
                }
                """.trimIndent()
            )
        )

        assertEquals(7L, snapshot.version)
        assertEquals("Demo", snapshot.extensions.single().name)
        assertEquals(
            listOf("demo:1:first", "demo:1:later"),
            snapshot.surfacesAt("chat.composer.top").map { it.id },
        )
        assertEquals("Dashboard", snapshot.pages.single().title)
        assertEquals(
            "wrap",
            snapshot.componentsAt("chat.composer.actionTray").single().mode,
        )
        assertTrue("before_send" in snapshot.eventNames)
    }

    @Test
    fun rejectedReloadSurfacesDistinctFactoryErrors() {
        val response = JSONObject(
            """
            {
              "reloaded": false,
              "errors": [
                {"error":"Factory failed"},
                {"error":"Factory failed"},
                {"error":"Cleanup failed"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(
            "Factory failed; Cleanup failed",
            response.extensionReloadError(),
        )
    }

    @Test
    fun successfulReloadHasNoReloadError() {
        assertEquals(
            "",
            JSONObject().put("reloaded", true).extensionReloadError(),
        )
    }
}
