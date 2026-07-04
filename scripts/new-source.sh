#!/usr/bin/env bash
#
# new-source.sh — scaffold a new Candela FictionSource plugin module.
#
#   scripts/new-source.sh [MODE] <id> "<Display Name>"
#   e.g. scripts/new-source.sh demofeed "Demo Feed"
#
# MODE (optional, mutually exclusive — pick the one that matches your backend):
#   (none)          Anonymous JSON HTTP source (the default; source-hackernews).
#   --auth|--oauth  Token-authed HTTP source (OAuth/BYOK): request() threads a
#                   Bearer token via an accessToken() seam, and the contract test
#                   wires a non-blank fake token so the IO-pin probe sees traffic
#                   (#1529). For Reddit-BYOK / Google-Drive-OAuth class sources.
#   --xml|--feed    XML/Atom/RSS HTTP source: request() sends an XML Accept header
#                   and drops the JSON (kotlinx-serialization) parse path (#1533).
#                   For arxiv / rss / primegaming (Atom) class sources.
#   --local         Non-HTTP local-provider source: no OkHttp, no serialization,
#                   no HTTP contract kit — injects a small <Name>Reader seam and a
#                   plain fake-backed unit test instead (#1526). For ocr / epub /
#                   calendar (CalendarContract) class sources.
#
# Generates source-<id>/ with a build file, a @SourcePlugin-annotated
# FictionSource stub, the mode-appropriate net/<Name>Api.kt (or, for --local, a
# <Name>Reader seam), and a matching test. The generated stubs COMPILE and the
# test FAILS honestly until you wire popular()/etc. to the Api/Reader — that
# red->green loop is your starting point. See docs/CONTRIBUTING-SOURCES.md.
#
# Zero central edits are baked in: @SourcePlugin makes KSP emit both Hilt
# bindings (the registry descriptor AND the Map<String, FictionSource> entry),
# so finishing the module is two one-line edits, printed at the end.
set -euo pipefail

usage() {
    echo "usage: scripts/new-source.sh [--auth|--xml|--feed|--local] <id> \"<Display Name>\"" >&2
    echo "  e.g. scripts/new-source.sh demofeed \"Demo Feed\"           # anonymous JSON HTTP" >&2
    echo "       scripts/new-source.sh --auth gdrive \"Google Drive\"    # token-authed HTTP" >&2
    echo "       scripts/new-source.sh --xml myfeed \"My Feed\"          # XML/Atom HTTP" >&2
    echo "       scripts/new-source.sh --local scans \"Scanned text\"    # non-HTTP local" >&2
}

# ── Mode flag (optional, mutually exclusive) ─────────────────────────────────
MODE="json" # json | auth | xml | local
set_mode() {
    if [[ "$MODE" != "json" ]]; then
        echo "error: --auth, --xml/--feed, and --local are mutually exclusive (an authed XML feed: pick --auth or --xml and swap the Accept header — see docs/CONTRIBUTING-SOURCES.md)" >&2
        exit 2
    fi
    MODE="$1"
}
while [[ $# -gt 0 && "$1" == -* ]]; do
    case "$1" in
        --auth|--oauth) set_mode auth ;;
        --xml|--feed)   set_mode xml ;;
        --local)        set_mode local ;;
        -h|--help)      usage; exit 0 ;;
        --)             shift; break ;;
        *)              echo "error: unknown flag: $1" >&2; usage; exit 2 ;;
    esac
    shift
done

