package com.communicator.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point.
 *
 * Hilt builds the object graph here, so every @AndroidEntryPoint component
 * (including the manifest-declared [SmsReceiver]) gets its dependencies injected
 * before any broadcast is delivered. There is deliberately no manual wiring and
 * no service-locator: a missing provider must fail at startup rather than leave
 * inbound SMS silently undelivered.
 *
 * The previous version constructed a throwaway [WapPushReceiver] purely to set a
 * callback on an instance the system would never use, since the manifest creates
 * its own. That was dead code and is gone: WAP push handling belongs to the
 * manifest-declared receiver, which sets its own callback.
 */
@HiltAndroidApp
class CommunicatorApplication : Application()
