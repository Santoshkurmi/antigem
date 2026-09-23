package com.example.gemini.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

@Serializable
data class ChoiceOption(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val description: String? = null,
    val icon: String? = null,
    val isCustomInput: Boolean = false,
    val nestedQuestions: List<ChoiceQuestion> = emptyList()
)

@Serializable
data class ChoiceQuestion(
    val id: String = UUID.randomUUID().toString(),
    val prompt: String,
    val description: String? = null,
    val isMultiSelect: Boolean = false,
    val options: List<ChoiceOption> = emptyList(),
    val allowCustomAnswer: Boolean = true
)

@Serializable
data class ChoiceQuestionnaire(
    val title: String = "Clarification & Options",
    val description: String? = null,
    val questions: List<ChoiceQuestion> = emptyList(),
    val allowCustomNote: Boolean = true
) {
    companion object {
        private val jsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        /**
         * Safely parses raw JSON, stringified JSON strings, or markdown codeblocks into a ChoiceQuestionnaire.
         * Enforces maximum nesting depth of 4.
         */
        fun parse(rawText: String): ChoiceQuestionnaire? {
            if (rawText.isBlank()) return null
            val cleanJson = rawText
                .replace(Regex("^```json\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^```\\s*"), "")
                .replace(Regex("```$"), "")
                .trim()

            if (cleanJson.isBlank()) return null

            return try {
                val element = jsonParser.parseToJsonElement(cleanJson)
                when (element) {
                    is JsonObject -> parseQuestionnaireObject(element)
                    is JsonArray -> {
                        val questions = element.mapNotNull { if (it is JsonObject) parseQuestion(it, depth = 1) else null }
                        if (questions.isNotEmpty()) {
                            ChoiceQuestionnaire(
                                title = "Clarification & Choices",
                                questions = questions
                            )
                        } else null
                    }
                    is JsonPrimitive -> {
                        // In case the whole string is an escaped JSON string
                        val inner = element.contentOrNull
                        if (!inner.isNullOrBlank() && (inner.startsWith("{") || inner.startsWith("["))) {
                            parse(inner)
                        } else null
                    }
                }
            } catch (_: Exception) {
                // Fallback custom parser for partial or malformed JSON
                tryParseLenient(cleanJson)
            }
        }

        private fun parseQuestionnaireObject(obj: JsonObject): ChoiceQuestionnaire {
            val title = obj["title"]?.jsonPrimitive?.contentOrNull
                ?: obj["toolSummary"]?.jsonPrimitive?.contentOrNull
                ?: obj["toolAction"]?.jsonPrimitive?.contentOrNull
                ?: "Clarification & Choices"

            val description = obj["description"]?.jsonPrimitive?.contentOrNull
                ?: obj["toolAction"]?.jsonPrimitive?.contentOrNull?.takeIf { it != title }
            val allowCustom = obj["allowCustomNote"]?.jsonPrimitive?.booleanOrNull ?: true

            val rawQuestionsElement = obj["questions"] ?: obj["questionnaire"]
            val questionsList = mutableListOf<ChoiceQuestion>()

            when (rawQuestionsElement) {
                is JsonArray -> {
                    rawQuestionsElement.forEach { qEl ->
                        if (qEl is JsonObject) {
                            parseQuestion(qEl, depth = 1)?.let { questionsList.add(it) }
                        }
                    }
                }
                is JsonPrimitive -> {
                    // Stringified JSON array (common in AGY language server generic args)
                    val jsonStr = rawQuestionsElement.contentOrNull
                    if (!jsonStr.isNullOrBlank()) {
                        try {
                            val parsedArray = jsonParser.parseToJsonElement(jsonStr)
                            if (parsedArray is JsonArray) {
                                parsedArray.forEach { qEl ->
                                    if (qEl is JsonObject) {
                                        parseQuestion(qEl, depth = 1)?.let { questionsList.add(it) }
                                    }
                                }
                            }
                        } catch (_: Exception) { }
                    }
                }
                is JsonObject -> {
                    parseQuestion(rawQuestionsElement, depth = 1)?.let { questionsList.add(it) }
                }
                else -> {
                    // Check if the top-level object itself is a single question
                    if (obj.containsKey("question") || obj.containsKey("prompt")) {
                        parseQuestion(obj, depth = 1)?.let { questionsList.add(it) }
                    }
                }
            }

            return ChoiceQuestionnaire(
                title = title,
                description = description,
                questions = questionsList,
                allowCustomNote = allowCustom
            )
        }

        private fun parseQuestion(obj: JsonObject, depth: Int): ChoiceQuestion? {
            if (depth > 4) return null
            val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull
                ?: obj["question"]?.jsonPrimitive?.contentOrNull
                ?: obj["title"]?.jsonPrimitive?.contentOrNull
                ?: return null

            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString()
            val desc = obj["description"]?.jsonPrimitive?.contentOrNull
            val isMulti = obj["isMultiSelect"]?.jsonPrimitive?.booleanOrNull
                ?: obj["is_multi_select"]?.jsonPrimitive?.booleanOrNull
                ?: obj["IsMultiSelect"]?.jsonPrimitive?.booleanOrNull
                ?: obj["multiSelect"]?.jsonPrimitive?.booleanOrNull
                ?: false

            val rawOptionsEl = obj["options"] ?: obj["choices"]
            val options = mutableListOf<ChoiceOption>()

            when (rawOptionsEl) {
                is JsonArray -> {
                    rawOptionsEl.forEachIndexed { idx, optEl ->
                        when (optEl) {
                            is JsonObject -> parseOption(optEl, depth + 1, fallbackId = (idx + 1).toString())?.let { options.add(it) }
                            is JsonPrimitive -> {
                                val text = optEl.contentOrNull ?: optEl.toString()
                                if (text.isNotBlank()) {
                                    options.add(ChoiceOption(id = (idx + 1).toString(), label = text))
                                }
                            }
                            else -> {}
                        }
                    }
                }
                is JsonPrimitive -> {
                    val rawStr = rawOptionsEl.contentOrNull
                    if (!rawStr.isNullOrBlank()) {
                        try {
                            val parsedArray = jsonParser.parseToJsonElement(rawStr)
                            if (parsedArray is JsonArray) {
                                parsedArray.forEachIndexed { idx, optEl ->
                                    if (optEl is JsonPrimitive) {
                                        optEl.contentOrNull?.let { text ->
                                            if (text.isNotBlank()) {
                                                options.add(ChoiceOption(id = (idx + 1).toString(), label = text))
                                            }
                                        }
                                    } else if (optEl is JsonObject) {
                                        parseOption(optEl, depth + 1, fallbackId = (idx + 1).toString())?.let { options.add(it) }
                                    }
                                }
                            }
                        } catch (_: Exception) { }
                    }
                }
                else -> {}
            }

            return ChoiceQuestion(
                id = id,
                prompt = prompt,
                description = desc,
                isMultiSelect = isMulti,
                options = options,
                allowCustomAnswer = true
            )
        }

        private fun parseOption(obj: JsonObject, depth: Int, fallbackId: String = "1"): ChoiceOption? {
            val label = obj["label"]?.jsonPrimitive?.contentOrNull
                ?: obj["text"]?.jsonPrimitive?.contentOrNull
                ?: obj["name"]?.jsonPrimitive?.contentOrNull
                ?: obj["option"]?.jsonPrimitive?.contentOrNull
                ?: return null

            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: fallbackId
            val desc = obj["description"]?.jsonPrimitive?.contentOrNull
            val icon = obj["icon"]?.jsonPrimitive?.contentOrNull

            val rawNested = obj["nestedQuestions"] ?: obj["nested_questions"] ?: obj["followUpQuestions"] ?: obj["subQuestions"]
            val nested = mutableListOf<ChoiceQuestion>()

            if (depth <= 4 && rawNested is JsonArray) {
                rawNested.forEach { qEl ->
                    if (qEl is JsonObject) {
                        parseQuestion(qEl, depth + 1)?.let { nested.add(it) }
                    }
                }
            }

            return ChoiceOption(
                id = id,
                label = label,
                description = desc,
                icon = icon,
                nestedQuestions = nested
            )
        }

        private fun tryParseLenient(text: String): ChoiceQuestionnaire? {
            return try {
                jsonParser.decodeFromString<ChoiceQuestionnaire>(text)
            } catch (_: Exception) {
                null
            }
        }
    }
}
