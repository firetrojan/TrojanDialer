package com.communicator.communication.recording

import com.communicator.data.core.model.TransportCapabilities

/**
 * Provider abstraction for call recording.
 *
 * Recording is not guaranteed to be supported by every transport.
 * This interface allows pluggable recording providers:
 * - Native Telecom recording
 * - External recorder apps (e.g. via intents)
 * - Shizuku-based recorders (NOT mandatory)
 * - Local app recording (where permitted)
 */
interface RecordingProvider {
    val id: String
    val name: String
    val capabilities: TransportCapabilities

    /** Whether this provider can record at all on this device. */
    fun isAvailable(): Boolean

    /** Starts recording the given call. */
    suspend fun startRecording(callId: String): RecordingResult

    /** Stops recording the given call. */
    suspend fun stopRecording(callId: String): RecordingResult

    /** Returns whether recording is currently active for [callId]. */
    fun isRecording(callId: String): Boolean

    /** Returns the file path/URI of the recording for [callId]. */
    suspend fun getRecordingUri(callId: String): String?

    /** Deletes the recording for [callId]. */
    suspend fun deleteRecording(callId: String): RecordingResult

    data class RecordingResult(
        val success: Boolean,
        val recordingId: String? = null,
        val error: Throwable? = null,
        val metadata: Map<String, String> = emptyMap()
    ) {
        companion object {
            fun success(recordingId: String): RecordingResult = RecordingResult(true, recordingId)
            fun failure(error: Throwable): RecordingResult = RecordingResult(false, error = error)
        }
    }
}

/**
 * Configuration for automatic recording.
 */
data class RecordingConfig(
    val isEnabled: Boolean = false,
    val autoRecordIncoming: Boolean = false,
    val autoRecordOutgoing: Boolean = false,
    val format: RecordingFormat = RecordingFormat.MP4,
    val isEncrypted: Boolean = false,
    val retentionDays: Int = 30,
    val perContactOverrides: Map<String, Boolean> = emptyMap(),
    val perNumberOverrides: Map<String, Boolean> = emptyMap(),
    val simSpecificOverrides: Map<Int, Boolean> = emptyMap()
)

enum class RecordingFormat {
    MP4,
    M4A,
    WAV,
    AAC
}

/**
 * Recorded call data model.
 */
data class RecordedCall(
    val recordingId: String,
    val callId: String,
    val contactId: String?,
    val filePath: String,
    val format: RecordingFormat,
    val duration: Long,
    val sizeBytes: Long,
    val isEncrypted: Boolean,
    val createdAt: Long,
    val deletedAt: Long? = null,
    val metadata: Map<String, String> = emptyMap()
)