if [[ $# -ne 2 ]]; then
    usage
    exit 2
fi

ID="$1"
DISPLAY="$2"

# Guard the raw inputs before they reach sed replacements and Kotlin string
# literals: ids become module dirs + package segments; display names land in
# generated source. Reject what cannot be made safe, escape the rest below.
if [[ ! "$ID" =~ ^[a-z][a-z0-9-]*$ ]]; then
    echo "error: id must match [a-z][a-z0-9-]* (got '$ID')" >&2
    exit 2
fi
if [[ "$DISPLAY" == *'"'* || "$DISPLAY" == *'\\'* ]]; then
    echo "error: display name must not contain double quotes or backslashes (it becomes a Kotlin string literal)" >&2
    exit 2
fi

# Package/namespace segment: id with any non-alphanumerics stripped, lowercased
# (Kotlin package segments cannot contain '-').
PKG="$(printf '%s' "$ID" | tr -cd '[:alnum:]' | tr '[:upper:]' '[:lower:]')"
# PascalCase type prefix, derived from the display name.
PASCAL="$(printf '%s' "$DISPLAY" | sed 's/[^[:alnum:] ]//g' \
    | awk '{for(i=1;i<=NF;i++){$i=toupper(substr($i,1,1)) tolower(substr($i,2))}; $1=$1; print}' \
    | tr -d ' ')"

if [[ -z "$PKG" || -z "$PASCAL" ]]; then
    echo "error: could not derive a package/type name from id='$ID' display='$DISPLAY'" >&2
    exit 2
fi
if [[ ! "$PKG" =~ ^[a-z] || ! "$PASCAL" =~ ^[A-Za-z] ]]; then
    echo "error: derived names must start with a letter (pkg='$PKG', type='$PASCAL') — Kotlin identifiers cannot start with a digit" >&2
    exit 2
fi

MODULE="source-$ID"
# Resolve repo root from this script's location so it works from any CWD.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODDIR="$REPO_ROOT/$MODULE"
MAIN="$MODDIR/src/main/kotlin/in/jphe/storyvox/source/$PKG"
TEST="$MODDIR/src/test/kotlin/in/jphe/storyvox/source/$PKG"

if [[ -e "$MODDIR" ]]; then
    echo "error: $MODULE already exists" >&2
    exit 1
fi

if [[ "$MODE" == "local" ]]; then
    mkdir -p "$MAIN" "$TEST"
else
    mkdir -p "$MAIN/net" "$MAIN/di" "$TEST"
fi

# ── build.gradle.kts ─────────────────────────────────────────────────────────
if [[ "$MODE" == "local" ]]; then
    # Local providers read the device, not the network: no OkHttp, no
    # kotlinx-serialization, and no HTTP contract kit. Mirrors source-ocr.
    cat > "$MODDIR/build.gradle.kts" <<'GRADLE'
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "in.jphe.storyvox.source.__PKG__"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-data"))

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // @SourcePlugin -> KSP emits the SourcePluginDescriptor @IntoSet binding
    // AND the Map<String, FictionSource> @IntoMap binding (#1371). The
    // <Name>Reader seam is an @Inject class, so Hilt constructs it with no
    // hand-written di/ module.
    ksp(project(":core-plugin-ksp"))

    // Local sources do NOT use the HTTP contract kit — a plain fake-backed
    // unit test (see <Name>SourceTest) verifies the read path instead.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
GRADLE
elif [[ "$MODE" == "xml" ]]; then
    # XML/Atom/RSS feed source: OkHttp for the fetch, but parse with an
    # XmlPullParser or regex — NOT kotlinx-serialization (#1533).
    cat > "$MODDIR/build.gradle.kts" <<'GRADLE'
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "in.jphe.storyvox.source.__PKG__"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-data"))

    implementation(libs.kotlinx.coroutines.android)
    // XML/Atom feed: parse with android.util.Xml / XmlPullParser or a regex
    // pass — no kotlinx-serialization dependency (#1533). See source-arxiv
    // and source-rss for two real XML precedents.
    implementation(libs.okhttp)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // @SourcePlugin -> KSP emits the SourcePluginDescriptor @IntoSet binding
    // AND the Map<String, FictionSource> @IntoMap binding (#1371).
    ksp(project(":core-plugin-ksp"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core-source-testkit"))
}
GRADLE
else
    # json / auth: anonymous or token-authed JSON HTTP source.
    cat > "$MODDIR/build.gradle.kts" <<'GRADLE'
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "in.jphe.storyvox.source.__PKG__"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-data"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // @SourcePlugin -> KSP emits the SourcePluginDescriptor @IntoSet binding
    // AND the Map<String, FictionSource> @IntoMap binding (#1371). The
    // scaffold also generates di/<Name>Module.kt, which provides this source's
    // dedicated OkHttpClient + Api — that one you keep (don't delete it; #1522).
    ksp(project(":core-plugin-ksp"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core-source-testkit"))
}
GRADLE
fi

# ── <Pascal>Source.kt ────────────────────────────────────────────────────────
if [[ "$MODE" == "local" ]]; then
    cat > "$MAIN/${PASCAL}Source.kt" <<'SRC'
package `in`.jphe.storyvox.source.__PKG__

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import javax.inject.Inject
import javax.inject.Singleton

/**
 * __DISPLAY__ source — a LOCAL provider (scaffolded by new-source.sh --local).
 *
 * Reads from the device (files, ContentResolver, a DataStore the :app/:feature
 * layer persists) via [__PASCAL__Reader] — there is NO network and no HTTP
 * contract kit. Wire each stubbed member to the reader and map the result into
 * the core-data models; the plain unit test in src/test fails until popular()
 * surfaces the reader's items. See docs/CONTRIBUTING-SOURCES.md ("Local-provider
 * sources") and :source-ocr for the reference implementation.
 *
 * The @SourcePlugin id below is the SINGLE source of truth for this backend's
 * identity — do NOT add a SourceIds constant (that table is frozen).
 */
@SourcePlugin(
    id = "__ID__",
    displayName = "__DISPLAY__",
    defaultEnabled = false,
    category = SourceCategory.Text,
    supportsSearch = true,
    description = "One-line subtitle — local surface, zero-network (fill me in)",
    sourceUrl = "",
)
@Singleton
internal class __PASCAL__Source @Inject constructor(
    @Suppress("unused") private val reader: __PASCAL__Reader,
) : FictionSource {

    override val id: String = "__ID__"
    override val displayName: String = "__DISPLAY__"

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.NotFound("not implemented")

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.NotFound("not implemented")

    override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.NotFound("not implemented")

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> =
        FictionResult.NotFound("not implemented")

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> =
        FictionResult.NotFound("not implemented")

    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> =
        FictionResult.NotFound("not implemented")

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.AuthRequired("not implemented")

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.AuthRequired("not implemented")

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())
}
SRC
else
    cat > "$MAIN/${PASCAL}Source.kt" <<'SRC'
package `in`.jphe.storyvox.source.__PKG__

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.source.__PKG__.net.__PASCAL__Api
import javax.inject.Inject
import javax.inject.Singleton

/**
 * __DISPLAY__ source (scaffolded by scripts/new-source.sh).
 *
 * Every member is stubbed with an honest [FictionResult.NotFound] — wire each
 * one to [__PASCAL__Api] and map the response into the core-data models. The
 * contract test in src/test fails until popular()/search()/etc. talk to the
 * Api; making it green is your definition of done. See
 * docs/CONTRIBUTING-SOURCES.md.
 *
 * The @SourcePlugin id below is the SINGLE source of truth for this backend's
 * identity — do NOT add a SourceIds constant (that table is frozen).
 */
@SourcePlugin(
    id = "__ID__",
    displayName = "__DISPLAY__",
    defaultEnabled = false,
    category = SourceCategory.Ebook,
    supportsSearch = true,
    description = "One-line subtitle — surface + auth posture (fill me in)",
    sourceUrl = "https://example.com",
)
@Singleton
internal class __PASCAL__Source @Inject constructor(
    @Suppress("unused") private val api: __PASCAL__Api,
) : FictionSource {

    override val id: String = "__ID__"
    override val displayName: String = "__DISPLAY__"

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.NotFound("not implemented")

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.NotFound("not implemented")

    override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.NotFound("not implemented")

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> =
        FictionResult.NotFound("not implemented")

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> =
        FictionResult.NotFound("not implemented")

    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> =
        FictionResult.NotFound("not implemented")

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.AuthRequired("not implemented")

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.AuthRequired("not implemented")

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())
}
SRC
fi

# ── net/<Pascal>Api.kt  (HTTP modes only) ────────────────────────────────────
if [[ "$MODE" == "auth" ]]; then
    cat > "$MAIN/net/${PASCAL}Api.kt" <<'API'
package `in`.jphe.storyvox.source.__PKG__.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

/**
 * HTTP client for __DISPLAY__ (OAuth/BYOK authed). The [request] wrapper is
 * pre-written to satisfy the FictionSourceContractTest: it pins the blocking
 * OkHttp call to Dispatchers.IO (#585), threads the bearer token from
 * [accessToken], and maps status codes to typed failures. Copy its shape for
 * every endpoint you add — do NOT surface raw HTTP codes or exceptions.
 */
internal open class __PASCAL__Api @Inject constructor(
    private val client: OkHttpClient,
) {
    /** Test seam — `open` so unit tests point this at a MockWebServer without
     *  restructuring call sites (mirrors StandardEbooksApi.baseUrl). */
    internal open val baseUrl: String get() = BASE_URL

    /**
     * Current OAuth/BYOK bearer token, or null/blank when the user has not
     * linked an account. Wire this to your token store: inject it into this Api
     * and return the persisted token. The contract test overrides this with a
     * non-blank fake so popular() actually reaches the wire (#1529) — a source
     * that short-circuits to AuthRequired on a blank token records zero network
     * calls, which fails the IO-pin probe with a confusing "no request reached
     * the probe" message.
     */
    internal open suspend fun accessToken(): String? = null

    /**
     * IO-pinned authed GET. `parse` turns the response body into your typed
     * model. Returns [FictionResult.AuthRequired] when there is no token, and a
     * typed failure for every non-2xx status — never throws for an HTTP error.
     */
    suspend fun <T> request(path: String, parse: (String) -> T): FictionResult<T> =
        withContext(Dispatchers.IO) {
            val url = baseUrl + path
            val token = accessToken()
            if (token.isNullOrBlank()) {
                return@withContext FictionResult.AuthRequired("__DISPLAY__: link your account in Settings")
            }
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 404 -> FictionResult.NotFound("__DISPLAY__: $path not found")
                        resp.code == 401 -> FictionResult.AuthRequired("HTTP 401 from $url")
                        resp.code == 403 -> {
                            // Cloudflare interstitials arrive as HTTP 403 with challenge
                            // HTML — the CF sniff MUST precede the auth mapping, or a
                            // CF-gated 403 misreports as "sign in required" (see
                            // docs/CONTRIBUTING-SOURCES.md decision table).
                            val body = resp.body?.string().orEmpty()
                            if (looksLikeCfChallenge(body)) {
                                FictionResult.NetworkError(
                                    "__DISPLAY__ returned a Cloudflare challenge page — try again later",
                                    IOException("Cloudflare challenge"),
                                )
                            } else {
                                FictionResult.AuthRequired("HTTP 403 from $url")
                            }
                        }
                        resp.code == 429 -> FictionResult.RateLimited(
                            retryAfter = null,
                            message = "__DISPLAY__ rate limited (HTTP 429)",
                        )
                        !resp.isSuccessful -> FictionResult.NetworkError(
                            "HTTP ${resp.code} from $url",
                            IOException("HTTP ${resp.code}"),
                        )
                        else -> {
                            val text = resp.body?.string()
                                ?: return@withContext FictionResult.NetworkError(
                                    "empty body",
                                    IOException("empty body"),
                                )
                            FictionResult.Success(parse(text))
                        }
                    }
                }
            } catch (e: IOException) {
                FictionResult.NetworkError(e.message ?: "fetch failed", e)
            } catch (e: kotlinx.serialization.SerializationException) {
                // A throwing parse lambda must stay inside the typed-error
                // contract — never escape as a raw exception.
                FictionResult.NetworkError("__DISPLAY__ returned an unexpected response shape", e)
            }
        }

    /**
     * #1443-family heuristic: does a body look like a Cloudflare challenge
     * interstitial rather than your API's payload? Keep this arm ahead of
     * the 401/403 auth mapping (see the request() template above).
     */
    private fun looksLikeCfChallenge(body: String): Boolean =
        body.contains("/cdn-cgi/challenge-platform/") ||
            body.contains("Just a moment...") ||
            body.contains("cf-mitigated")

    companion object {
        const val BASE_URL = "https://example.com"
    }
}
API
elif [[ "$MODE" == "xml" ]]; then
    cat > "$MAIN/net/${PASCAL}Api.kt" <<'API'
