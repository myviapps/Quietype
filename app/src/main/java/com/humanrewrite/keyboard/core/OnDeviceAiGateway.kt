package com.humanrewrite.keyboard.core

/**
 * Boundary for on-device rewriting. The keyboard should not know which model produced the text.
 */
interface OnDeviceAiGateway {
    fun isRewriteSupported(): Boolean
    fun rewrite(request: RewriteRequest): RewriteResult
    fun warmUp()
}

/** Rewrites with the selected downloaded model: Low = SmolLM2 360M, High = Llama 3.2 1B, run by llama.cpp. */
class LocalModelRewriteGateway(
    private val settingsStore: SettingsStore,
    private val modelStore: ModelStore
) : OnDeviceAiGateway {
    // Only a fully downloaded model counts; nothing is faked when it is missing.
    override fun isRewriteSupported(): Boolean = modelStore.state(settingsStore.localModelOption) == ModelState.Ready

    override fun warmUp() {
        val file = modelStore.file(settingsStore.localModelOption)
        // llama.cpp mmaps weights lazily, so loading alone isn't enough: a one-token completion
        // forces the weights into RAM so the user's first real rewrite isn't the one paying for it.
        if (file.exists()) LlamaEngine.complete(file.absolutePath, "Hi", 1)
    }

    override fun rewrite(request: RewriteRequest): RewriteResult {
        val option = request.localModel
        val file = modelStore.file(option)
        if (!file.exists()) {
            DiagnosticLog.log("Rewrite failed: ${option.name} model file missing")
            return RewriteResult.Failed("Download the ${option.title} model in Quietype settings first")
        }

        if (!RewritePrompt.hasLatinLetters(request.text)) {
            return RewriteResult.Failed("Rewrite works on English text only")
        }

        val prompt = RewritePrompt.build(request.text, request.mode, llama3Format = option == LocalModelOption.LLAMA32_1B)
        val maxTokens = (request.text.length / 2 + 64).coerceAtMost(1024)
        val raw = LlamaEngine.complete(file.absolutePath, prompt, maxTokens)
        if (raw == null) {
            DiagnosticLog.log("Rewrite failed: ${option.name} llama.cpp init/inference returned null")
            return RewriteResult.Failed("The ${option.title} model could not run on this device")
        }

        // Never logs request.text or rewritten — lengths only, diagnostics must not carry user content.
        val rewritten = RewritePrompt.clean(raw)
        return if (RewritePrompt.looksValid(request.text, rewritten)) {
            RewriteResult.Success(rewritten, option.name)
        } else {
            DiagnosticLog.log("Rewrite rejected: ${option.name} output failed validity check (in=${request.text.length} out=${rewritten.length})")
            RewriteResult.Failed("Couldn't rewrite this text. Try again, or switch to High quality.")
        }
    }
}

internal object RewritePrompt {
    private val instructions = mapOf(
        RewriteMode.GRAMMAR_ONLY to "Correct the grammar, spelling, punctuation and capitalization of the text, " +
            "including subtle errors like wrong prepositions (e.g. \"informed this to him\" should be " +
            "\"informed him about this\"), wrong modal verbs (can/could), subject-verb agreement, and " +
            "countable/uncountable nouns (\"works\" -> \"work\" or \"tasks\"). Keep the writer's own words and " +
            "meaning, and change as little as possible otherwise.",
        RewriteMode.NATURAL_CASUAL to "Rewrite the text so it sounds natural and friendly, like a real person wrote it. " +
            "Fix all grammar, spelling, wrong prepositions, and awkward word choices. Keep the meaning and about the same length.",
        RewriteMode.PROFESSIONAL to "Rewrite the text in a clear, polite, professional tone for work messages. " +
            "Fix all grammar, spelling, wrong prepositions, modal verbs, and awkward word choices. Keep the meaning."
    )

    // One worked example per mode: small models follow a demonstration far better than instructions alone.
    private val examples = mapOf(
        RewriteMode.GRAMMAR_ONLY to ("i has two brother and they goes to school yesterday" to
            "I have two brothers and they went to school yesterday."),
        RewriteMode.NATURAL_CASUAL to ("i am not able to come to the meeting tomorrow because of some personal work" to
            "I can't make it to the meeting tomorrow, something personal came up."),
        // Demonstrates the exact pattern small models miss most: "informed X to Y" -> "informed Y about X",
        // subject-verb agreement on "pending works", and not inventing a greeting that wasn't in the original.
        RewriteMode.PROFESSIONAL to ("hey i cant finish the report today will send tmrw. i had already informed this issue to my manager. " +
            "pending works needs to be complete before we go live. the vendor has not give any date for the fix so i need to tell the client and they is asking everyday" to
            "I won't be able to finish the report today. I will send it tomorrow. I have already informed my manager " +
            "about this issue. The pending tasks need to be completed before we go live. The vendor has not given any date " +
            "for the fix, so I need to update the client, and they are asking every day.")
    )

