package com.naeblis11.mealplanner.desktop.sync

import com.naeblis11.mealplanner.data.PairedDeviceEntity
import com.naeblis11.mealplanner.sync.HandoverBundle
import com.naeblis11.mealplanner.sync.HandoverRequest
import com.naeblis11.mealplanner.sync.HandoverWire
import com.naeblis11.mealplanner.sync.PairReply
import com.naeblis11.mealplanner.sync.PairingWire
import com.naeblis11.mealplanner.sync.SyncErrors
import com.naeblis11.mealplanner.sync.SyncHub
import com.naeblis11.mealplanner.sync.SyncPaths
import com.naeblis11.mealplanner.sync.SyncPhotos
import com.naeblis11.mealplanner.sync.SyncWire
import kotlinx.coroutines.CancellationException

/**
 * The sync port's routes (LAN sync spec, section 2):
 * - `POST /sync/pair`: no key. Answers only while [window] is open and the proof is right ([PairingWindow.claim], which
 *   uses the code up); it pairs the device and then closes the window, after the reply is built, so the sync port is
 *   still wanted while this pairing is answered.
 * - `POST /sync/exchange`, `GET` and `PUT /sync/photo/{uuid}`: a paired device's key ([devices]); they go to [hub].
 * - While this PC yields to an older household's ([lanAllowed] false), every route says `not_master`. The port is
 *   closed while it yields, so a device meets this only when the yield starts while its connection is open; otherwise
 *   it finds the PC unreachable.
 * - Stage 4b: an exchange tells the PC the role is offered to ([HandoverRoutes.offeredTo]). `POST /sync/handover/take`
 *   (that PC's key, a PC only) answers the bundle and starts the hand-over; while it runs ([HandoverRoutes.handing]) every
 *   other request is `not_master`; `POST /sync/handover/done` (the taker's key) finishes it, 204. Either answers 409
 *   `no_handover` when nothing is offered to, or handing over to, that PC.
 * - `POST /sync/leave`: a paired device's key; it is removed as Remove removes it ([onLeft]) and answered 204, with no body read (stage 2c).
 * - Anything else is `not_found`.
 * - A failure is logged as its method, route and class only, never its message, a key, a code or a body, and answered
 *   `failed`.
 */
