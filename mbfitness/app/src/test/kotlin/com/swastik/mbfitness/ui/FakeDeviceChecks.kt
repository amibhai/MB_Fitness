package com.swastik.mbfitness.ui

import android.content.Context
import com.swastik.mbfitness.work.DeviceChecks
import com.swastik.mbfitness.work.SimChoice

/** The phone's settings, as Health check and onboarding see them. Tests change these directly. */
class FakeDeviceChecks(context: Context) : DeviceChecks(context) {
    var exactAlarms: Boolean? = true
    var battery = true
    var notifications = true
    var hibernation: Boolean? = true
    var sim = true
    var chosenSimGone = false
    var asksWhichSim = false
    var outsideStore = true
    var exitReason: String? = null
    var sims = emptyList<SimChoice>()
    var slots = 1
    var canList = true

    override fun exactAlarmsAllowed() = exactAlarms
    override fun batteryExempt() = battery
    override fun notificationsAllowed() = notifications
    override fun hibernationExempt() = hibernation
    override fun simSlots() = slots
    override fun hasSim() = sim
    override fun canListSims() = canList
    override fun activeSims() = sims
    override fun chosenSimMissing(chosen: Int?) = chosenSimGone
    override fun phoneAsksWhichSim() = asksWhichSim
    override fun installedOutsideStore() = outsideStore
    override fun lastExitReason() = exitReason
    override fun databaseBytes() = 2_097_152L
}