package `in`.jphe.storyvox.source.__PKG__.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

/**
 * HTTP client for __DISPLAY__ (XML/Atom/RSS feed). The [request] wrapper is
 * pre-written to satisfy the FictionSourceContractTest: it pins the blocking
 * OkHttp call to Dispatchers.IO (#585), sends an XML Accept header, and maps
 * status codes to typed failures. `parse` is where you run an XmlPullParser or
 * a regex pass (there is no kotlinx-serialization here, #1533) — see
 * source-arxiv and source-rss for two real XML precedents. Copy this shape for
 * every endpoint you add — do NOT surface raw HTTP codes or exceptions.
 */
internal open class __PASCAL__Api @Inject constructor(
    private val client: OkHttpClient,
) {
    /** Test seam — `open` so unit tests point this at a MockWebServer without
     *  restructuring call sites (mirrors StandardEbooksApi.baseUrl). */
    internal open val baseUrl: String get() = BASE_URL

    /**
     * IO-pinned GET for an XML/Atom body. `parse` turns the response text into
     * your typed model (XmlPullParser / regex). Returns a typed
     * [FictionResult] failure for every non-2xx status — never throws for an
     * HTTP error. A feed source usually adds conditional-GET headers
     * (If-None-Match / If-Modified-Since) here — see source-primegaming.
     */
    suspend fun <T> request(path: String, parse: (String) -> T): FictionResult<T> =
        withContext(Dispatchers.IO) {
            val url = baseUrl + path
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/atom+xml, application/xml;q=0.9, */*;q=0.8")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 404 -> FictionResult.NotFound("__DISPLAY__: $path not found")
                        resp.code == 401 -> FictionResult.AuthRequired("HTTP 401 from $url")
                        resp.code == 403 -> {
                            // Cloudflare interstitials arrive as HTTP 403 with challenge
                            // HTML — the CF sniff MUST precede the auth mapping, or a
                            // CF-gated 403 misreports as "sign in required" (see
                            // docs/CONTRIBUTING-SOURCES.md decision table).
                            val body = resp.body?.string().orEmpty()
                            if (looksLikeCfChallenge(body)) {
                                FictionResult.NetworkError(
                                    "__DISPLAY__ returned a Cloudflare challenge page — try again later",
                                    IOException("Cloudflare challenge"),
                                )
                            } else {
                                FictionResult.AuthRequired("HTTP 403 from $url")
                            }
                        }
                        resp.code == 429 -> FictionResult.RateLimited(
                            retryAfter = null,
                            message = "__DISPLAY__ rate limited (HTTP 429)",
                        )
                        !resp.isSuccessful -> FictionResult.NetworkError(
                            "HTTP ${resp.code} from $url",
                            IOException("HTTP ${resp.code}"),
                        )
                        else -> {
                            val text = resp.body?.string()
                                ?: return@withContext FictionResult.NetworkError(
                                    "empty body",
                                    IOException("empty body"),
                                )
                            // XML/regex parsing does not throw SerializationException,
                            // so there is no JSON parse-error catch here (#1533). If
                            // your parser can throw (XmlPullParserException is an
                            // IOException, caught below), keep failures inside the
                            // typed contract — never let one escape as a raw throw.
                            FictionResult.Success(parse(text))
                        }
                    }
                }
            } catch (e: IOException) {
                FictionResult.NetworkError(e.message ?: "fetch failed", e)
            }
        }

    /**
     * #1443-family heuristic: does a body look like a Cloudflare challenge
     * interstitial rather than your feed payload? Keep this arm ahead of
     * the 401/403 auth mapping (see the request() template above).
     */
    private fun looksLikeCfChallenge(body: String): Boolean =
        body.contains("/cdn-cgi/challenge-platform/") ||
            body.contains("Just a moment...") ||
            body.contains("cf-mitigated")

    companion object {
        const val BASE_URL = "https://example.com"
    }
}
API
elif [[ "$MODE" == "json" ]]; then
    cat > "$MAIN/net/${PASCAL}Api.kt" <<'API'
package `in`.jphe.storyvox.source.__PKG__.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

/**
 * HTTP client for __DISPLAY__. The [request] wrapper is pre-written to satisfy
 * the FictionSourceContractTest: it pins the blocking OkHttp call to
 * Dispatchers.IO (#585) and maps status codes to typed failures. Copy its shape
 * for every endpoint you add — do NOT surface raw HTTP codes or exceptions.
 */
internal open class __PASCAL__Api @Inject constructor(
    private val client: OkHttpClient,
) {
    /** Test seam — `open` so unit tests point this at a MockWebServer without
     *  restructuring call sites (mirrors StandardEbooksApi.baseUrl). */
    internal open val baseUrl: String get() = BASE_URL

    /**
     * IO-pinned GET. `parse` turns the response body into your typed model.
     * Returns a typed [FictionResult] failure for every non-2xx status — never
     * throws for an HTTP error.
     */
    suspend fun <T> request(path: String, parse: (String) -> T): FictionResult<T> =
        withContext(Dispatchers.IO) {
            val url = baseUrl + path
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 404 -> FictionResult.NotFound("__DISPLAY__: $path not found")
                        resp.code == 401 -> FictionResult.AuthRequired("HTTP 401 from $url")
                        resp.code == 403 -> {
                            // Cloudflare interstitials arrive as HTTP 403 with challenge
                            // HTML — the CF sniff MUST precede the auth mapping, or a
                            // CF-gated 403 misreports as "sign in required" (see
                            // docs/CONTRIBUTING-SOURCES.md decision table).
                            val body = resp.body?.string().orEmpty()
                            if (looksLikeCfChallenge(body)) {
                                FictionResult.NetworkError(
                                    "__DISPLAY__ returned a Cloudflare challenge page — try again later",
                                    IOException("Cloudflare challenge"),
                                )
                            } else {
                                FictionResult.AuthRequired("HTTP 403 from $url")
                            }
                        }
                        resp.code == 429 -> FictionResult.RateLimited(
                            retryAfter = null,
                            message = "__DISPLAY__ rate limited (HTTP 429)",
                        )
                        !resp.isSuccessful -> FictionResult.NetworkError(
                            "HTTP ${resp.code} from $url",
                            IOException("HTTP ${resp.code}"),
                        )
                        else -> {
                            val text = resp.body?.string()
                                ?: return@withContext FictionResult.NetworkError(
                                    "empty body",
                                    IOException("empty body"),
                                )
                            FictionResult.Success(parse(text))
                        }
                    }
                }
            } catch (e: IOException) {
                FictionResult.NetworkError(e.message ?: "fetch failed", e)
            } catch (e: kotlinx.serialization.SerializationException) {
                // A throwing parse lambda must stay inside the typed-error
                // contract — never escape as a raw exception.
                FictionResult.NetworkError("__DISPLAY__ returned an unexpected response shape", e)
            }
        }

    /**
     * #1443-family heuristic: does a body look like a Cloudflare challenge
     * interstitial rather than your API's payload? Keep this arm ahead of
     * the 401/403 auth mapping (see the request() template above).
     */
    private fun looksLikeCfChallenge(body: String): Boolean =
        body.contains("/cdn-cgi/challenge-platform/") ||
            body.contains("Just a moment...") ||
            body.contains("cf-mitigated")

    companion object {
        const val BASE_URL = "https://example.com"
    }
}
API
fi

