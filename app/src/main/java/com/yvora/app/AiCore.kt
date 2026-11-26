package com.yvora.app

internal enum class AiProvider(val title: String) {
    OPENAI("OpenAI / GPT"), GEMINI("Google / Gemini"), CLAUDE("Anthropic / Claude"), DEEPSEEK("DeepSeek")
}
internal enum class AiAction { SWIPE_LEFT, SWIPE_RIGHT, SWIPE_UP, SWIPE_DOWN, BACK, HOME }

/** Remote output is untrusted. Only exact allowlisted tokens become a preview.
 * No accessibility calls or side effects are possible from this module.
 */
internal object AiCommandGate {
    const val INSTRUCTION = "You are Yvora's navigation planning assistant. Return only a list of 1 to 4 exact action tokens, one per line: SWIPE_LEFT, SWIPE_RIGHT, SWIPE_UP, SWIPE_DOWN, BACK, HOME. If the request is ambiguous, requires screen contents, sensitive actions, or unsupported actions, return exactly UNSUPPORTED. Do not add markdown, explanations, parameters, or other text. All actions are previews only."
    fun parse(raw: String): List<AiAction>? {
        if (raw.length > 200) return null
        val trimmed = raw.trim()
        if (trimmed == "UNSUPPORTED" || trimmed.isEmpty()) return null
        val lines = trimmed.lines()
        if (lines.size !in 1..4) return null
        return lines.map { line ->
            AiAction.values().firstOrNull { it.name == line.trim() } ?: return null
        }
    }
}
