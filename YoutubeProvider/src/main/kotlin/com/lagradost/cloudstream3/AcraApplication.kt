package com.lagradost.cloudstream3

import android.content.Context

/**
 * Compile-time stand-in for the host app's AcraApplication.
 *
 * The recode library jar does not ship this class (it lives in the host app
 * APK), so plugins cannot compile against it. The host app loads plugin dex
 * files parent-first, meaning at runtime the app's real AcraApplication is
 * always resolved when present and this copy is inert.
 */
object AcraApplication {
    val context: Context? = null
}