# ── <Pascal>Reader.kt  (--local only) ────────────────────────────────────────
if [[ "$MODE" == "local" ]]; then
    cat > "$MAIN/${PASCAL}Reader.kt" <<'READER'
package `in`.jphe.storyvox.source.__PKG__

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local read seam for __DISPLAY__ (scaffolded by new-source.sh --local).
 *
 * A local-provider source reads from the device — files via SAF, a
 * ContentResolver query (e.g. CalendarContract), or a DataStore the
 * :app/:feature capture flow persists — NOT the network. Keep every blocking
 * read pinned to Dispatchers.IO here so the source layer stays main-safe: the
 * HTTP contract kit's #585 IO-pin check does NOT run for local sources, so
 * this is your responsibility.
 *
 * This is a concrete `open class` with an `@Inject` constructor on purpose:
 * Hilt constructs it with zero DI wiring (no hand-written di/ module), and unit
 * tests subclass it with a fake — see __PASCAL__SourceTest. Mirrors
 * :source-ocr's `OcrConfig` seam.
 */
@Singleton
internal open class __PASCAL__Reader @Inject constructor() {

    /** Every item this local source exposes, newest first. Stub — replace with
     *  a real device read (SAF / ContentResolver / DataStore), IO-pinned. */
    internal open suspend fun items(): List<__PASCAL__Item> =
        withContext(Dispatchers.IO) { emptyList() }

