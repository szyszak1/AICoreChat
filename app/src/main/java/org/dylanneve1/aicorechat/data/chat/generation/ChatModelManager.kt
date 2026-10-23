package org.dylanneve1.aicorechat.data.chat.generation

import android.app.Application
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map

data class StreamChunk(val text: String?)

class ChatGenerativeModel(
    private val delegate: GenerativeModel,
    private val temperature: Float,
    private val topK: Int,
) {
    fun generateContentStream(prompt: String): Flow<StreamChunk> {
        val request = generateContentRequest(TextPart(prompt)) {
            this.temperature = this@ChatGenerativeModel.temperature
            this.topK = this@ChatGenerativeModel.topK
        }
        return delegate.generateContentStream(request).map { response ->
            StreamChunk(text = response.candidates.firstOrNull()?.text)
        }
    }

    fun close() {
        delegate.close()
    }
}

class ChatModelManager(private val application: Application) {

    private var generativeModel: ChatGenerativeModel? = null

    suspend fun reinitialize(temperature: Float, topK: Int): Result<Unit> = runCatching {
        generativeModel?.close()
        val client = Generation.getClient()
        when (client.checkStatus()) {
            FeatureStatus.AVAILABLE -> Unit
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                client.download().collect { status ->
                    if (status is DownloadStatus.DownloadFailed) {
                        throw status.e
                    }
                }
            }
            FeatureStatus.UNAVAILABLE -> error("Gemini Nano model is unavailable")
        }
        client.warmup()
        generativeModel = ChatGenerativeModel(
            delegate = client,
            temperature = temperature,
            topK = topK,
        )
    }

    fun getModel(): ChatGenerativeModel? = generativeModel

    fun close() {
        generativeModel?.close()
        generativeModel = null
    }
}