class SyncRoutes(
    private val hub: SyncHub,
    private val window: PairingWindow,
    private val devices: PairedDevices,
    private val fingerprint: () -> String,
    private val household: suspend () -> String,
    private val pcName: () -> String,
    private val lanAllowed: () -> Boolean = { true },
    /** A device was paired; said before the window closes, so the port stays wanted for this reply. */
    private val onPaired: suspend () -> Unit = {},
    private val onExchanged: suspend () -> Unit = {},
    /** A paired device left by itself (`POST /sync/leave`); by default it is simply removed. */
    private val onLeft: suspend (deviceId: String) -> Unit = { devices.remove(it) },
    private val log: (String) -> Unit = { System.err.println(it) },
    /** Stage 4b: the hand-over of the main PC's role; null (most tests) means none is ever offered. */
    private val handover: HandoverRoutes? = null,
) : SyncHandler {
    private sealed class Route(val label: String) {
        object PairDevice : Route(SyncPaths.PAIR)
        object Exchange : Route(SyncPaths.EXCHANGE)
        object Leave : Route(SyncPaths.LEAVE)
        object Take : Route(SyncPaths.HANDOVER_TAKE)
        object Done : Route(SyncPaths.HANDOVER_DONE)
        class GetPhoto(val uuid: String) : Route(SyncPaths.PHOTO + "{uuid}")
        class PutPhoto(val uuid: String) : Route(SyncPaths.PHOTO + "{uuid}")
    }

    override suspend fun handle(request: SyncHttpRequest): SyncHttpResponse {
        val route = routeOf(request) ?: return SyncHttpResponse.error(404, SyncErrors.NOT_FOUND)
        return try {
            if (!lanAllowed() || (route != Route.Done && handover?.handing() == true)) {
                SyncHttpResponse.error(409, SyncErrors.NOT_MASTER)
            } else {
                when (route) {
                    Route.PairDevice -> pair(request)
                    Route.Exchange -> exchange(request)
                    Route.Leave -> leave(request)
                    Route.Take -> take(request)
                    Route.Done -> done(request)
                    is Route.GetPhoto -> getPhoto(request, route.uuid)
                    is Route.PutPhoto -> putPhoto(request, route.uuid)
                }
            }
        } catch (e: BodyTooLargeException) {
            SyncHttpResponse.error(413, SyncErrors.TOO_LARGE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Meal Planner: ${request.method} ${route.label} failed: ${e.javaClass.simpleName}")
            SyncHttpResponse.error(500, SyncErrors.FAILED)
        }
    }

    private fun routeOf(request: SyncHttpRequest): Route? = when {
        request.method == "POST" && request.path == SyncPaths.PAIR -> Route.PairDevice
        request.method == "POST" && request.path == SyncPaths.EXCHANGE -> Route.Exchange
        request.method == "POST" && request.path == SyncPaths.LEAVE -> Route.Leave
        request.method == "POST" && request.path == SyncPaths.HANDOVER_TAKE -> Route.Take
        request.method == "POST" && request.path == SyncPaths.HANDOVER_DONE -> Route.Done
        request.path.startsWith(SyncPaths.PHOTO) -> {
            // The whole segment must be a uuid (matches, not find): "..%2Fx", "a/b" and anything longer are no route.
            val uuid = request.path.substring(SyncPaths.PHOTO.length).takeIf { SyncPaths.UUID.matches(it) }
            when {
                uuid == null -> null
                request.method == "GET" -> Route.GetPhoto(uuid)
                request.method == "PUT" -> Route.PutPhoto(uuid)
                else -> null
            }
        }
        else -> null
    }

    private suspend fun pair(request: SyncHttpRequest): SyncHttpResponse {
        if (!window.isOpen()) return notPairing()
        if (request.contentLength > PAIR_MAX_BYTES) return SyncHttpResponse.error(413, SyncErrors.TOO_LARGE)
        val asked = try {
            PairingWire.decodeRequest(request.body(PAIR_MAX_BYTES).toString(Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            return SyncHttpResponse.error(400, SyncErrors.BAD_REQUEST)
        }
        val mine = fingerprint()
        // A null is a wrong proof or a code already used, unless the window shut meanwhile (expired, or that was the fifth failure).
        val hubProof = window.claim(asked.proof, asked.nonce, mine)
            ?: return if (window.isOpen()) SyncHttpResponse.error(403, SyncErrors.WRONG_CODE) else notPairing()
        try {
            // Everything that can fail is asked before the device is registered, so a failure leaves no device without its key.
            val hubId = hub.hubId()
            val householdId = household()
            val name = pcName()
            val reply = PairReply(hubProof, devices.register(asked), hubId, householdId, name)
            onPaired()
            return SyncHttpResponse.json(200, PairingWire.encode(reply))
        } finally {
            // The code is used up whatever happened; the window closes only now, after the reply is ready.
            window.close()
        }
    }

    private suspend fun exchange(request: SyncHttpRequest): SyncHttpResponse {
        val device = devices.authenticate(request.authorization) ?: return unpaired()
        if (request.contentLength > EXCHANGE_MAX_BYTES) return SyncHttpResponse.error(413, SyncErrors.TOO_LARGE)
        val asked = try {
            SyncWire.decodeRequest(request.body(EXCHANGE_MAX_BYTES).toString(Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            return SyncHttpResponse.error(400, SyncErrors.BAD_REQUEST)
        }
        val reply = hub.exchange(device.deviceId, asked)
        // The exchange itself worked (its changes are applied): a failed date must not turn its reply into a failure,
        // or the device would send them again. Logged as its class only, as onExchanged's failures are.
        try {
            devices.touch(device.deviceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Meal Planner: dating a device's sync failed: ${e.javaClass.simpleName}")
        }
        onExchanged()
        val offered = handover?.offeredTo(device.deviceId) == true
        return SyncHttpResponse.json(200, SyncWire.encode(if (offered) reply.copy(handover = true) else reply))
    }

    private suspend fun take(request: SyncHttpRequest): SyncHttpResponse {
        val device = devices.authenticate(request.authorization) ?: return unpaired()
        val side = handover ?: return noHandover()
        if (device.kind != PairingWire.KIND_PC) return noHandover()
        if (request.contentLength > HandoverWire.MAX_REQUEST_BYTES) return SyncHttpResponse.error(413, SyncErrors.TOO_LARGE)
        val asked = try {
            HandoverWire.decodeRequest(request.body(HandoverWire.MAX_REQUEST_BYTES).toString(Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            return SyncHttpResponse.error(400, SyncErrors.BAD_REQUEST)
        }
        val bundle = side.take(device, asked) ?: return noHandover()
        return SyncHttpResponse.json(200, HandoverWire.encode(bundle))
    }

    private suspend fun done(request: SyncHttpRequest): SyncHttpResponse {
        val device = devices.authenticate(request.authorization) ?: return unpaired()
        val side = handover ?: return noHandover()
        return if (side.done(device)) SyncHttpResponse.NO_CONTENT else noHandover()
    }

    private suspend fun leave(request: SyncHttpRequest): SyncHttpResponse {
        val device = devices.authenticate(request.authorization) ?: return unpaired()
        onLeft(device.deviceId)
        return SyncHttpResponse.NO_CONTENT
    }

    private suspend fun getPhoto(request: SyncHttpRequest, uuid: String): SyncHttpResponse {
        if (devices.authenticate(request.authorization) == null) return unpaired()
        val bytes = hub.photo(uuid) ?: return SyncHttpResponse.error(404, SyncErrors.NOT_FOUND)
        return SyncHttpResponse(200, SyncHttpResponse.JPEG, bytes)
    }

    private suspend fun putPhoto(request: SyncHttpRequest, uuid: String): SyncHttpResponse {
        if (devices.authenticate(request.authorization) == null) return unpaired()
        if (request.contentLength > SyncPhotos.MAX_BYTES) return SyncHttpResponse.error(413, SyncErrors.TOO_LARGE)
        val bytes = request.body(SyncPhotos.MAX_BYTES)
        return if (hub.receivePhoto(uuid, bytes)) SyncHttpResponse.NO_CONTENT else SyncHttpResponse.error(409, SyncErrors.REFUSED)
    }

    private fun noHandover() = SyncHttpResponse.error(409, SyncErrors.NO_HANDOVER)

    private fun notPairing() = SyncHttpResponse.error(404, SyncErrors.NOT_PAIRING)

    private fun unpaired() = SyncHttpResponse.error(401, SyncErrors.UNPAIRED)

    companion object {
        const val PAIR_MAX_BYTES = 4 * 1024
        const val EXCHANGE_MAX_BYTES = 50 * 1024 * 1024
    }
}

/** Stage 4b: what the routes ask of the main PC's hand-over (DesktopSync). */
interface HandoverRoutes {
    /** The role is offered to [deviceId] just now. */
    fun offeredTo(deviceId: String): Boolean

    /** Handing over just now: every request but the taker's done is `not_master`. */
    fun handing(): Boolean

    /** [device] takes the role: the bundle, and the hand-over begins; null when it isn't offered to [device]. */
    suspend fun take(device: PairedDeviceEntity, request: HandoverRequest): HandoverBundle?

    /** [device] is done: true when it was the one handing over (this PC then becomes its client, off this thread). */
    suspend fun done(device: PairedDeviceEntity): Boolean
}