    /** One item's full body by id, or null if it is gone. Stub. */
    internal open suspend fun item(id: String): __PASCAL__Item? =
        withContext(Dispatchers.IO) { null }
}

/** Minimal local record — rename/extend to match your device data. */
internal data class __PASCAL__Item(
    val id: String,
    val title: String,
    val body: String,
)
READER
fi

# ── di/<Pascal>Module.kt  (JSON / auth / XML HTTP modes only) ─────────────────
# @SourcePlugin makes KSP emit the FictionSource multibindings, but the source's
# OkHttpClient + Api still need a provider (there is no global unqualified
# OkHttpClient — each source owns a qualified client with the shared UA
# interceptor). Mirrors source-hackernews/di/HackerNewsHttpModule.kt. --local
# sources skip this: the <Name>Reader is an @Inject class Hilt builds directly.
if [[ "$MODE" != "local" ]]; then
    cat > "$MAIN/di/${PASCAL}Module.kt" <<'DIMOD'
package `in`.jphe.storyvox.source.__PKG__.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.network.UserAgentHeader
import `in`.jphe.storyvox.source.__PKG__.net.__PASCAL__Api
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Dedicated OkHttp client qualifier for the __DISPLAY__ backend. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class __PASCAL__Http

@Module
@InstallIn(SingletonComponent::class)
internal object __PASCAL__HttpModule {

