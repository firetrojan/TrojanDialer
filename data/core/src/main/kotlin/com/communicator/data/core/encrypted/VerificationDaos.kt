package com.communicator.data.core.encrypted

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.communicator.data.core.model.VerificationState
import kotlinx.coroutines.flow.Flow

@Dao
interface IdentityVerificationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(verification: IdentityVerificationEntity): Long
    @Update
    suspend fun update(verification: IdentityVerificationEntity): Int
    @Query("SELECT * FROM identity_verifications WHERE verificationId = :verificationId")
    suspend fun getById(verificationId: String): IdentityVerificationEntity?
    @Query("SELECT * FROM identity_verifications WHERE identityKeyId = :identityKeyId AND peerIdentityKeyId = :peerIdentityKeyId")
    suspend fun getByIdentityPair(identityKeyId: String, peerIdentityKeyId: String): IdentityVerificationEntity?
    @Query("SELECT * FROM identity_verifications WHERE identityKeyId = :identityKeyId")
    suspend fun getByIdentityKeyId(identityKeyId: String): List<IdentityVerificationEntity>
    @Query("SELECT * FROM identity_verifications WHERE peerIdentityKeyId = :peerIdentityKeyId")
    suspend fun getByPeerIdentityKeyId(peerIdentityKeyId: String): List<IdentityVerificationEntity>
    @Query("SELECT * FROM identity_verifications WHERE verificationState = :state")
    suspend fun getByVerificationState(state: VerificationState): List<IdentityVerificationEntity>
    @Query("SELECT * FROM identity_verifications WHERE trustLevel = :trustLevel")
    suspend fun getByTrustLevel(trustLevel: TrustLevel): List<IdentityVerificationEntity>
    @Query("SELECT * FROM identity_verifications WHERE fingerprint = :fingerprint")
    suspend fun getByFingerprint(fingerprint: String): List<IdentityVerificationEntity>
    @Query("DELETE FROM identity_verifications WHERE verificationId = :verificationId")
    suspend fun delete(verificationId: String): Int
    @Query("SELECT * FROM identity_verifications ORDER BY createdAt DESC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<IdentityVerificationEntity>>
}
@Dao
interface VerificationEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: VerificationEventEntity): Long
    @Query("SELECT * FROM verification_events WHERE verificationId = :verificationId ORDER BY timestamp DESC")
    suspend fun getByVerificationId(verificationId: String): List<VerificationEventEntity>
    @Query("SELECT * FROM verification_events WHERE eventType = :eventType ORDER BY timestamp DESC")
    suspend fun getByEventType(eventType: VerificationEventType): List<VerificationEventEntity>
    @Query("SELECT * FROM verification_events WHERE timestamp >= :since ORDER BY timestamp DESC")
    suspend fun getSince(since: Long): List<VerificationEventEntity>
    @Query("DELETE FROM verification_events WHERE verificationId = :verificationId")
    suspend fun deleteByVerificationId(verificationId: String): Int
    @Query("DELETE FROM verification_events WHERE timestamp < :before")
    suspend fun deleteOldEvents(before: Long): Int
}
@Dao
interface QrVerificationPayloadDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(payload: QrVerificationPayloadEntity): Long
    @Query("SELECT * FROM qr_verification_payloads WHERE payloadId = :payloadId")
    suspend fun getById(payloadId: String): QrVerificationPayloadEntity?
    @Query("SELECT * FROM qr_verification_payloads WHERE identityKeyId = :identityKeyId ORDER BY timestamp DESC")
    suspend fun getByIdentityKeyId(identityKeyId: String): List<QrVerificationPayloadEntity>
    @Query("SELECT * FROM qr_verification_payloads WHERE nonce = :nonce")
    suspend fun getByNonce(nonce: String): QrVerificationPayloadEntity?
    @Query("DELETE FROM qr_verification_payloads WHERE payloadId = :payloadId")
    suspend fun delete(payloadId: String): Int
    @Query("DELETE FROM qr_verification_payloads WHERE expiresAt IS NOT NULL AND expiresAt < :now")
    suspend fun deleteExpired(now: Long): Int
}
@Dao
interface SafetyNumberDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(safetyNumber: SafetyNumberEntity): Long
    @Query("SELECT * FROM safety_numbers WHERE safetyNumberId = :safetyNumberId")
    suspend fun getById(safetyNumberId: String): SafetyNumberEntity?
    @Query("SELECT * FROM safety_numbers WHERE identityKeyId = :identityKeyId AND peerIdentityKeyId = :peerIdentityKeyId")
    suspend fun getByIdentityPair(identityKeyId: String, peerIdentityKeyId: String): SafetyNumberEntity?
    @Query("SELECT * FROM safety_numbers WHERE identityKeyId = :identityKeyId")
    suspend fun getByIdentityKeyId(identityKeyId: String): List<SafetyNumberEntity>
    @Query("SELECT * FROM safety_numbers WHERE verificationState = :state")
    suspend fun getByVerificationState(state: VerificationState): List<SafetyNumberEntity>
    @Update
    suspend fun update(safetyNumber: SafetyNumberEntity): Int
    @Query("DELETE FROM safety_numbers WHERE safetyNumberId = :safetyNumberId")
    suspend fun delete(safetyNumberId: String): Int
}
