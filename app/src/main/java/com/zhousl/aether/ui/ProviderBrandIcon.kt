package com.zhousl.aether.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhousl.aether.R
import com.zhousl.aether.data.PiProviderDefinition

@Composable
internal fun ProviderBrandIconBadge(
    provider: PiProviderDefinition,
    modifier: Modifier = Modifier,
    badgeSize: Dp = 48.dp,
    iconSize: Dp = 30.dp,
    cornerRadius: Dp = 14.dp,
) {
    Box(
        modifier = modifier
            .size(badgeSize)
            .clip(RoundedCornerShape(cornerRadius))
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(providerBrandIconRes(provider.id)),
            contentDescription = provider.displayName,
            modifier = Modifier.size(iconSize),
            contentScale = ContentScale.Fit,
        )
    }
}

@DrawableRes
private fun providerBrandIconRes(providerId: String): Int = when (providerId) {
    "amazon-bedrock" -> R.drawable.provider_amazon_bedrock
    "ant-ling" -> R.drawable.provider_ant_ling
    "anthropic" -> R.drawable.provider_anthropic
    "azure-openai-responses" -> R.drawable.provider_azure_openai_responses
    "cerebras" -> R.drawable.provider_cerebras
    "cloudflare-ai-gateway" -> R.drawable.provider_cloudflare_ai_gateway
    "cloudflare-workers-ai" -> R.drawable.provider_cloudflare_workers_ai
    "deepseek" -> R.drawable.provider_deepseek
    "fireworks" -> R.drawable.provider_fireworks
    "github-copilot" -> R.drawable.provider_github_copilot
    "google" -> R.drawable.provider_google
    "google-vertex" -> R.drawable.provider_google_vertex
    "groq" -> R.drawable.provider_groq
    "huggingface" -> R.drawable.provider_huggingface
    "kimi-coding" -> R.drawable.provider_kimi_coding
    "minimax" -> R.drawable.provider_minimax
    "minimax-cn" -> R.drawable.provider_minimax_cn
    "mistral" -> R.drawable.provider_mistral
    "moonshotai" -> R.drawable.provider_moonshotai
    "moonshotai-cn" -> R.drawable.provider_moonshotai_cn
    "nvidia" -> R.drawable.provider_nvidia
    "openai" -> R.drawable.provider_openai
    "openai-codex" -> R.drawable.provider_openai_codex
    "opencode" -> R.drawable.provider_opencode
    "opencode-go" -> R.drawable.provider_opencode_go
    "openrouter" -> R.drawable.provider_openrouter
    "together" -> R.drawable.provider_together
    "vercel-ai-gateway" -> R.drawable.provider_vercel_ai_gateway
    "xai" -> R.drawable.provider_xai
    "xiaomi" -> R.drawable.provider_xiaomi
    "xiaomi-token-plan-ams" -> R.drawable.provider_xiaomi_token_plan_ams
    "xiaomi-token-plan-cn" -> R.drawable.provider_xiaomi_token_plan_cn
    "xiaomi-token-plan-sgp" -> R.drawable.provider_xiaomi_token_plan_sgp
    "zai" -> R.drawable.provider_zai
    "zai-coding-cn" -> R.drawable.provider_zai_coding_cn
    else -> R.drawable.provider_openai_compatible
}
