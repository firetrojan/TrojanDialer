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
                val subscriptionId = subscriptionIdFromExtras(it.handle)
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
                    carrierName = activeSubscriptions()
                        .firstOrNull { sub -> sub.subscriptionId == subscriptionIdFromExtras(it.handle) }
                        ?.carrierName?.toString()
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
        val subId = subscriptionIdFromExtras(handle)
        if (subId >= 0) {
            return activeSubscriptions().firstOrNull { it.subscriptionId == subId }
        }
        return null
    }

    /**
     * Subscription id carried in the PhoneAccount extras, or -1.
     *
     * The bundle key is "subscription_id": telecom writes it into the account
     * extras, but PhoneAccount exposes no public constant for it, so it is
     * referenced by name.
     */
    private fun subscriptionIdFromExtras(handle: PhoneAccountHandle): Int {
        val extras = telecomManager.getPhoneAccount(handle)?.extras ?: return -1
        return if (extras.containsKey(KEY_SUBSCRIPTION_ID)) {
            extras.getInt(KEY_SUBSCRIPTION_ID, -1)
        } else {
            -1
        }
    }

    private fun activeSubscriptions(): List<SubscriptionInfo> =
        subscriptionManager.activeSubscriptionInfoList ?: emptyList()

    fun findPhoneAccountByLabel(label: String): PhoneAccountInfo? {
        return _phoneAccounts.value.find { it.label == label }
    }

    fun getCallCapableAccounts(): List<PhoneAccountInfo> {
        return _phoneAccounts.value.filter { it.hasCapabilities }
    }

    /**
     * Attempts to map PhoneAccount to SubscriptionInfo using multiple strategies:
     * 1. subscription id in the PhoneAccount extras (most reliable)
     * 2. carrier name matching against SubscriptionInfo.carrierName
     * Returns null if mapping cannot be determined reliably.
     */
    fun resolveSubscriptionForPhoneAccount(handle: PhoneAccountHandle): PhoneAccountDiagnostics.SubscriptionMapping? {
        val account = telecomManager.getPhoneAccount(handle)
        val accountExtras = account?.extras
        
        // Strategy 1: subscription id in the PhoneAccount extras
        val subId = subscriptionIdFromExtras(handle)
        if (subId >= 0) {
            val subInfo = activeSubscriptions().firstOrNull { it.subscriptionId == subId }
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
        val carrierName = accountExtras?.getCharSequence(KEY_CARRIER_NAME)?.toString()
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


        // Strategy 3: Single SIM fallback
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
            if (capabilities and PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS != 0) labels.add("PLACE_EMERGENCY_CALLS")
            if (capabilities and PhoneAccount.CAPABILITY_CALL_COMPOSER != 0) labels.add("CALL_COMPOSER")
            if (capabilities and PhoneAccount.CAPABILITY_SUPPORTS_CALL_STREAMING != 0) labels.add("SUPPORTS_CALL_STREAMING")
            if (capabilities and PhoneAccount.CAPABILITY_RTT != 0) labels.add("RTT")
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
