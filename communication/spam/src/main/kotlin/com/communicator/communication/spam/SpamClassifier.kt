package com.communicator.communication.spam

import com.communicator.data.core.model.TransportCapabilities

/**
 * Spam classification provider.
 * Local-only mode supported; no mandatory cloud API.
 */
interface SpamClassifier {
    suspend fun classify(number: String): SpamClassification
    suspend fun classifyMessage(content: String, sender: String): SpamClassification
    suspend fun isBlocked(number: String): Boolean
    suspend fun blockNumber(number: String)
    suspend fun unblockNumber(number: String)
    suspend fun getBlocklist(): List<BlockedEntry>
    suspend fun importBlocklist(source: String): Int
    suspend fun exportBlocklist(): String

    data class SpamClassification(
        val number: String,
        val classification: SpamClass,
        val confidence: Float,
        val labels: List<String>,
        val metadata: Map<String, String>
    )

    enum class SpamClass {
        UNKNOWN,
        SPAM,
        FRAUD,
        NON_SPAM,
        OTP,
        BUSINESS
    }

    data class BlockedEntry(
        val id: String,
        val number: String,
        val label: String?,
        val createdAt: Long,
        val reason: String?
    )
}

val SpamClassificationCapabilities = TransportCapabilities(setOf())
