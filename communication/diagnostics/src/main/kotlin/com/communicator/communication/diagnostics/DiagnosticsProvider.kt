package com.communicator.communication.diagnostics

import com.communicator.data.core.model.TransportCapabilities

/**
 * Diagnostic capability states.
 * Do not collapse these states.
 */
enum class CapabilityState {
    SUPPORTED,
    AVAILABLE,
    UNAVAILABLE,
    NOT_EXPOSED,
    NOT_PERMITTED,
    REQUIRES_CARRIER,
    REQUIRES_SYSTEM_PRIVILEGE,
    REQUIRES_ROOT,
    REQUIRES_EXTERNAL_HARDWARE,
    NOT_IMPLEMENTED,
    UNKNOWN
}

/**
 * Diagnostic information for a transport.
 */
data class TransportDiagnostics(
    val transportId: String,
    val transportName: String,
    val isInstalled: Boolean,
    val isAvailable: Boolean,
    val capabilities: Map<String, CapabilityState>,
    val details: Map<String, String> = emptyMap()
)

/**
 * Device information.
 */
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdk: Int,
    val romBuild: String?,
    val isLocked: Boolean
)

/**
 * SIM information for diagnostics.
 */
data class SimInfo(
    val subscriptionId: Int,
    val slot: Int,
    val carrier: String?,
    val mcc: Int,
    val mnc: Int,
    val number: String?,
    val isDefaultVoice: Boolean,
    val isDefaultSms: Boolean
)

/**
 * IMS diagnostic state.
 */
data class ImsDiagnostics(
    val subscriptionId: Int,
    val isRegistered: Boolean,
    val mmTelAvailable: Boolean,
    val volteState: CapabilityState,
    val voWifiState: CapabilityState,
    val vilteState: CapabilityState,
    val capabilitySource: String?,
    val carrierConfigState: Map<String, String>
)

/**
 * Telecom diagnostic state.
 */
data class TelecomDiagnostics(
    val isDefaultDialer: Boolean,
    val phoneAccounts: List<PhoneAccountInfo>,
    val callCapabilities: Map<String, CapabilityState>,
    val videoCapability: CapabilityState
)

data class PhoneAccountInfo(
    // This parameter was missing `val`, so the primary constructor had a
    // non-property parameter and the data class could not compile:
    //   "Primary constructor of data class must only have property
    //    ('val' / 'var') parameters."
    val label: String,
    val address: String?,
    val capabilities: TransportCapabilities,
    val isEnabled: Boolean
)

/**
 * Full system diagnostics.
 */
data class SystemDiagnostics(
    val device: DeviceInfo,
    val sims: List<SimInfo>,
    val ims: List<ImsDiagnostics>,
    val telecom: TelecomDiagnostics,
    val transports: List<TransportDiagnostics>,
    val capabilities: Map<String, CapabilityState>
)

/**
 * Diagnostics provider interface.
 */
interface DiagnosticsProvider {
    suspend fun getDeviceInfo(): DeviceInfo
    suspend fun getSimInfo(): List<SimInfo>
    suspend fun getImsDiagnostics(): List<ImsDiagnostics>
    suspend fun getTelecomDiagnostics(): TelecomDiagnostics
    suspend fun getTransportDiagnostics(): List<TransportDiagnostics>
    suspend fun getFullDiagnostics(): SystemDiagnostics
}

/**
 * Transcription provider interface for local processing.
 */
interface TranscriptionProvider {
    suspend fun transcribeAudio(audioUri: String): Result<Transcription>
    suspend fun transcribeCall(callId: String): Result<Transcription>
    suspend fun isAvailable(): Boolean

    data class Transcription(
        val transcriptionId: String,
        val callId: String?,
        val text: String,
        val confidence: Float,
        val language: String,
        val durationMs: Long,
        val createdAt: Long
    )
}

/**
 * Summary provider for call intelligence.
 */
interface SummaryProvider {
    suspend fun summarizeCall(callId: String): Result<CallSummary>
    suspend fun summarizeTranscription(transcriptionId: String): Result<CallSummary>
    suspend fun isAvailable(): Boolean

    data class CallSummary(
        val summaryId: String,
        val callId: String?,
        val summary: String,
        val keywords: List<String>,
        val tags: List<String>,
        val duration: Long
    )
}

/**
 * Caller classifier.
 */
interface CallerClassifier {
    suspend fun classifyCall(callerNumber: String, callerName: String?): CallerClassification
    suspend fun isSpam(number: String): Boolean
    suspend fun isOtp(number: String, content: String): Boolean
    suspend fun isFraud(number: String): Boolean
    suspend fun getRepeatedCallerState(number: String): RepeatedCallerInfo

    data class CallerClassification(
        val number: String,
        val classification: CallerClass,
        val confidence: Float,
        val labels: List<String>,
        val metadata: Map<String, String>
    )

    enum class CallerClass {
        SPAM,
        FRAUD,
        OTP,
        BUSINESS,
        PERSONAL,
        UNKNOWN
    }

    data class RepeatedCallerInfo(
        val number: String,
        val callCount: Int,
        val lastCallTime: Long,
        val firstCallTime: Long,
        val isFrequent: Boolean
    )
}
