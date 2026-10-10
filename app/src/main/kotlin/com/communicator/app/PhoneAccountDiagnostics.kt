package com.communicator.app

import android.content.Context
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PhoneAccountDiagnostics(private val context: Context) {

    private val telecomManager = context.getSystemService(android.telecom.TelecomManager::class.java)
    private val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)

    private val _phoneAccounts = MutableStateFlow<List<PhoneAccountInfo>>(emptyList())
    val phoneAccounts: StateFlow<List<PhoneAccountInfo>> = _phoneAccounts

    private val _subscriptions = MutableStateFlow<List<SubscriptionInfo>>(emptyList())
    val subscriptions: StateFlow<List<SubscriptionInfo>> = _subscriptions

    init {
        refresh()
    }

    fun refresh() {
        refreshPhoneAccounts()
        refreshSubscriptions()
    }

    private fun refreshPhoneAccounts() {
        val handles = telecomManager.callCapablePhoneAccounts
        val accounts = handles.mapNotNull { handle ->
            val account = telecomManager.getPhoneAccount(handle)
            account?.let {
                val subscriptionId = it.extras?.getInt(PhoneAccount.EXTRA_SUBSCRIPTION_ID, -1) ?: -1
                val hasSubscriptionId = subscriptionId >= 0
                
                PhoneAccountInfo(
                    handle = handle,
                    label = it.label?.toString() ?: "Unknown",
                    hasCapabilities = it.hasCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER),
                    capabilities = it.capabilities,
                    isEnabled = it.isEnabled,
                    subscriptionId = subscriptionId,
                    hasSubscriptionId = hasSubscriptionId,
                    supportsVideo = it.hasCapabilities(PhoneAccount.CAPABILITY_VIDEO_CALLING),
                    carrierName = it.extras?.getString(PhoneAccount.EXTRA_CARRIER_NAME)
                )
            }
        }
        _phoneAccounts.value = accounts
    }

    private fun refreshSubscriptions() {
        _subscriptions.value = subscriptionManager.activeSubscriptionInfoList ?: emptyList()
    }

    fun getPhoneAccountForSubscription(subscriptionId: Int): PhoneAccountInfo? {
        return _phoneAccounts.value.find { it.subscriptionId == subscriptionId && it.hasSubscriptionId }
    }

    fun getSubscriptionForPhoneAccount(handle: PhoneAccountHandle): SubscriptionInfo? {
        val account = telecomManager.getPhoneAccount(handle)
        val subId = account?.extras?.getInt(PhoneAccount.EXTRA_SUBSCRIPTION_ID, -1) ?: -1
        if (subId >= 0) {
            return subscriptionManager.getSubscriptionInfo(subId)
        }
        return null
    }

    fun findPhoneAccountByLabel(label: String): PhoneAccountInfo? {
        return _phoneAccounts.value.find { it.label == label }
    }

    fun getCallCapableAccounts(): List<PhoneAccountInfo> {
        return _phoneAccounts.value.filter { it.hasCapabilities }
    }

    /**
     * Attempts to map PhoneAccount to SubscriptionInfo using multiple strategies:
     * 1. EXTRA_SUBSCRIPTION_ID in PhoneAccount extras (most reliable)
     * 2. Carrier name matching with SubscriptionInfo carrierName
     * 3. MCC/MNC matching
     * Returns null if mapping cannot be determined reliably.
     */
    fun resolveSubscriptionForPhoneAccount(handle: PhoneAccountHandle): PhoneAccountDiagnostics.SubscriptionMapping? {
        val account = telecomManager.getPhoneAccount(handle)
        val accountExtras = account?.extras
        
        // Strategy 1: EXTRA_SUBSCRIPTION_ID
        val subId = accountExtras?.getInt(PhoneAccount.EXTRA_SUBSCRIPTION_ID, -1) ?: -1
        if (subId >= 0) {
            val subInfo = subscriptionManager.getSubscriptionInfo(subId)
            if (subInfo != null) {
                return PhoneAccountDiagnostics.SubscriptionMapping(
                    phoneAccountHandle = handle,
                    subscriptionId = subId,
                    subscriptionInfo = subInfo,
                    mappingMethod = PhoneAccountDiagnostics.MappingMethod.EXTRA_SUBSCRIPTION_ID,
                    confidence = PhoneAccountDiagnostics.Confidence.HIGH
                )
            }
        }

        // Strategy 2: Carrier name matching
        val carrierName = accountExtras?.getString(PhoneAccount.EXTRA_CARRIER_NAME)
        if (carrierName != null && carrierName.isNotBlank()) {
            val matchingSub = _subscriptions.value.find { sub ->
                sub.carrierName?.toString()?.equals(carrierName, ignoreCase = true) == true
            }
            if (matchingSub != null) {
                return PhoneAccountDiagnostics.SubscriptionMapping(
                    phoneAccountHandle = handle,
                    subscriptionId = matchingSub.subscriptionId,
                    subscriptionInfo = matchingSub,
                    mappingMethod = PhoneAccountDiagnostics.MappingMethod.CARRIER_NAME_MATCH,
                    confidence = PhoneAccountDiagnostics.Confidence.MEDIUM
                )
            }
        }

        // Strategy 3: MCC/MNC matching (if available in extras)
        val mcc = accountExtras?.getInt(PhoneAccount.EXTRA_MCC, -1) ?: -1
        val mnc = accountExtras?.getInt(PhoneAccount.EXTRA_MNC, -1) ?: -1
        if (mcc >= 0 && mnc >= 0) {
            val matchingSub = _subscriptions.value.find { sub ->
                sub.mcc == mcc && sub.mnc == mnc
            }
            if (matchingSub != null) {
                return PhoneAccountDiagnostics.SubscriptionMapping(
                    phoneAccountHandle = handle,
                    subscriptionId = matchingSub.subscriptionId,
                    subscriptionInfo = matchingSub,
                    mappingMethod = PhoneAccountDiagnostics.MappingMethod.MCC_MNC_MATCH,
                    confidence = PhoneAccountDiagnostics.Confidence.MEDIUM
                )
            }
        }

        // Strategy 4: Single SIM fallback
        if (_subscriptions.value.size == 1 && _phoneAccounts.value.size == 1) {
            val singleSub = _subscriptions.value.first()
            return PhoneAccountDiagnostics.SubscriptionMapping(
                phoneAccountHandle = handle,
                subscriptionId = singleSub.subscriptionId,
                subscriptionInfo = singleSub,
                mappingMethod = PhoneAccountDiagnostics.MappingMethod.SINGLE_SIM_FALLBACK,
                confidence = PhoneAccountDiagnostics.Confidence.LOW
            )
        }

        return null
    }

    data class PhoneAccountInfo(
        val handle: PhoneAccountHandle,
        val label: String,
        val hasCapabilities: Boolean,
        val capabilities: Int,
        val isEnabled: Boolean,
        val subscriptionId: Int,
        val hasSubscriptionId: Boolean,
        val supportsVideo: Boolean,
        val carrierName: String?
    ) {
        fun getCapabilityLabels(): List<String> {
            val labels = mutableListOf<String>()
            if (capabilities and PhoneAccount.CAPABILITY_CALL_PROVIDER != 0) labels.add("CALL_PROVIDER")
            if (capabilities and PhoneAccount.CAPABILITY_VIDEO_CALLING != 0) labels.add("VIDEO_CALLING")
            if (capabilities and PhoneAccount.CAPABILITY_CONNECTION_MANAGER != 0) labels.add("CONNECTION_MANAGER")
            if (capabilities and PhoneAccount.CAPABILITY_SELF_MANAGED != 0) labels.add("SELF_MANAGED")
            if (capabilities and PhoneAccount.CAPABILITY_SIP != 0) labels.add("SIP")
            if (capabilities and PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS != 0) labels.add("PLACE_EMERGENCY_CALLS")
            if (capabilities and PhoneAccount.CAPABILITY_MULTI_USER != 0) labels.add("MULTI_USER")
            if (capabilities and PhoneAccount.CAPABILITY_HOLD != 0) labels.add("HOLD")
            if (capabilities and PhoneAccount.CAPABILITY_ADD_CALL != 0) labels.add("ADD_CALL")
            if (capabilities and PhoneAccount.CAPABILITY_CONFERENCE != 0) labels.add("CONFERENCE")
            return labels
        }
    }

    data class SubscriptionMapping(
        val phoneAccountHandle: PhoneAccountHandle,
        val subscriptionId: Int,
        val subscriptionInfo: SubscriptionInfo,
        val mappingMethod: MappingMethod,
        val confidence: Confidence
    ) {
        override fun toString(): String {
            return "Mapping(method=$mappingMethod, confidence=$confidence, subId=$subscriptionId, carrier=${subscriptionInfo.carrierName})"
        }
    }

    enum class MappingMethod {
        EXTRA_SUBSCRIPTION_ID,
        CARRIER_NAME_MATCH,
        MCC_MNC_MATCH,
        SINGLE_SIM_FALLBACK,
        UNKNOWN
    }

    enum class Confidence {
        HIGH,
        MEDIUM,
        LOW,
        UNKNOWN
    }
}
