package com.example.gemini.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.UUID

@Serializable
data class ChoiceOption(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val description: String? = null,
    val icon: String? = null,
    val nestedQuestions: List<ChoiceQuestion> = emptyList()
)

@Serializable
data class ChoiceQuestion(
    val id: String = UUID.randomUUID().toString(),
    val prompt: String,
    val description: String? = null,
    val isMultiSelect: Boolean = false,
    val options: List<ChoiceOption> = emptyList()
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
         * Safely parses raw JSON or JSON within markdown blocks into a ChoiceQuestionnaire.
         * Enforces maximum nesting depth of 4.
         */
        fun parse(rawText: String): ChoiceQuestionnaire? {
            val cleanJson = rawText
                .replace(Regex("^```json\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^```\\s*"), "")
                .replace(Regex("```$"), "")
                .trim()

            return try {
                val element = jsonParser.parseToJsonElement(cleanJson)
                if (element is JsonObject) {
                    parseQuestionnaireObject(element)
                } else null
            } catch (e: Exception) {
                // Fallback custom parser for partial or malformed JSON
                tryParseLenient(cleanJson)
            }
        }

        private fun parseQuestionnaireObject(obj: JsonObject): ChoiceQuestionnaire {
            val title = obj["title"]?.jsonPrimitive?.content ?: "Clarification & Choices"
            val description = obj["description"]?.jsonPrimitive?.content
            val allowCustom = obj["allowCustomNote"]?.jsonPrimitive?.booleanOrNull ?: true

            val rawQuestions = obj["questions"]?.jsonArray ?: emptyList()
            val questions = rawQuestions.mapNotNull { qEl ->
                if (qEl is JsonObject) parseQuestion(qEl, depth = 1) else null
            }

            return ChoiceQuestionnaire(
                title = title,
                description = description,
                questions = questions,
                allowCustomNote = allowCustom
            )
        }

        private fun parseQuestion(obj: JsonObject, depth: Int): ChoiceQuestion? {
            if (depth > 4) return null
            val prompt = obj["prompt"]?.jsonPrimitive?.content
                ?: obj["question"]?.jsonPrimitive?.content
                ?: return null

            val id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
            val desc = obj["description"]?.jsonPrimitive?.content
            val isMulti = obj["isMultiSelect"]?.jsonPrimitive?.booleanOrNull
                ?: obj["multiSelect"]?.jsonPrimitive?.booleanOrNull
                ?: false

            val rawOptions = obj["options"]?.jsonArray ?: emptyList()
            val options = rawOptions.mapNotNull { optEl ->
                if (optEl is JsonObject) parseOption(optEl, depth + 1) else null
            }

            return ChoiceQuestion(
                id = id,
                prompt = prompt,
                description = desc,
                isMultiSelect = isMulti,
                options = options
            )
        }

        private fun parseOption(obj: JsonObject, depth: Int): ChoiceOption? {
            val label = obj["label"]?.jsonPrimitive?.content
                ?: obj["text"]?.jsonPrimitive?.content
                ?: obj["name"]?.jsonPrimitive?.content
                ?: return null

            val id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
            val desc = obj["description"]?.jsonPrimitive?.content
            val icon = obj["icon"]?.jsonPrimitive?.content

            val rawNested = obj["nestedQuestions"]?.jsonArray
                ?: obj["nested_questions"]?.jsonArray
                ?: obj["followUpQuestions"]?.jsonArray
                ?: obj["subQuestions"]?.jsonArray
                ?: emptyList()

            val nested = if (depth <= 4) {
                rawNested.mapNotNull { qEl ->
                    if (qEl is JsonObject) parseQuestion(qEl, depth + 1) else null
                }
            } else emptyList()

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
