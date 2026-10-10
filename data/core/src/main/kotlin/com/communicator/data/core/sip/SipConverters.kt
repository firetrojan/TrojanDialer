package com.communicator.data.core.sip

import androidx.room.TypeConverter

class SipConverters {

    @TypeConverter
    fun fromCallState(state: CallState?): String? = state?.name

    @TypeConverter
    fun toCallState(name: String?): CallState? = name?.let { CallState.valueOf(it) }

    @TypeConverter
    fun fromMessageDirection(dir: MessageDirection?): String? = dir?.name

    @TypeConverter
    fun toMessageDirection(name: String?): MessageDirection? = name?.let { MessageDirection.valueOf(it) }

    @TypeConverter
    fun fromDeliveryState(state: DeliveryState?): String? = state?.name

    @TypeConverter
    fun toDeliveryState(name: String?): DeliveryState? = name?.let { DeliveryState.valueOf(it) }

    @TypeConverter
    fun fromEncryptionState(state: EncryptionState?): String? = state?.name

    @TypeConverter
    fun toEncryptionState(name: String?): EncryptionState? = name?.let { EncryptionState.valueOf(it) }

    @TypeConverter
    fun fromCallDirection(dir: CallDirection?): String? = dir?.name

    @TypeConverter
    fun toCallDirection(name: String?): CallDirection? = name?.let { CallDirection.valueOf(it) }
}
