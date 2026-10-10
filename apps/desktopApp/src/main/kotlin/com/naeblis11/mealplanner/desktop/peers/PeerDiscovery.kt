package com.naeblis11.mealplanner.desktop.peers

/**
 * How this PC finds other Meal Planner PCs (P6-R2): JmdnsDiscovery on the network, FakeDiscovery in tests. Every call
 * blocks; none belongs on the UI thread.
 */
interface PeerDiscovery {
    /**
     * Announces [record] for the server's [port] until [close]. False when discovery is off: no network interface to
     * announce on (P6-R5), or none of them would start; it may then be called again (P6-T4a), but never after true.
     */
    fun announce(record: PeerRecord, port: Int): Boolean

    /**
     * Looks for up to [millis] and returns the records seen, this PC's own among them, decoded and bounded (PeerTxt);
     * empty when off or closed. A [close] ends it early; an interrupt ends it with InterruptedException.
     */
    fun browse(millis: Long): List<PeerRecord>

    /** Stops announcing and looking, within about a second; calling it again does nothing. */
    fun close()
}