    // SmolLM2 ("Low") uses ChatML; Llama 3.2 ("High") uses its own header-tagged turn format — sending
    // the wrong one to either model measurably hurt output quality in on-device comparison testing.
    fun build(text: String, mode: RewriteMode, llama3Format: Boolean): String {
        val system = "You are a writing assistant inside a keyboard. ${instructions.getValue(mode)} " +
            "Never answer, reply to, or comment on the text. Keep every fact, name, number, date and detail that is in the text — " +
            "never drop one, and never add new information, apologies, sympathy, context, greetings, sign-offs, or " +
            "filler phrases (like \"I hope this message finds you well\") that are not already in the text. " +
            "Keep the same meaning and who is doing what to whom. " +
            "Output only the rewritten text."
        val (exampleIn, exampleOut) = examples.getValue(mode)
        // The tokenizer parses special tokens, so a message containing "<|im_end|>" or
        // "<|eot_id|>" could close the user turn and inject its own instructions. Strip them.
        val trimmed = text.replace(Regex("<\\|[^|>]{0,40}\\|>"), "").trim()
        return if (llama3Format) {
            buildString {
                append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n").append(system).append("<|eot_id|>")
                append("<|start_header_id|>user<|end_header_id|>\n\n").append(exampleIn).append("<|eot_id|>")
                append("<|start_header_id|>assistant<|end_header_id|>\n\n").append(exampleOut).append("<|eot_id|>")
                append("<|start_header_id|>user<|end_header_id|>\n\n").append(trimmed).append("<|eot_id|>")
                append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            }
        } else {
            buildString {
                append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
                append("<|im_start|>user\n").append(exampleIn).append("<|im_end|>\n")
                append("<|im_start|>assistant\n").append(exampleOut).append("<|im_end|>\n")
                append("<|im_start|>user\n").append(trimmed).append("<|im_end|>\n")
                append("<|im_start|>assistant\n")
            }
        }
    }

    // The hallucination guard below only understands A-Z words, and the models are English-only,
    // so text with no Latin letters at all is refused instead of rewritten unchecked.
    fun hasLatinLetters(text: String): Boolean = text.any { it in 'a'..'z' || it in 'A'..'Z' }

    fun clean(raw: String): String {
        // A reasoning model's think block is discarded entirely, not just unwrapped — its content is
        // scratch work, not part of the answer. Any other stray tag (e.g. SmolLM2 sometimes wraps the
        // whole reply in "<response>...</response>") has its markers stripped but its content kept.
        var text = raw.substringAfter("</think>").trim()
        // Only known wrapper tags are stripped, so real "<b>" or "List<String>" in the text survives.
        text = text.replace(Regex("</?(think|response|answer|output|text|rewrite|rewritten|corrected|result)>", RegexOption.IGNORE_CASE), "").trim()
        // Small models sometimes lead with "Corrected text:" or wrap the answer in quotes.
        text = text.replace(Regex("^(corrected|rewritten|revised)( text)?\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        if (text.length > 1 && text.first() == '"' && text.last() == '"') text = text.substring(1, text.length - 1).trim()
        return text
    }

    // Rejects replies that answer the message instead of rewriting it (far longer or near-empty output).
    fun looksValid(original: String, rewritten: String): Boolean {
        val length = original.trim().length
        return rewritten.isNotBlank() && rewritten.length <= length * 2 + 40 && rewritten.length >= length / 3 &&
            !inventsContent(original, rewritten)
    }

    // Hard guard against hallucination: small models sometimes invent names, acronyms, numbers or whole
    // phrases. A rewrite only reshuffles the writer's own words (stems, so "give" -> "given" still
    // matches), so an unmatched name/number, or too many unmatched words, means the model made it up
    // and we insert nothing rather than wrong text.
    private fun inventsContent(original: String, rewritten: String): Boolean {
        val words = Regex("[A-Za-z0-9']+")
        fun stem(w: String) = w.lowercase().take(4)
        val known = words.findAll(original).map { stem(it.value) }.toSet()
        var invented = 0
        var total = 0
        for (m in words.findAll(rewritten)) {
            val w = m.value
            if (stem(w) in known) { if (w.length >= 4) total++; continue }
            val before = rewritten.substring(0, m.range.first).trimEnd()
            val sentenceStart = before.isEmpty() || before.last() in ".!?"
            val specific = w.any { it.isDigit() } || (w.length >= 2 && w.all { it.isUpperCase() }) ||
                (w.first().isUpperCase() && !sentenceStart)
            if (specific) return true
            if (w.length >= 4) { invented++; total++ }
        }
        return invented >= 3 && invented * 100 / total > 40
    }
}