    @Provides
    @Singleton
    @__PASCAL__Http
    fun provideClient(
        @UserAgentHeader userAgent: Interceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            // #1204 — shared descriptive User-Agent on every request.
            .addInterceptor(userAgent)
            .build()

    @Provides
    @Singleton
    fun provide__PASCAL__Api(
        @__PASCAL__Http client: OkHttpClient,
    ): __PASCAL__Api = __PASCAL__Api(client)
}
DIMOD
fi

# ── src/test/<Pascal>(Contract)Test.kt ───────────────────────────────────────
if [[ "$MODE" == "local" ]]; then
    cat > "$TEST/${PASCAL}SourceTest.kt" <<'CTEST'
package `in`.jphe.storyvox.source.__PKG__

import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * __DISPLAY__ read-path test over a fake [__PASCAL__Reader] — the :source-ocr
 * OcrSourceTest pattern. No Robolectric, no device: the fake stands in for the
 * real device read so the source's mapping (item -> fiction, page -> chapter)
 * is verified in pure JVM.
 *
 * This FAILS until you map [__PASCAL__Reader.items] into a
 * ListPage<FictionSummary> in [__PASCAL__Source.popular] — that mapping is your
 * to-do list. See docs/CONTRIBUTING-SOURCES.md ("Local-provider sources").
 */
class __PASCAL__SourceTest {

