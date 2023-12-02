package io.github.lauzhack.backend.features.session

import io.github.lauzhack.backend.api.openAI.OpenAIMessage
import io.github.lauzhack.backend.api.openAI.OpenAIRequest
import io.github.lauzhack.backend.api.openAI.OpenAIService
import io.github.lauzhack.backend.data.Resources.Prompt.ExtractJsonFromUserMessagePrompt
import io.github.lauzhack.backend.data.Resources.Prompt.GenerateQuestionForMissingJsonPrompt
import io.github.lauzhack.common.api.*
import io.github.lauzhack.common.api.AssistantRole.Assistant
import io.github.lauzhack.common.api.AssistantRole.User
import io.github.lauzhack.common.api.PlanningOptions
import kotlinx.serialization.encodeToString

class Session(
    private val enqueue: (BackendToUserMessage) -> Unit,
    private val openAIService: OpenAIService,
) {

  private var currentPlanning = PlanningOptions()
  private val conversation = mutableListOf<OpenAIMessage>()

  companion object {
    private const val ROLE_USER = "user"
    private const val ROLE_SYSTEM = "system"
    private const val ROLE_ASSISTANT = "assistant"
    private const val STRING_JSON_INJECT = "\$INJECT_CURRENT_JSON\$"
  }

  suspend fun process(message: UserToBackendMessage) {
    when (message) {
      is UserToAssistantMessage -> handleUserToAssistantMessage(message)
      is UserToAssistantSetPlanning -> currentPlanning = message.planningOptions
    }
  }

  private suspend fun handleUserToAssistantMessage(message: UserToAssistantMessage) {
    updateConversation(message.text, ROLE_USER)
    val extractJsonPrompt = ExtractJsonFromUserMessagePrompt
    val extractPromptResponse = openAIResponseForConversation(extractJsonPrompt)
    val json = extractPromptResponse.choices.firstOrNull()?.message?.content ?: "{}"

    updatePlanning(json)

    val currentJson = DefaultJsonSerializer.encodeToString(currentPlanning)
    val questionPrompt =
        GenerateQuestionForMissingJsonPrompt.replace(STRING_JSON_INJECT, currentJson)
    val questionPromptResponse = openAIResponseForConversation(questionPrompt)
    val question =
        questionPromptResponse.choices.firstOrNull()?.message?.content
            ?: "An error occurred. Please try again."

    updateConversation(question, ROLE_ASSISTANT)
    enqueueConversationToUser()
    enqueuePlanningToUser()
  }

  private fun updatePlanning(json: String) {
    val extracted = DefaultJsonSerializer.decodeFromString(PlanningOptions.serializer(), json)
    println(extracted)
    currentPlanning = currentPlanning.updatedWith(extracted)
  }

  private suspend fun openAIResponseForConversation(prompt: String, role: String = ROLE_SYSTEM) =
      openAIService.prompt(
          OpenAIRequest(messages = conversation + OpenAIMessage(role = role, content = prompt)))

  private fun updateConversation(question: String, role: String) {
    conversation.add(
        OpenAIMessage(
            role = role,
            content = question,
        ),
    )
  }

  private fun enqueueConversationToUser() {
    enqueue(
        AssistantToUserConversation(
            conversation
                .filter { it.role == ROLE_ASSISTANT || it.role == ROLE_USER }
                .map {
                  AssistantToUserMessage(
                      role = if (it.role == ROLE_USER) User else Assistant,
                      text = it.content,
                  )
                },
        ))
  }

  private fun enqueuePlanningToUser() {
    enqueue(
        AssistantToUserSetPlanning(
            planningOptions = currentPlanning,
        ))
  }
}
