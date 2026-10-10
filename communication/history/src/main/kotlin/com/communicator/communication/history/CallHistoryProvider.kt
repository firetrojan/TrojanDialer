package com.communicator.communication.history

import com.communicator.data.core.model.Call
import com.communicator.data.core.model.CallDirection
import com.communicator.data.core.model.CallStatus

/**
 * Call history provider.
 * Stores and manages call logs.
 */
interface CallHistoryProvider {
    suspend fun insertCall(call: Call): Result<String>
    suspend fun updateCall(call: Call): Result<Unit>
    suspend fun deleteCall(callId: String): Result<Unit>
    suspend fun deleteCalls(callIds: List<String>): Result<Unit>
    suspend fun getCall(callId: String): Result<Call?>
    suspend fun getAllCalls(): kotlinx.coroutines.flow.Flow<List<Call>>
    suspend fun getCallsForContact(contactId: String): kotlinx.coroutines.flow.Flow<List<Call>>
    suspend fun getRecentCalls(limit: Int = 100): kotlinx.coroutines.flow.Flow<List<Call>>
    suspend fun searchCalls(query: String): kotlinx.coroutines.flow.Flow<List<Call>>
    suspend fun filterCalls(filter: CallFilter): kotlinx.coroutines.flow.Flow<List<Call>>
    suspend fun groupCalls(): kotlinx.coroutines.flow.Flow<List<CallGroup>>
}

data class CallFilter(
    val direction: CallDirection? = null,
    val status: List<CallStatus> = emptyList(),
    val transportId: String? = null,
    val subscriptionId: Int? = null,
    val dateFrom: Long? = null,
    val dateTo: Long? = null,
    val isPrivate: Boolean? = null
)

data class CallGroup(
    val contactId: String?,
    val number: String?,
    val calls: List<Call>,
    val count: Int,
    val lastCall: Call,
    val totalCount: Int,
    val missedCount: Int
)
