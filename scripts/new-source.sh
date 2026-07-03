#!/usr/bin/env bash
#
# new-source.sh — scaffold a new Candela FictionSource plugin module.
#
#   scripts/new-source.sh <id> "<Display Name>"
#   e.g. scripts/new-source.sh demofeed "Demo Feed"
#
# Generates source-<id>/ with a build file, a @SourcePlugin-annotated
# FictionSource stub, an IO-pinned net/<Name>Api.kt request wrapper, and a
# contract-test subclass wired to :core-source-testkit. The generated stubs
# COMPILE and the contract test FAILS honestly until you wire popular()/etc. to
# the Api — that red→green loop is your starting point. See
# docs/CONTRIBUTING-SOURCES.md.
#
# Zero central edits are baked in: @SourcePlugin makes KSP emit both Hilt
# bindings (the registry descriptor AND the Map<String, FictionSource> entry),
# so finishing the module is two one-line edits, printed at the end.
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: scripts/new-source.sh <id> \"<Display Name>\"" >&2
    echo "  e.g. scripts/new-source.sh demofeed \"Demo Feed\"" >&2
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

mkdir -p "$MAIN/net" "$MAIN/di" "$TEST"

# ── build.gradle.kts ─────────────────────────────────────────────────────────
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
    // AND the Map<String, FictionSource> @IntoMap binding (#1371). No hand
    // di/ module needed.
    ksp(project(":core-plugin-ksp"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core-source-testkit"))
}
GRADLE

# ── <Pascal>Source.kt ────────────────────────────────────────────────────────
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

# ── net/<Pascal>Api.kt ───────────────────────────────────────────────────────
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

# ── di/<Pascal>Module.kt ─────────────────────────────────────────────────────
# @SourcePlugin makes KSP emit the FictionSource multibindings, but the source's
# OkHttpClient + Api still need a provider (there is no global unqualified
# OkHttpClient — each source owns a qualified client with the shared UA
# interceptor). Mirrors source-hackernews/di/HackerNewsHttpModule.kt.
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

# ── src/test/<Pascal>ContractTest.kt ─────────────────────────────────────────
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

cat <<DONE
Scaffolded $MODULE ($PASCAL) at $MODDIR

Done. Two edits remain:
  1. settings.gradle.kts       → include(":source-$ID")
  2. app/build.gradle.kts      → implementation(project(":source-$ID"))
Then: ./gradlew :source-$ID:testDebugUnitTest
Guide: docs/CONTRIBUTING-SOURCES.md
DONE
