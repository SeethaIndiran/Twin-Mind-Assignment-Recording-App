package com.example.twinmindrecordingapphomeassignment.service


import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.example.twinmindhomeassignmentrecordingapp.domain.model.MeetingSummary
import com.example.twinmindhomeassignmentrecordingapp.domain.model.RecordingStatus
import com.example.twinmindrecordingapphomeassignment.data.remote.api.ChatGptService
import com.example.twinmindrecordingapphomeassignment.data.remote.api.WhisperApiService
import com.example.twinmindrecordingapphomeassignment.data.repository.RecordingRepository
import com.example.twinmindrecordingapphomeassignment.service.SummaryWorker.Companion.WORK_NAME_PREFIX
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

@HiltWorker
class TranscriptionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: RecordingRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val recordingId = inputData.getString(KEY_RECORDING_ID) ?: return@withContext Result.failure()
        val chunkId = inputData.getString(KEY_CHUNK_ID) ?: return@withContext Result.failure()

        try {
            // Transcribe the chunk
            val result = repository.transcribeChunk(chunkId)

            if (result.isSuccess) {
                // Check if all chunks are transcribed
                val untranscribedCount = repository.getUntranscribedChunksCount(recordingId)

                if (untranscribedCount == 0) {
                    // All chunks transcribed - generate full transcript
                    val fullTranscript = repository.getFullTranscript(recordingId)
                    repository.updateRecordingTranscript(recordingId, fullTranscript)
                    repository.updateRecordingStatus(recordingId, RecordingStatus.COMPLETED)

                    // Enqueue summary generation
                    SummaryWorker.enqueueSummaryGeneration(applicationContext, recordingId)
                }

                Result.success()
            } else {
                // Retry on failure
                if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        private const val KEY_RECORDING_ID = "recording_id"
        private const val KEY_CHUNK_ID = "chunk_id"
        const val WORK_NAME_PREFIX = "transcription_"

        fun enqueueTranscription(context: Context, recordingId: String, chunkId: String) {
            val inputData = Data.Builder()
                .putString(KEY_RECORDING_ID, recordingId)
                .putString(KEY_CHUNK_ID, chunkId)
                .build()

            val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setInputData(inputData)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "$WORK_NAME_PREFIX$chunkId",
                    ExistingWorkPolicy.KEEP,
                    request
                )
        }
    }
}
@HiltWorker
class SummaryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: RecordingRepository,
    private val chatGptService: ChatGptService
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val recordingId = inputData.getString(KEY_RECORDING_ID) ?: return@withContext Result.failure()

        try {
            val recording = repository.getRecordingById(recordingId)
            val transcript = recording?.transcript

            if (transcript.isNullOrBlank()) {
                return@withContext Result.failure()
            }

            // Generate summary using ChatGPT with streaming
            var finalSummary: MeetingSummary? = null
            repository.generateSummaryStreaming(recordingId, transcript) { summary ->
                finalSummary = summary
            }

            return@withContext if (finalSummary != null) {
                Result.success()
            } else {
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }

        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun extractTitle(summary: String): String {
        // Extract title from the ChatGPT response
        // Assuming the format starts with "Title: "
        return summary.lineSequence()
            .firstOrNull { it.startsWith("Title:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?: "Meeting Summary"
    }

    private fun extractMainSummary(summary: String): String {
        // Extract main summary between "Summary:" and "Key Points:"
        val summaryStart = summary.indexOf("Summary:", ignoreCase = true)
        val summaryEnd = summary.indexOf("Key Points:", ignoreCase = true)
        return if (summaryStart != -1 && summaryEnd != -1) {
            summary.substring(summaryStart + 8, summaryEnd).trim()
        } else {
            summary
        }
    }

    private fun extractKeyPoints(summary: String): List<String> {
        // Extract key points between "Key Points:" and "Action Items:"
        val keyPointsStart = summary.indexOf("Key Points:", ignoreCase = true)
        val keyPointsEnd = summary.indexOf("Action Items:", ignoreCase = true)
        if (keyPointsStart != -1 && keyPointsEnd != -1) {
            return summary.substring(keyPointsStart + 11, keyPointsEnd)
                .trim()
                .split("\n")
                .filter { it.isNotBlank() }
                .map { it.trim().removePrefix("-").trim() }
        }
        return emptyList()
    }

    private fun extractActionItems(summary: String): List<String> {
        // Extract action items after "Action Items:"
        val actionItemsStart = summary.indexOf("Action Items:", ignoreCase = true)
        if (actionItemsStart != -1) {
            return summary.substring(actionItemsStart + 13)
                .trim()
                .split("\n")
                .filter { it.isNotBlank() }
                .map { it.trim().removePrefix("-").trim() }
        }
        return emptyList()
    }

    companion object {
        private const val KEY_RECORDING_ID = "recording_id"
        const val WORK_NAME_PREFIX = "summary_"

        fun enqueueSummaryGeneration(context: Context, recordingId: String) {
            val inputData = Data.Builder()
                .putString(KEY_RECORDING_ID, recordingId)
                .build()

            val request = OneTimeWorkRequestBuilder<SummaryWorker>()
                .setInputData(inputData)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "$WORK_NAME_PREFIX$recordingId",
                    ExistingWorkPolicy.KEEP,
                    request
                )
        }
    }
}

/*@HiltWorker
class SummaryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: RecordingRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val recordingId = inputData.getString(KEY_RECORDING_ID) ?: return@withContext Result.failure()

        try {
            val recording = repository.getRecordingById(recordingId)
            val transcript = recording?.transcript

            if (transcript.isNullOrBlank()) {
                return@withContext Result.failure()
            }

            // Generate summary
            val result = repository.generateSummary(recordingId, transcript)

            if (result.isSuccess) {
                Result.success()
            } else {
                if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        private const val KEY_RECORDING_ID = "recording_id"
        const val WORK_NAME_PREFIX = "summary_"

        fun enqueueSummaryGeneration(context: Context, recordingId: String) {
            val inputData = Data.Builder()
                .putString(KEY_RECORDING_ID, recordingId)
                .build()

            val request = OneTimeWorkRequestBuilder<SummaryWorker>()
                .setInputData(inputData)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "$WORK_NAME_PREFIX$recordingId",
                    ExistingWorkPolicy.KEEP,
                    request
                )
        }
    }
}*/

@HiltWorker
class RecordingTerminationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: RecordingRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Check if there's an active session
            val session = repository.getSession()

            session?.let {
                val recordingId = it.recordingId ?: return@withContext Result.success()

                // Update recording status
                repository.updateRecordingStatus(recordingId, RecordingStatus.PROCESSING)

                // Get all chunks and ensure they're processed
                val chunks = repository.getChunksByRecordingId(recordingId)

                // Enqueue transcription for any untranscribed chunks
                chunks.filter { chunk -> !chunk.transcribed }.forEach { chunk ->
                    TranscriptionWorker.enqueueTranscription(
                        applicationContext,
                        recordingId,
                        chunk.id
                    )
                }

                // Clear session
                repository.clearSession()
            }

            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "recording_termination"

        fun enqueueTerminationWork(context: Context) {
            val request = OneTimeWorkRequestBuilder<RecordingTerminationWorker>()
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }
    }

}