    private class FakeReader(private val seed: List<__PASCAL__Item>) : __PASCAL__Reader() {
        override suspend fun items(): List<__PASCAL__Item> = seed
        override suspend fun item(id: String): __PASCAL__Item? = seed.firstOrNull { it.id == id }
    }

    @Test
    fun `popular surfaces every local item as a fiction`() = runTest {
        val source = __PASCAL__Source(
            FakeReader(
                listOf(
                    __PASCAL__Item(id = "1", title = "First", body = "Body one"),
                    __PASCAL__Item(id = "2", title = "Second", body = "Body two"),
                ),
            ),
        )

        val result = source.popular(1)

        assertTrue(
            "wire popular() to reader.items() and map to ListPage<FictionSummary>",
            result is FictionResult.Success<*>,
        )
    }
}
CTEST
elif [[ "$MODE" == "auth" ]]; then
    cat > "$TEST/${PASCAL}ContractTest.kt" <<'CTEST'
package `in`.jphe.storyvox.source.__PKG__

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.__PKG__.net.__PASCAL__Api
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

/**
 * __DISPLAY__ (authed) against the shared contract kit. This FAILS until you
 * wire [__PASCAL__Source.popular] (and friends) through [__PASCAL__Api.request]:
 * the stub never hits the network, so the "network work leaves the caller
 * thread" and auth/rate-limit checks fail honestly. Replace [happyListBody] /
 * [listPathFragment] with your real list endpoint, make the source talk to the
 * Api, and turn this green. See docs/CONTRIBUTING-SOURCES.md.
 */
class __PASCAL__ContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return __PASCAL__Source(
            object : __PASCAL__Api(client) {
                override val baseUrl: String get() = host
                // #1529 — a non-blank fake token so request() adds the Bearer
                // header and actually reaches the wire. Without it every call
                // short-circuits to AuthRequired and the IO-pin probe records
                // zero traffic ("no request reached the probe").
                override suspend fun accessToken(): String = "contract-fake-token"
            },
        )
    }

    /** Replace with a trimmed real response body from your list endpoint. */
    override fun happyListBody(): String = "{}"

    /** Replace with a path fragment your popular()/list endpoint hits. */
    override fun listPathFragment(): String = "__ID__"
}
CTEST
elif [[ "$MODE" == "xml" ]]; then
    cat > "$TEST/${PASCAL}ContractTest.kt" <<'CTEST'
package `in`.jphe.storyvox.source.__PKG__

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.__PKG__.net.__PASCAL__Api
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

