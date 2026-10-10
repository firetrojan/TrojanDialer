package com.communicator.data.core.sms

import androidx.room.TypeConverter

/**
 * Type converters for the SMS tables.
 *
 * SmsMessageEntity.recipients is a `List<String>`, which Room cannot persist
 * without a converter. Without this class KSP fails with:
 *   SmsEntities.kt:51: Cannot figure out how to save this property into
 *   database. You can consider adding a type converter for it.
 *
 * The encoding is a JSON array, matching how the encrypted-message converters
 * already store string lists, so both families read and write the same shape.
 */
class SmsConverters {

    @TypeConverter
    fun fromStringList(values: List<String>?): String? =
        values?.let { list -> list.joinToString(separator = ",", prefix = "[", postfix = "]") { escape(it) } }

    @TypeConverter
    fun toStringList(stored: String?): List<String>? {
        if (stored == null) return null
        val trimmed = stored.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return emptyList()
        val body = trimmed.removePrefix("[").removeSuffix("]")
        if (body.isEmpty()) return emptyList()
        return splitEscaped(body).map { unescape(it) }
    }

    /** Commas and brackets inside an address would corrupt the encoding. */
    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace(",", "\\,").replace("[", "\\[").replace("]", "\\]")

    private fun unescape(value: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                out.append(value[i + 1]); i += 2
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    /** Splits on unescaped commas only. */
    private fun splitEscaped(body: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                current.append(c).append(body[i + 1]); i += 2
            } else if (c == ',') {
                parts.add(current.toString()); current.setLength(0); i++
            } else {
                current.append(c); i++
            }
        }
        parts.add(current.toString())
        return parts.filter { it.isNotEmpty() }
    }
}
