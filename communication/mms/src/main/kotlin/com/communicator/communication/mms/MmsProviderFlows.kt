package com.communicator.communication.mms

import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import com.communicator.communication.mms.MmsTransport.MmsMessage
import com.communicator.communication.mms.MmsTransport.MmsThread
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Reads MMS rows from the platform provider and re-emits them when it changes.
 *
 * The previous implementation called `ContentResolver.query(...).asFlow()`,
 * which cannot work: a Cursor is not a Flow source. Each helper here performs the
 * query inside a callbackFlow, re-runs it when a ContentObserver reports a
 * change, and unregisters the observer when the collector stops.
 */

/**
 * Emits every element of [load] initially, then again on each change to [uri].
 */
private fun <T> contentFlow(
    context: Context,
    uri: Uri,
    load: () -> List<T>
): Flow<T> = callbackFlow {
    fun emitAll() {
        load().forEach { element ->
            trySend(element)
        }
    }

    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = emitAll()
    }
    context.contentResolver.registerContentObserver(uri, true, observer)
    // Emit once immediately so callers get a value without waiting for a change.
    emitAll()
    awaitClose { context.contentResolver.unregisterContentObserver(observer) }
}

/** Re-emits the MMS inbox whenever the provider changes. */
fun observeMmsChanges(
    context: Context,
    toMessage: (Cursor) -> MmsMessage?
): Flow<MmsMessage> =
    contentFlow(context, Telephony.Mms.CONTENT_URI) {
        val out = mutableListOf<MmsMessage>()
        context.contentResolver
            .query(Telephony.Mms.CONTENT_URI, null, null, null, null)
            ?.use { c ->
                while (c.moveToNext()) toMessage(c)?.let { out.add(it) }
            }
        out
    }

/** Re-emits the MMS thread list whenever the provider changes. */
fun observeThreadChanges(
    context: Context,
    toThread: (Cursor) -> MmsThread?
): Flow<MmsThread> =
    contentFlow(context, Telephony.Threads.CONTENT_URI) {
        val out = mutableListOf<MmsThread>()
        context.contentResolver
            .query(Telephony.Threads.CONTENT_URI, null, null, null, null)
            ?.use { c ->
                while (c.moveToNext()) toThread(c)?.let { out.add(it) }
            }
        out
    }

/** Re-emits one thread's messages whenever the provider changes. */
fun observeThreadMessages(
    context: Context,
    threadId: String,
    toMessage: (Cursor) -> MmsMessage?
): Flow<MmsMessage> {
    // content://mms/threadId/<id> is the provider's per-thread inbox URI.
    val uri = Telephony.Mms.CONTENT_URI
        .buildUpon()
        .appendPath("threadId")
        .appendPath(threadId)
        .build()
    return contentFlow(context, uri) {
        val out = mutableListOf<MmsMessage>()
        context.contentResolver
            .query(uri, null, "thread_id = ?", arrayOf(threadId), null)
            ?.use { c ->
                while (c.moveToNext()) toMessage(c)?.let { out.add(it) }
            }
        out
    }
}
