package com.example.twinmindrecordingapphomeassignment.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.example.twinmindhomeassignmentrecordingapp.domain.model.AudioChunk
import com.example.twinmindhomeassignmentrecordingapp.domain.model.Recording
import com.example.twinmindhomeassignmentrecordingapp.domain.model.RecordingStatus
import com.example.twinmindrecordingapphomeassignment.MainActivity
import com.example.twinmindrecordingapphomeassignment.R
import com.example.twinmindrecordingapphomeassignment.data.repository.RecordingRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.random.Random
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class RecordingService : Service() {

    @Inject
    lateinit var repository: RecordingRepository

    @Inject
    lateinit var audioFocusManager: AudioFocusManager

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var timerJob: Job? = null
    private var isStopping = false

    private var audioInputStream: InputStream? = null
    private val testAudioFiles = listOf(
        R.raw.audio_file1,
        R.raw.audio_file2
    )
    private var currentAudioResource: Int? = null

    private var currentRecordingId: String? = null
    private var chunkSequence = 0
    private var startTime = 0L
    private var pausedDuration = 0L
    private var pauseStartTime = 0L

    private val _recordingDuration = MutableStateFlow(0L)
    val recordingDuration: StateFlow<Long> = _recordingDuration

    private var isPaused = false
    private var pauseReason: String? = null

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    companion object {
        const val CHANNEL_ID = "RecordingServiceChannel"
        const val NOTIFICATION_ID = 1

        const val ACTION_START_RECORDING = "START_RECORDING"
        const val ACTION_STOP_RECORDING = "STOP_RECORDING"
        const val ACTION_PAUSE_RECORDING = "PAUSE_RECORDING"
        const val ACTION_RESUME_RECORDING = "RESUME_RECORDING"

        const val EXTRA_RECORDING_ID = "recording_id"

        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_DURATION_MS = 30000L // 30 seconds
        private const val OVERLAP_MS = 2000L // 2 seconds overlap
        private const val SILENCE_THRESHOLD = 500
        private const val SILENCE_DURATION_MS = 10000L // 10 seconds
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupAudioFocusListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val recordingId = intent.getStringExtra(EXTRA_RECORDING_ID)
                    ?: UUID.randomUUID().toString()
                startRecording(recordingId)
            }
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_PAUSE_RECORDING -> pauseRecording("Manual pause")
            ACTION_RESUME_RECORDING -> resumeRecording()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording(recordingId: String) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }

        currentRecordingId = recordingId
        chunkSequence = 0
        startTime = System.currentTimeMillis()
        pausedDuration = 0L

        // Check storage
        if (!hasEnoughStorage()) {
            notifyError("Recording stopped - Low storage")
            stopSelf()
            return
        }

        // Create recording in database
        serviceScope.launch {
            val recording = Recording(
                id = recordingId,
                title = "Recording ${Date().time}",
                timestamp = startTime,
                duration = 0L,
                status = RecordingStatus.RECORDING
            )
            repository.insertRecording(recording)
            repository.saveSession(recordingId, startTime, 0L, false, null)
        }

        // Start foreground service
        startForeground(NOTIFICATION_ID, createNotification("Recording...", 0L))

        // Request audio focus
        audioFocusManager.requestAudioFocus()

        // Start recording
        startAudioRecording()
        startTimer()
    }

    private fun startAudioRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        audioRecord?.startRecording()

        recordingJob = serviceScope.launch {
            recordAudioChunks(bufferSize)
        }
    }

    private fun initializeRandomTestAudio() {
        currentAudioResource = testAudioFiles.random()
        audioInputStream?.close()
        audioInputStream = resources.openRawResource(currentAudioResource!!)
    }

    private suspend fun recordAudioChunks(bufferSize: Int) {
        val buffer = ShortArray(bufferSize)
        var chunkBuffer = mutableListOf<Short>()
        val samplesPerChunk = (SAMPLE_RATE * CHUNK_DURATION_MS / 1000).toInt()
        val overlapSamples = (SAMPLE_RATE * OVERLAP_MS / 1000).toInt()

        var silenceStartTime = 0L
        var isSilent = false

     /*   while (recordingJob?.isActive == true && !isPaused ) {
         //   val readSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0
            val readSize = if (audioRecord != null) {
                // Generate random audio data to simulate speech
                for (i in buffer.indices) {
                    buffer[i] = (Random.nextInt(-5000, 5000)).toShort() // Simulate audio
                }
                buffer.size
            } else {
                0
            }*/
        while (recordingJob?.isActive == true && !isPaused) {
            if (audioInputStream == null) {
                initializeRandomTestAudio()
            }

            val readSize = if (audioRecord != null) {
                val byteBuffer = ByteArray(buffer.size * 2)
                val bytesRead = audioInputStream?.read(byteBuffer) ?: 0

                if (bytesRead > 0) {
                    // Convert bytes to shorts
                    for (i in 0 until bytesRead / 2) {
                        buffer[i] = ((byteBuffer[i * 2 + 1].toInt() shl 8) or
                                (byteBuffer[i * 2].toInt() and 0xFF)).toShort()
                    }
                    bytesRead / 2
                } else {
                    // Switch to another random audio file when current one ends
                    initializeRandomTestAudio()
                    0
                }
            } else {
                0
            }

            if (readSize > 0) {
                // Check for silence
                val avgAmplitude = buffer.take(readSize).map {
                    kotlin.math.abs(it.toInt()) }.average()

                if (avgAmplitude < SILENCE_THRESHOLD) {
                    if (silenceStartTime == 0L) {
                        silenceStartTime = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - silenceStartTime > SILENCE_DURATION_MS && !isSilent) {
                        isSilent = true
                        notifyWarning("No audio detected - Check microphone")
                    }
                } else {
                    silenceStartTime = 0L
                    if (isSilent) {
                        isSilent = false
                        updateNotification("Recording...", getCurrentDuration())
                    }
                }

                // Add to chunk buffer
                chunkBuffer.addAll(buffer.take(readSize).toList())

                // Save chunk if reached duration
                if (chunkBuffer.size >= samplesPerChunk) {
                    saveChunk(chunkBuffer.toShortArray())

                    // Keep overlap for next chunk
                    chunkBuffer = chunkBuffer.takeLast(overlapSamples).toMutableList()
                    chunkSequence++
                }
            }

            // Check storage periodically
            if (!hasEnoughStorage()) {
                notifyError("Recording stopped - Low storage")
                withContext(Dispatchers.Main) {
                    stopRecording()
                }
                break
            }
        }

        // Save remaining buffer
        if (chunkBuffer.isNotEmpty() && !isPaused) {
            saveChunk(chunkBuffer.toShortArray())
        }
    }

    private suspend fun saveChunk(audioData: ShortArray) {
        val recordingId = currentRecordingId ?: return

        val chunkId = UUID.randomUUID().toString()
        val fileName = "chunk_${recordingId}_$chunkSequence.pcm"
        val file = File(getRecordingDir(recordingId), fileName)

        withContext(Dispatchers.IO) {
            FileOutputStream(file).use { fos ->
                val bytes = ByteArray(audioData.size * 2)
                for (i in audioData.indices) {
                    bytes[i * 2] = (audioData[i].toInt() and 0xFF).toByte()
                    bytes[i * 2 + 1] = (audioData[i].toInt() shr 8 and 0xFF).toByte()
                }
                fos.write(bytes)
            }

            val chunk = AudioChunk(
                id = chunkId,
                recordingId = recordingId,
                filePath = file.absolutePath,
                sequence = chunkSequence,
                duration = CHUNK_DURATION_MS
            )

            repository.insertChunk(chunk)

            // Start transcription for this chunk
            TranscriptionWorker.enqueueTranscription(applicationContext, recordingId, chunkId)
        }
    }

    fun pauseRecording(reason: String) {
        if (isPaused) return

        isPaused = true
        pauseReason = reason
        pauseStartTime = System.currentTimeMillis()

        audioRecord?.stop()
        recordingJob?.cancel()

        val status = when (reason) {
            "Phone call" -> "Paused - Phone call"
            "Audio focus lost" -> "Paused - Audio focus lost"
            else -> "Paused"
        }

        updateNotification(status, getCurrentDuration())

        serviceScope.launch {
            currentRecordingId?.let { id ->
                repository.updateRecordingStatus(
                    id,
                    if (reason == "Phone call") RecordingStatus.PAUSED_PHONE_CALL
                    else RecordingStatus.PAUSED_AUDIO_FOCUS
                )
                repository.saveSession(id, startTime, getCurrentDuration(), true, reason)
            }
        }
    }

    fun resumeRecording() {
        if (!isPaused) return

        isPaused = false
        pausedDuration += System.currentTimeMillis() - pauseStartTime
        pauseReason = null

        updateNotification("Recording...", getCurrentDuration())

        serviceScope.launch {
            currentRecordingId?.let { id ->
                repository.updateRecordingStatus(id, RecordingStatus.RECORDING)
                repository.saveSession(id, startTime, getCurrentDuration(), false, null)
            }
        }

        startAudioRecording()
    }

    private fun stopRecording() {
        isPaused = false

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        recordingJob?.cancel()
        timerJob?.cancel()

        audioFocusManager.abandonAudioFocus()

        val duration = getCurrentDuration()

        serviceScope.launch {
            currentRecordingId?.let { id ->
                repository.updateRecordingStatus(id, RecordingStatus.PROCESSING)
                repository.updateRecordingDuration(id, duration)
                repository.clearSession()
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

    }

    private fun startTimer() {
        timerJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                if (!isPaused) {
                    val duration = getCurrentDuration()
                    _recordingDuration.value = duration
                    updateNotification(
                        if (isPaused) "Paused - ${pauseReason ?: "Unknown"}" else "Recording...",
                        duration
                    )

                    // Save session periodically
                    currentRecordingId?.let { id ->
                        repository.saveSession(id, startTime, duration, isPaused, pauseReason)
                    }
                }
            }
        }
    }

    private fun getCurrentDuration(): Long {
        return if (isPaused) {
            pauseStartTime - startTime - pausedDuration
        } else {
            System.currentTimeMillis() - startTime - pausedDuration
        }
    }

    private fun getRecordingDir(recordingId: String): File {
        val dir = File(filesDir, "recordings/$recordingId")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun hasEnoughStorage(): Boolean {
        val dir = filesDir
        val freeSpace = dir.freeSpace
        val requiredSpace = 50 * 1024 * 1024L // 50 MB
        return freeSpace > requiredSpace
    }

    private fun setupAudioFocusListener() {
        audioFocusManager.setOnAudioFocusChangeListener { hasFocus ->
            if (!hasFocus) {
                pauseRecording("Audio focus lost")
            } else if (isPaused && pauseReason == "Audio focus lost") {
                resumeRecording()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Recording Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows recording status"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String, duration: Long): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = ACTION_STOP_RECORDING
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val timeString = formatDuration(duration)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(status)
            .setContentText("Duration: $timeString")
            .setSmallIcon(R.drawable.baseline_fiber_manual_record_24)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.baseline_stop_circle_24, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String, duration: Long) {
        notificationManager.notify(NOTIFICATION_ID, createNotification(status, duration))
    }

    private fun notifyWarning(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Warning")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_background)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(2, notification)
    }

    private fun notifyError(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Error")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_background)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(3, notification)
    }

    private fun formatDuration(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = (millis / (1000 * 60 * 60))

        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioInputStream?.close()
        serviceScope.cancel()
    }
}