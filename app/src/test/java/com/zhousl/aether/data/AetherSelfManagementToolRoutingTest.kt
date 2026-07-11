package com.zhousl.aether.data

import org.junit.Assert.assertTrue
import org.junit.Test

class AetherSelfManagementToolRoutingTest {
    @Test
    fun scheduledTaskToolIsRoutedByPiHostExecutor() {
        assertTrue(AetherToolExecutor.supports("aether_scheduled_task_manage"))
    }
}