/**
 * __DISPLAY__ (XML/Atom feed) against the shared contract kit. This FAILS until
 * you wire [__PASCAL__Source.popular] (and friends) through
 * [__PASCAL__Api.request]: the stub never hits the network, so the "network
 * work leaves the caller thread" and auth/rate-limit checks fail honestly.
 * Replace [happyListBody] with a trimmed real feed body and [listPathFragment]
 * with your real list path, make the source parse the feed, and turn this
 * green. See docs/CONTRIBUTING-SOURCES.md and source-arxiv / source-rss.
 */
class __PASCAL__ContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return __PASCAL__Source(
            object : __PASCAL__Api(client) {
                override val baseUrl: String get() = host
            },
        )
    }

    /** Replace with a trimmed real Atom/RSS body from your feed (2–3 entries). */
    override fun happyListBody(): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<feed xmlns=\"http://www.w3.org/2005/Atom\">" +
            "<entry><id>1</id><title>Example entry</title></entry>" +
            "</feed>"

    /** Replace with a path fragment your popular()/list endpoint hits. */
    override fun listPathFragment(): String = "__ID__"
}
CTEST
elif [[ "$MODE" == "json" ]]; then
    cat > "$TEST/${PASCAL}ContractTest.kt" <<'CTEST'
package `in`.jphe.storyvox.source.__PKG__

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.__PKG__.net.__PASCAL__Api
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

/**
 * __DISPLAY__ against the shared contract kit. This FAILS until you wire
 * [__PASCAL__Source.popular] (and friends) through [__PASCAL__Api.request]:
 * the stub never hits the network, so the "network work leaves the caller
 * thread" and auth/rate-limit checks fail honestly. Replace [happyListBody] /
 * [listPathFragment] with your real list endpoint, make the source talk to the
 * Api, and turn this green. See docs/CONTRIBUTING-SOURCES.md.
 */
class __PASCAL__ContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return __PASCAL__Source(
            object : __PASCAL__Api(client) {
                override val baseUrl: String get() = host
            },
        )
    }

    /** Replace with a trimmed real response body from your list endpoint. */
    override fun happyListBody(): String = "{}"

    /** Replace with a path fragment your popular()/list endpoint hits. */
    override fun listPathFragment(): String = "__ID__"
}
CTEST
fi

# ── AndroidManifest.xml  (--local only, for a declared permission) ────────────
if [[ "$MODE" == "local" ]]; then
    cat > "$MODDIR/src/main/AndroidManifest.xml" <<'MANIFEST'
<?xml version="1.0" encoding="utf-8"?>
<!--
  Local-provider manifest placeholder. If your source reads a permission-gated
  provider (e.g. CalendarContract needs READ_CALENDAR), declare it here and
  request it at runtime from the :feature/:app layer. Delete this file if your
  source needs no permission (an empty manifest is synthesized from `namespace`).
-->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- <uses-permission android:name="android.permission.READ_CALENDAR" /> -->
</manifest>
MANIFEST
fi

# Substitute placeholders (| delimiter so display names with '/' are safe).
# The display name is user text: escape sed replacement metacharacters
# (& = whole match, \ = escape, | = our delimiter) or "Q&A Daily" silently
# corrupts every generated file. PKG/PASCAL/ID are regex-constrained above.
ESC_DISPLAY="$(printf '%s' "$DISPLAY" | sed -e 's/[&\\|]/\\&/g')"
find "$MODDIR" -type f -print0 | while IFS= read -r -d '' f; do
    sed -i \
        -e "s|__PASCAL__|$PASCAL|g" \
        -e "s|__PKG__|$PKG|g" \
        -e "s|__ID__|$ID|g" \
        -e "s|__DISPLAY__|$ESC_DISPLAY|g" \
        "$f"
done

# ── Done ─────────────────────────────────────────────────────────────────────
case "$MODE" in
    local) MODE_LABEL="local provider (non-HTTP; <Name>Reader seam)" ;;
    auth)  MODE_LABEL="authed HTTP (OAuth/BYOK; Bearer-token request wrapper)" ;;
    xml)   MODE_LABEL="XML/Atom feed HTTP (XML Accept, no serialization)" ;;
    *)     MODE_LABEL="anonymous JSON HTTP" ;;
esac

cat <<DONE
Scaffolded $MODULE ($PASCAL) — mode: $MODE_LABEL
  at $MODDIR

Done. Two edits remain:
  1. settings.gradle.kts       → include(":source-$ID")
  2. app/build.gradle.kts      → implementation(project(":source-$ID"))
Then: ./gradlew :source-$ID:testDebugUnitTest
Guide: docs/CONTRIBUTING-SOURCES.md
DONE
