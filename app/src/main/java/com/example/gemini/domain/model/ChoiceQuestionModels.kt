package com.example.gemini.domain.model

import exa.language_server_pb.AskQuestionEntry
import kotlinx.serialization.Serializable
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
        /**
         * Constructs a ChoiceQuestionnaire directly from typed Wire Protobuf AskQuestionEntry objects.
         */
        fun fromProto(title: String, questions: List<AskQuestionEntry>, description: String? = null): ChoiceQuestionnaire {
            return ChoiceQuestionnaire(
                title = title.ifBlank { "Clarification & Options" },
                description = description,
                questions = questions.mapIndexed { idx, q ->
                    ChoiceQuestion(
                        id = (idx + 1).toString(),
                        prompt = q.question,
                        isMultiSelect = q.is_multi_select,
                        options = q.options.mapIndexed { optIdx, opt ->
                            ChoiceOption(
                                id = opt.id.ifBlank { (optIdx + 1).toString() },
                                label = opt.text
                            )
                        }
                    )
                }
            )
        }
    }
}
