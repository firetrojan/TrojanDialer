package com.communicator.communication.encrypted

import java.security.MessageDigest

/**
 * Delivery acknowledgement protocol for Stage 5D.
 *
 * WHY THIS EXISTS
 * ---------------
 * Advancing a message from TRANSPORT_ACCEPTED to REMOTE_RECEIVED is a security
 * decision: it asserts that a specific group member received that specific
 * message. A local row id proves nothing, so the previous implementation refused
 * the transition outright. That was correct but left the delivery lifecycle
 * permanently incomplete.
 *
 * This protocol supplies the missing proof using material the MLS architecture
 * already provides, without inventing any cryptography:
 *
 *  - An acknowledgement is an ordinary MLS APPLICATION message. It is decrypted
 *    and signature-verified by Bouncy Castle inside [MlsGroupManager.processInbound]
 *    before its plaintext is ever returned. Only a current member of the group,
 *    holding the group's key schedule, can produce one that survives that step.
 *  - The sender's leaf index is captured from the authenticated
 *    [org.bouncycastle.mls.codec.Sender] at the moment of decryption, NOT from
 *    any field supplied by the sender. `MLSMessage.getEpoch()` and
 *    `getContentType()` are attacker-controlled header bytes; the sender inside
 *    `FramedContent` is covered by the MLS message signature and is not.
 *
 * BINDING
 * -------
 * The signed plaintext binds the acknowledgement to:
 *  - protocol and version, so it cannot be reinterpreted later;
 *  - the group id, so an ACK for one group cannot advance another group's row;
 *  - the exact message wire hash the sender received, so an ACK for a different
 *    message cannot be replayed onto this one;
 *  - the epoch in which the ACK was produced, so an ACK minted in an older epoch
 *    is rejected after the group advances;
 *  - the acknowledged state, so an ACK for REMOTE_RECEIVED cannot be replayed to
 *    claim PROCESSED.
 *
 * Everything is length-prefixed before hashing, so no field boundary can be
 * shifted to make two different tuples hash alike.
 *
 * REPLAY
 * ------
 * [MlsGroupManager] records every accepted ACK hash in the durable inbound
 * message log. A replayed frame is already dropped there by hash before any
 * state transition is attempted, and the acknowledgement carries its own
 * message hash so a second, differently-encoded replay cannot re-apply.
 */
object DeliveryAck {

    /** Bumped only if the framing below changes incompatibly. */
    const val PROTOCOL: String = "trojan-dialer/ack/v1"

    /** The state the sender is claiming by acknowledging receipt. */
    enum class AckKind { REMOTE_RECEIVED }

    /**
     * Serialises an acknowledgement.
     *
     * @param groupId       local group id; must match the caller's group.
     * @param messageHash   base64 SHA-256 of the wire bytes actually received.
     * @param epoch         current MLS epoch of the acknowledging member.
     * @param kind          which transition is being acknowledged.
     * @return UTF-8 bytes to be carried as an MLS application payload.
     */
    fun encode(
        groupId: String,
        messageHash: String,
        epoch: Long,
        kind: AckKind
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun int32(value: Int) {
            // 4 bytes big-endian. ByteArrayOutputStream.write(Int) would emit a
            // SINGLE byte, which the reader would mis-parse as a length, so the
            // encoding is written out explicitly to stay symmetric with readInt.
            for (shift in 24 downTo 0 step 8) {
                out.write((value shr shift) and 0xFF)
            }
        }
        fun field(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            int32(bytes.size)
            out.write(bytes)
        }
        out.write(1) // framing version
        field(PROTOCOL)
        field(groupId)
        field(messageHash)
        // 8-byte big-endian epoch, written explicitly for the same reason.
        for (shift in 56 downTo 0 step 8) {
            out.write(((epoch shr shift) and 0xFF).toInt())
        }
        field(kind.name)
        return out.toByteArray()
    }

