package com.communicator.data.core.encrypted

import kotlinx.serialization.Serializable

/**
 * Human-verifiable identity fingerprint derived from the long-term MLS
 * identity public material. Uses the MLS identity public key (signature key)
 * and credential identity as the stable basis.
 *
 * The fingerprint is stable across normal message/epoch changes and
 * KeyPackage rotations because it's derived from the long-term identity
 * signature key (Ed25519) and the credential identity (display name),
 * not from ephemeral KeyPackage material.
 */
@Serializable
data class IdentityFingerprint(
    /** Base64-encoded SHA-256 of the identity signature public key || credential identity */
    val fingerprint: String,
    /** Base64 identity public key (Ed25519 signature public key) */
    val identityPublicKeyB64: String,
    /** Human-readable identity name from the credential */
    val displayName: String,
    /** Short safety number for human comparison (first 8 chars of fingerprint) */
    val safetyNumber: String,
    /** Creation timestamp */
    val createdAt: Long
) {
    override fun toString(): String = fingerprint
}
