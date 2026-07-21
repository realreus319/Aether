package com.zhousl.aether.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingScreenTest {
    @Test
    fun prioritizedModelsOnlyContainFetchedCatalogEntries() {
        assertEquals(
            listOf("provider/model-a", "provider/model-b"),
            prioritizedModelOptions(
                piProviderId = "openai-compatible",
                cachedModels = listOf(" provider/model-b ", "provider/model-a"),
            ),
        )
    }

    @Test
    fun prioritizedModelsDoNotInjectProviderDefaultWhenCatalogIsEmpty() {
        assertEquals(
            emptyList<String>(),
            prioritizedModelOptions(
                piProviderId = "openai",
                cachedModels = emptyList(),
            ),
        )
    }

    @Test
    fun prioritizedModelsReorderOnlyFetchedPreferredFamilies() {
        assertEquals(
            listOf(
                "claude-fable-5",
                "openai/gpt-5.6-sol",
                "claude-opus-4-8",
                "claude-sonnet-5",
                "gemini-3.5-flash",
                "gpt-4.1",
                "gemini-2.5-pro",
            ),
            prioritizedModelOptions(
                piProviderId = null,
                cachedModels = listOf(
                    "gemini-2.5-pro",
                    "claude-sonnet-5",
                    "gpt-4.1",
                    "gemini-3.5-flash",
                    "claude-opus-4-8",
                    "claude-fable-5",
                    "openai/gpt-5.6-sol",
                ),
            ),
        )
    }

    @Test
    fun automaticSelectionDoesNotOverridePreferredFetchedModel() {
        assertEquals(
            listOf("gpt-5.6-sol", "gpt-4.1"),
            prioritizedModelOptions(
                piProviderId = "openai",
                cachedModels = listOf("gpt-4.1", "gpt-5.6-sol"),
            ),
        )
    }
}