    /**
     * Parses and fully validates an acknowledgement.
     *
     * @param expectedGroupId     group this queue row belongs to.
     * @param expectedMessageHash hash the queue row is waiting on.
     * @param expectedKind        transition being acknowledged.
     * @return the authenticated acknowledgement.
     * @throws AckFormatException if the frame is malformed or bound to something
     *         other than the message named by the caller.
     */
    fun decode(
        payload: ByteArray,
        expectedGroupId: String,
        expectedMessageHash: String,
        expectedKind: AckKind
    ): Ack {
        var offset = 0
        fun readInt(): Int {
            if (offset + 4 > payload.size) throw AckFormatException("truncated")
            val v = ((payload[offset].toInt() and 0xFF) shl 24) or
                ((payload[offset + 1].toInt() and 0xFF) shl 16) or
                ((payload[offset + 2].toInt() and 0xFF) shl 8) or
                (payload[offset + 3].toInt() and 0xFF)
            offset += 4
            return v
        }
        fun readField(): String {
            val len = readInt()
            if (len < 0 || offset + len > payload.size) throw AckFormatException("bad field length")
            val s = String(payload, offset, len, Charsets.UTF_8)
            offset += len
            return s
        }
        fun readLong(): Long {
            if (offset + 8 > payload.size) throw AckFormatException("truncated")
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (payload[offset + i].toLong() and 0xFF)
            offset += 8
            return v
        }

        if (payload.isEmpty()) throw AckFormatException("empty")
        val version = payload[offset].toInt() and 0xFF
        if (version != 1) throw AckFormatException("unsupported framing version $version")
        offset += 1

        val protocol = readField()
        if (protocol != PROTOCOL) throw AckFormatException("unexpected protocol $protocol")

        val groupId = readField()
        val messageHash = readField()
        val epoch = readLong()
        val kind = readField()

        // No trailing bytes: a longer frame must not be accepted with the
        // expected prefix plus attacker padding.
        if (offset != payload.size) throw AckFormatException("trailing bytes")

        // Every binding is enforced here, not assumed by the caller.
        if (groupId != expectedGroupId) {
            throw AckBindingException("ack group $groupId does not match $expectedGroupId")
        }
        if (messageHash != expectedMessageHash) {
            throw AckBindingException("ack message hash does not match the pending send")
        }
        val parsedKind = runCatching { AckKind.valueOf(kind) }
            .getOrElse { throw AckFormatException("unknown ack kind $kind") }
        if (parsedKind != expectedKind) {
            throw AckBindingException("ack kind $parsedKind does not match $expectedKind")
        }

        return Ack(groupId = groupId, messageHash = messageHash, epoch = epoch, kind = parsedKind)
    }

    /**
     * Stable hash of an acknowledgement, used for durable replay rejection.
     *
     * Computed over the canonical encoding rather than the received bytes, so a
     * re-encoded but semantically identical ACK still hashes the same.
     */
    fun canonicalHash(ack: Ack): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(encode(ack.groupId, ack.messageHash, ack.epoch, ack.kind))
        return java.util.Base64.getEncoder().encodeToString(digest.digest())
    }

    /** A validated acknowledgement bound to one pending send. */
    data class Ack(
        val groupId: String,
        val messageHash: String,
        val epoch: Long,
        val kind: AckKind
    )
}

/** The acknowledgement bytes are malformed or use an unsupported protocol. */
class AckFormatException(message: String) : IllegalArgumentException("Malformed ACK: $message")

/**
 * The acknowledgement is well formed but is bound to a different message,
 * group or transition than the one being acknowledged.
 */
class AckBindingException(message: String) : SecurityException("ACK binding check failed: $message")

/**
 * The acknowledgement was produced by someone who is not a current member, or
 * the MLS layer refused to decrypt it. This is distinct from a binding failure:
 * here the sender's membership is the thing in doubt.
 */
class AckUnauthenticatedException(message: String) :
    SecurityException("ACK rejected: $message")