@HiltWorker
class WhisperTranscriptionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val apiService: WhisperApiService
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val recordingId = inputData.getString(KEY_RECORDING_ID) ?: return Result.failure()
        val chunkId = inputData.getString(KEY_CHUNK_ID) ?: return Result.failure()

        return try {
            // Get the audio file
            val audioFile = File(applicationContext.filesDir, "recordings/$recordingId/chunk_${recordingId}_$chunkId.pcm")
            if (!audioFile.exists()) {
                return Result.failure()
            }

            // Make API call directly with the file
            val transcription = apiService.transcribe(audioFile)

            // Save transcription to database
            withContext(Dispatchers.IO) {
                // Save transcription logic here
            }
            Result.success()

        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val KEY_RECORDING_ID = "recording_id"
        private const val KEY_CHUNK_ID = "chunk_id"

        fun enqueueTranscription(
            context: Context,
            recordingId: String,
            chunkId: String
        ) {
            val workRequest = OneTimeWorkRequestBuilder<WhisperTranscriptionWorker>()
                .setInputData(
                    workDataOf(
                        KEY_RECORDING_ID to recordingId,
                        KEY_CHUNK_ID to chunkId
                    )
                )
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "${WORK_NAME_PREFIX}${recordingId}_$chunkId",
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )
        }
    }
}