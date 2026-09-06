package `in`.jphe.storyvox.source.endless.config

import kotlinx.coroutines.flow.Flow

/**
 * Endless-LitRPG daemon connection config — the read side.
 *
 * The daemon is a LAN service on a host that moves (katana today,
 * `familiar` later), so its address is a **user setting, not a
 * constant**. There is deliberately no default host: a compiled-in
 * address would be wrong the first time the daemon moves, and a
 * wrong-but-present default is worse than an empty one because it
 * fails as a timeout instead of as "not configured yet".
 *
 * The implementation lives in `:app` (`EndlessConfigImpl`, backed by
 * its own small DataStore) — same split as
 * [`in`.jphe.storyvox.source.mempalace.config.PalaceConfig] and
 * `source-outline`'s `OutlineConfig`: this module stays free of
 * Preferences/DataStore plumbing and only ever **reads**, so the
 * source cannot mutate the config it consumes. The write side and the
 * Settings field are contributed in `:app` through the generic
 * `SourceConfigContributor` seam (#1531).
 *
 * There is no credential here because the daemon has no auth — every
 * route is open on a LAN port, a documented and accepted decision in
 * the endless-litrpg spec §9.1. That is why this contract carries a
 * host and nothing else, and why the source never returns
 * [`in`.jphe.storyvox.data.source.model.FictionResult.AuthRequired].
 */
interface EndlessConfig {

    /** Hot stream — re-emits whenever the user edits the host. */
    val state: Flow<EndlessConfigState>

    /** One-shot snapshot for a single request. */
    suspend fun current(): EndlessConfigState
}

data class EndlessConfigState(
    /**
     * Empty until the user configures a host. Otherwise the raw
     * settings value — `host[:port]`, with an optional scheme. The
     * source normalizes it (defaulting to `http://`, since the daemon
     * has no TLS) and rejects anything that doesn't resolve LAN-local.
     */
    val host: String,
) {
    val isConfigured: Boolean get() = host.isNotBlank()
}
