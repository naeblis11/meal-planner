package com.naeblis11.mealplanner.desktop.peers

/**
 * One Meal Planner PC as its mDNS record says (P6-R3). [name] is the PC's name; [householdHash] the first 16 hex
 * digits of SHA-256 of its household id (the id itself is never sent); [since] when that household was created, in
 * epoch milliseconds; [instanceId] 32 hex digits naming the install, so a PC can tell its own record; [app] its
 * version. A peer's address and port are never kept (P6-R11).
 */
data class PeerRecord(
    val name: String,
    val householdHash: String,
    val since: Long,
    val instanceId: String,
    val app: String,
)
