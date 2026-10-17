package com.naeblis11.mealplanner.desktop.peers

/**
 * What the last look found (P6-R7): [peers] the other PCs' names; [yieldTo] the older household's PC this one leaves
 * Google Calendar and Alexa to, or null; [twoMasters] another main PC of this household (stage 4a), or null, while which
 * this PC serves no phone or PC; [off] there is no network to look on (P6-R5); [notice] the one line the window and
 * Settings show, or null.
 */
data class PeerStatus(
    val peers: List<String> = emptyList(),
    val yieldTo: String? = null,
    val twoMasters: String? = null,
    val off: Boolean = false,
    val notice: String? = null,
)

/** Who yields (P6-R7). Both PCs run it on the same two records and reach the same answer. */
object PeerDecision {
    /**
     * [own] against [seen]: our own record (by instance id) and repeats are dropped, and at most PeerTxt.MAX_PEERS names are listed.
     * Only masters count (stage 4a): a client PC follows its master, so it never makes anyone yield or keep, and is never
     * a second master. A master of another household that is older makes this PC yield to the oldest such (a client PC
     * too, so it doesn't send to Google beside it); a newer one is a notice on a master. A master of the same household beside a
     * master is [PeerStatus.twoMasters] (both sides see it); a client never sees it, since its own master isn't its business.
     */
    fun decide(own: PeerRecord, seen: List<PeerRecord>): PeerStatus {
        val others = seen.asSequence()
            .filter { it.instanceId != own.instanceId }
            .distinctBy { it.instanceId }
            .toList()
        val masters = others.filter { it.role == PeerTxt.ROLE_MASTER }
        val separate = masters.filter { it.householdHash != own.householdHash }
        // Chosen over every distinct peer, so a flood of newer records can't hide the older household; only the list shown is capped.
        val older = separate.filter { isOlder(it, own) }.minWithOrNull(compareBy<PeerRecord>({ it.since }, { it.householdHash }))
        val twin = if (own.role == PeerTxt.ROLE_MASTER) masters.firstOrNull { it.householdHash == own.householdHash } else null
        val notice = when {
            older != null -> PeerMessages.yielding(older.name)
            twin != null -> PeerMessages.twoMasters(twin.name)
            separate.isNotEmpty() && own.role == PeerTxt.ROLE_MASTER -> PeerMessages.keeping(separate.first().name)
            else -> null
        }
        return PeerStatus(
            peers = others.take(PeerTxt.MAX_PEERS).map { it.name },
            yieldTo = older?.name,
            twoMasters = twin?.name,
            notice = notice,
        )
    }

    /** True when [peer]'s household is older than [own]'s: created earlier, or at the same moment with the lower hash. */
    fun isOlder(peer: PeerRecord, own: PeerRecord): Boolean =
        peer.since < own.since || (peer.since == own.since && peer.householdHash < own.householdHash)
}
