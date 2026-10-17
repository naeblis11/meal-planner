package com.naeblis11.mealplanner.desktop

import com.naeblis11.mealplanner.settings.DeviceRow
import com.naeblis11.mealplanner.settings.HandoverView
import com.naeblis11.mealplanner.settings.PairingControls
import com.naeblis11.mealplanner.settings.PairingView
import com.naeblis11.mealplanner.settings.SyncServerState
import com.naeblis11.mealplanner.settings.SyncServerStatus
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow

/** Settings' "Phones and PCs" without a sync port: counts what it is asked, and fails or waits when told to. */
class FakePairingControls : PairingControls {
    override val devices = MutableStateFlow<List<DeviceRow>>(emptyList())
    override val offer = MutableStateFlow<PairingView?>(null)
    override val status = MutableStateFlow(SyncServerStatus(SyncServerState.OFF, 5001))
    override val certificateNotice = MutableStateFlow<String?>(null)
    val opens = AtomicInteger()
    val closes = AtomicInteger()
    val removed = CopyOnWriteArrayList<String>()
    override val handover = MutableStateFlow<HandoverView?>(null)
    val offeredTo = CopyOnWriteArrayList<String>()

    /** What open() shows. */
    @Volatile
    var next = PairingView("123456", Long.MAX_VALUE, listOf("192.0.2.20"), null, null)

    @Volatile
    var gate: CountDownLatch? = null

    @Volatile
    var failOpen: Exception? = null

    @Volatile
    var failRemove: Exception? = null

    override suspend fun open() {
        opens.incrementAndGet()
        gate?.await(5, TimeUnit.SECONDS)
        failOpen?.let { throw it }
        offer.value = next
        status.value = SyncServerStatus(SyncServerState.LISTENING, 5001)
    }

    override fun close() {
        closes.incrementAndGet()
        offer.value = null
    }

    override suspend fun remove(deviceId: String) {
        failRemove?.let { throw it }
        removed += deviceId
        devices.value = devices.value.filter { it.deviceId != deviceId }
    }

    override fun offerHandover(deviceId: String) {
        offeredTo += deviceId
        val row = devices.value.first { it.deviceId == deviceId }
        handover.value = HandoverView(deviceId, row.name, Long.MAX_VALUE, handing = false)
    }

    override fun cancelHandover() {
        handover.value = null
    }
}
