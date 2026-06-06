package com.zhousl.aether.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlpineRuntimeInstrumentedTest {
    @Test
    fun alpineRuntimeStartsShellFromAppProcess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = AlpineRuntime(context)

        val setup = runtime.initialize()
        assertEquals(setup.detail, LocalRuntimeIssue.Ready, setup.issue)

        val result = JSONObject(
            runtime.executeCommand(
                command = "echo AETHER_ALPINE_APP_PROCESS_OK; cat /etc/alpine-release; uname -m; pwd",
                workingDirectory = runtime.homeDirectory,
                awaitTimeoutMillis = 30_000L,
            )
        )

        assertTrue(result.optString("errmsg"), result.optBoolean("ok"))
        val stdout = result.optString("stdout")
        assertTrue(stdout, stdout.contains("AETHER_ALPINE_APP_PROCESS_OK"))
        assertTrue(stdout, stdout.contains("aarch64"))
        assertTrue(stdout, stdout.contains("/root"))
    }

    @Test
    fun pythonPackageProfileInstallsAndRuns() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = AlpineRuntime(context)

        val setup = runtime.initialize()
        assertEquals(setup.detail, LocalRuntimeIssue.Ready, setup.issue)

        val profile = runtime.installPackageProfile("python")
        assertEquals(profile.detail, LocalRuntimeIssue.Ready, profile.issue)

        val result = JSONObject(
            runtime.executeCommand(
                command = "python3 --version && python3 - <<'PY'\nprint('AETHER_ALPINE_PYTHON_OK')\nPY",
                workingDirectory = runtime.homeDirectory,
                awaitTimeoutMillis = 30_000L,
            )
        )

        assertTrue(result.optString("errmsg"), result.optBoolean("ok"))
        val stdout = result.optString("stdout")
        assertTrue(stdout, stdout.contains("Python"))
        assertTrue(stdout, stdout.contains("AETHER_ALPINE_PYTHON_OK"))
    }
}
