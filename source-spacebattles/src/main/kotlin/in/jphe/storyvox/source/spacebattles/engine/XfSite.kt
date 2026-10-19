package `in`.jphe.storyvox.source.spacebattles.engine

/**
 * Static description of one XenForo forum board with the threadmarks add-on
 * (SpaceBattles, Sufficient Velocity, Questionable Questing — all three run
 * the same software, so all parsing lives in the shared [XfEngineSource]).
 *
 * @property defaultForum node path browsed by popular()/latestUpdates(), e.g.
 *  `creative-writing.18`. XenForo has no site-wide story directory, so we use
 *  the board's main fiction forum.
 * @property forums display label → forum node path, surfaced via genres().
 *  byGenre() also accepts a raw `name.123` node path so a user can reach any
 *  forum the curated list doesn't cover.
 */
data class XfSite(
    val id: String,
    val displayName: String,
    val defaultForum: String,
    val forums: Map<String, String>,
)
