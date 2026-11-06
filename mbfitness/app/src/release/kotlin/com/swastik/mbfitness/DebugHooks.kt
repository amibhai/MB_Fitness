package com.swastik.mbfitness

import android.content.Context

/** Empty on purpose: the test hooks in src/debug must never ship. */
object DebugHooks {
    @Suppress("UNUSED_PARAMETER")
    fun onActivityCreated(activity: MainActivity) = Unit

    fun wrapContext(base: Context): Context = base
}
