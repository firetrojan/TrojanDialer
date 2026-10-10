package com.communicator.app

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CallLog
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CallHistoryRepository(private val context: Context) {

    private val callLogUri = Uri.parse("content://call_log/calls")

    suspend fun getRecentCalls(limit: Int = 50): Result<List<CallRecord>> = withContext(Dispatchers.IO) {
        try {
            val projection = arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.TYPE,
                CallLog.Calls.PHONE_ACCOUNT_ID,
                CallLog.Calls.SUB_ID
            )

            val sortOrder = "${CallLog.Calls.DATE} DESC LIMIT $limit"

            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )

            val calls = mutableListOf<CallRecord>()
            cursor?.use { c ->
                val idIndex = c.getColumnIndex(CallLog.Calls._ID)
                val numberIndex = c.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIndex = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val dateIndex = c.getColumnIndex(CallLog.Calls.DATE)
                val durationIndex = c.getColumnIndex(CallLog.Calls.DURATION)
                val typeIndex = c.getColumnIndex(CallLog.Calls.TYPE)
                val accountIndex = c.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
                val subIdIndex = c.getColumnIndex(CallLog.Calls.SUB_ID)

                while (c.moveToNext()) {
                    val callType = when (c.getInt(typeIndex)) {
                        CallLog.Calls.INCOMING_TYPE -> CallType.INCOMING
                        CallLog.Calls.OUTGOING_TYPE -> CallType.OUTGOING
                        CallLog.Calls.MISSED_TYPE -> CallType.MISSED
                        CallLog.Calls.VOICEMAIL_TYPE -> CallType.VOICEMAIL
                        CallLog.Calls.REJECTED_TYPE -> CallType.REJECTED
                        CallLog.Calls.BLOCKED_TYPE -> CallType.BLOCKED
                        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> CallType.ANSWERED_EXTERNALLY
                        else -> CallType.UNKNOWN
                    }

                    calls.add(CallRecord(
                        id = c.getLong(idIndex),
                        number = c.getString(numberIndex) ?: "",
                        name = c.getString(nameIndex),
                        date = c.getLong(dateIndex),
                        duration = c.getInt(durationIndex),
                        type = callType,
                        phoneAccountId = c.getString(accountIndex),
                        subscriptionId = if (subIdIndex >= 0) c.getInt(subIdIndex) else -1
                    ))
                }
            }

            Result.success(calls)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    enum class CallType {
        INCOMING,
        OUTGOING,
        MISSED,
        VOICEMAIL,
        REJECTED,
        BLOCKED,
        ANSWERED_EXTERNALLY,
        UNKNOWN
    }

    data class CallRecord(
        val id: Long,
        val number: String,
        val name: String?,
        val date: Long,
        val duration: Int,
        val type: CallType,
        val phoneAccountId: String?,
        val subscriptionId: Int
    )
}
