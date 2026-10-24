package org.dylanneve1.aicorechat.data.chat.prompt

import org.dylanneve1.aicorechat.data.chat.model.ChatMessage
import org.dylanneve1.aicorechat.data.chat.model.ChatUiState
import org.dylanneve1.aicorechat.data.context.PersonalContextProvider
import org.dylanneve1.aicorechat.data.prompt.PromptTemplates

class ChatPromptBuilder(
    private val personalContextProvider: PersonalContextProvider,
) {

    suspend fun buildInitialPrompt(
        state: ChatUiState,
        userPrompt: String,
        userMessage: ChatMessage,
        allowSearch: Boolean,
        offlineNotice: Boolean,
    ): String {
        val builder = StringBuilder()
        builder.append(PromptTemplates.systemPreamble(allowSearch = allowSearch, offlineNotice = offlineNotice))

        if (state.customInstructionsEnabled && state.customInstructions.isNotBlank()) {
            builder.append(PromptTemplates.customInstructionsBlock(state.customInstructions))
        }

        builder.append(PromptTemplates.fewShotGeneral())

        if (state.personalContextEnabled) {
            builder.append(personalContextProvider.build(state.userName))
        }

        if (state.memoryContextEnabled) {
            val relevantMemories = PromptTemplates.buildMemoryContextFromQuery(
                query = userPrompt,
                allMemories = state.memoryEntries,
            )
            val bioInfo = if (state.bioContextEnabled) state.bioInformation else null
            builder.append(PromptTemplates.memoryContextBlock(relevantMemories, bioInfo))
        }

        if (state.multimodalEnabled) {
            state.messages.forEach { message ->
                if (!message.imageDescription.isNullOrBlank()) {
                    builder.append("[IMAGE_DESCRIPTION]\n${message.imageDescription}\n[/IMAGE_DESCRIPTION]\n\n")
                }
            }
        }

        val history = state.messages.takeLast(6)
        val priorMessages = history.filter { it.id != userMessage.id }
        priorMessages.forEach { message ->
            if (message.isFromUser) {
                builder.append("[USER]\n${message.text}\n[/USER]\n")
            } else {
                builder.append("[ASSISTANT]\n${message.text}\n[/ASSISTANT]\n")
            }
        }

        builder.append("[USER]\n$userPrompt (Odpowiedz po polsku)\n[/USER]\n")
        builder.append("[ASSISTANT]\n")
        return builder.toString()
    }

    fun buildSearchFollowUpPrompt(
        state: ChatUiState,
        userMessage: ChatMessage,
        recentMessages: List<ChatMessage>,
        webResults: String,
    ): String {
        val builder = StringBuilder()
        builder.append("Zadanie: Przeczytaj poniższy tekst źródłowy i wyciągnij z niego odpowiedź po polsku.\n")
        builder.append("Odpowiadaj wyłącznie na podstawie podanego tekstu źródłowego. Nie oceniaj, czy dane są aktualne – po prostu odczytaj liczby i fakty z tekstu.\n\n")

        builder.append("[USER]\n")
        builder.append("TEKST ŹRÓDŁOWY:\n")
        builder.append("$webResults\n\n")
        builder.append("Zadanie: Korzystając wyłącznie z powyższego TEKSTU ŹRÓDŁOWEGO, odpowiedz krótko po polsku na temat: ${userMessage.text}\n")
        builder.append("[/USER]\n")
        builder.append("[ASSISTANT]\nWedług podanych informacji: ")
        return builder.toString()
    }
}
