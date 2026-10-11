package tech.drakar.wallpaper

/** Settings of the app: server, timings, rendering. The protocol is described in drakar_world/docs/protocol.md. */
object Config {
    val SITE: String = BuildConfig.SITE
    const val HOME = "https://drakar.tech"                 // the wordmark on the settings screen links back to it
    val WS: String = SITE.replaceFirst("http", "ws") + "/ws"
    val EARTH = listOf("terrain.bin", "base.png", "land.bin", "coasts.bin", "contours.bin")
    const val EARTH_REFRESH_MS = 7 * 86_400_000L       // Earth data is re-validated weekly (ETag)

    // connection
    const val DEAD_AFTER_MS = 45_000L                  // no message for this long: the link is dead (server heartbeat: 20 s)
    const val BACKOFF_MIN_MS = 1_000L
    const val BACKOFF_MAX_MS = 15_000L
    const val DISCONNECT_GRACE_MS = 10_000L            // wallpaper hidden this long: disconnect
    const val ASK_TIMEOUT_MS = 20_000L                 // lease / result unanswered: give up and reconnect

    // computing
    const val VISIBLE_UNIT_EVERY_MS = 60_000L          // wallpaper visible: one unit a minute
    const val CHARGING_UNIT_EVERY_MS = 10_000L         // visible and on the charger: one every ten seconds
    const val BACKGROUND_RUN_MS = 8 * 60_000L          // background job on charger + Wi-Fi: computes this long
    const val BACKGROUND_PAUSE_MS = 2_000L
    const val LEASE_STALE_MS = 80_000L                 // a unit older than this is not sent (its 90 s lease expired)
    const val STEP_SEASONS = 12                        // seasons per engine call while animating (240 per unit)
    const val STEP_PAUSE_MS = 200L                     // pause between engine calls while visible: ~4 s per unit
    const val PROGRESS_EVERY_MS = 500L

    // rendering
    const val FPS_MOVING = 12                          // frames a second while the camera moves
    const val DATA_REDRAW_MS = 1_000L                  // world / witnesses changes: at most one redraw a second
    const val MASK_WIDTH = 4096                        // land mask texture (equirectangular, height = width / 2)
    const val PEER_TTL_MS = 6_000L
    const val CITY_SOULS = 10_000                      // each 10,000 souls of a settlement area makes a bigger point
    const val URBAN_LINK = 3                           // groups of one lineage within 3 cells form one area
}
