package com.zhousl.aether.data.pi

import com.zhousl.aether.data.PiProviderEnvironmentVariable
import com.zhousl.aether.data.ProviderAuthMethod
import org.json.JSONArray
import org.json.JSONObject

data class PiOAuthPromptOption(
    val id: String,
    val label: String,
    val description: String = "",
)

data class PiOAuthPrompt(
    val id: String,
    val type: String,
    val message: String,
    val placeholder: String = "",
    val options: List<PiOAuthPromptOption> = emptyList(),
)

data class PiProviderAuthState(
    val providerId: String = "",
    val authMethod: ProviderAuthMethod = ProviderAuthMethod.ApiKey,
    val isRunning: Boolean = false,
    val statusMessage: String = "",
    val authorizationUrl: String = "",
    val deviceCode: String = "",
    val verificationUrl: String = "",
    val prompt: PiOAuthPrompt? = null,
    val apiKey: String = "",
    val oauthCredentialJson: String = "",
    val providerEnvironmentVariables: List<PiProviderEnvironmentVariable> = emptyList(),
    val errorMessage: String = "",
)

enum class PiCoreSetupPhase(
    val step: Int,
) {
    Idle(step = 0),
    CheckingAlpine(step = 1),
    CheckingNode(step = 2),
    InstallingNode(step = 2),
    PreparingBridge(step = 3),
    StartingBridge(step = 4),
    VerifyingBridge(step = 5),
    Ready(step = 5),
    Failed(step = 0),
}

enum class PiCoreSetupActivity {
    None,
    Extracting,
    Downloading,
}

data class PiCoreSetupUpdate(
    val phase: PiCoreSetupPhase,
    val activity: PiCoreSetupActivity = PiCoreSetupActivity.None,
    val bytesPerSecond: Long = 0L,
    val output: String = "",
)

data class PiCoreSetupState(
    val isChecking: Boolean = false,
    val isReady: Boolean = false,
    val phase: PiCoreSetupPhase = PiCoreSetupPhase.Idle,
    val failedAtPhase: PiCoreSetupPhase = PiCoreSetupPhase.Idle,
    val detail: String = "",
    val nodeVersion: String = "",
    val bridgeVersion: String = "",
    val activity: PiCoreSetupActivity = PiCoreSetupActivity.None,
    val bytesPerSecond: Long = 0L,
    val output: String = "",
)

fun JSONObject.toPiOAuthPrompt(): PiOAuthPrompt = PiOAuthPrompt(
    id = optString("prompt_id"),
    type = optString("prompt_type"),
    message = optString("message"),
    placeholder = optString("placeholder"),
    options = optJSONArray("options").toPiOAuthPromptOptions(),
)

fun JSONObject.toPiProviderEnvironmentVariables(): List<PiProviderEnvironmentVariable> =
    optJSONObject("provider_env")?.let { environment ->
        environment.keys().asSequence()
            .mapNotNull { name ->
                name.trim()
                    .takeIf(String::isNotEmpty)
                    ?.let { normalizedName ->
                        PiProviderEnvironmentVariable(
                            name = normalizedName,
                            value = environment.optString(name),
                        )
                    }
            }
            .toList()
    }.orEmpty()

private fun JSONArray?.toPiOAuthPromptOptions(): List<PiOAuthPromptOption> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val option = optJSONObject(index) ?: continue
            add(
                PiOAuthPromptOption(
                    id = option.optString("id"),
                    label = option.optString("label"),
                    description = option.optString("description"),
                )
            )
        }
    }
}
