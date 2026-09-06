package `in`.jphe.storyvox.source.endless

import `in`.jphe.storyvox.source.endless.config.EndlessConfig
import `in`.jphe.storyvox.source.endless.config.EndlessConfigState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Response bodies **captured from the live daemon** (katana:8093,
 * 2026-07-29), trimmed in length but never reshaped.
 *
 * Using real payloads rather than hand-written ones is deliberate: the
 * daemon's actual shape has already contradicted its description twice —
 * `mp3_url`/`pcm_url`/`total_bytes` go **null** together when a chapter's
 * audio is absent (a fixture written from the docs would have had them
 * always present and would have missed the null path entirely), and the
 * render manifest's segment granularity varies by which engine version
 * last rendered the chapter. Both facts are encoded in these fixtures so
 * the tests exercise the daemon that exists.
 */
internal object EndlessFixtures {

    /** `GET /api/story`. */
    const val STORY: String = """
        {"title":"The Ashen Ledger",
         "description":"An endlessly generated LitRPG serial.",
         "protagonist":"Kaelen Vord","language":"en-us",
         "chapter_count":5,"latest_chapter":5,"dirty_chapters":[],
         "sample_rate":16000,"bytes_per_ms":32,"target_words":2000,
         "prompt_hash":"fnv1a64:e8632d8729326d8d","initialised":true}
    """

    /**
     * `GET /api/chapters?since=0` — two real entries, deliberately one of
     * each kind: chapter 5 has rendered audio, chapter 1 does not (its
     * audio was invalidated for a re-render, which nulls all three media
     * fields). Returned out of order, so the ordering assertion is real.
     */
    const val CHAPTER_INDEX: String = """
        [{"number":5,"title":"The Mill at Blackwater","duration_ms":767800,
          "has_audio":true,"words":1886,"state_dirty":false,
          "pcm_url":"http://192.0.2.129:8093/media/0005.pcm",
          "mp3_url":"http://192.0.2.129:8093/media/0005.mp3",
          "total_bytes":24569600},
         {"number":1,"title":"Collecting the Divine Shard","duration_ms":452729,
          "has_audio":false,"words":1479,"state_dirty":false,
          "pcm_url":null,"mp3_url":null,"total_bytes":null}]
    """

    /**
     * The **real** chapter index immediately before the issue-#15
     * re-render, captured live. Five chapters, all with audio.
     *
     * Paired with [INDEX_AFTER_RERENDER] this is the actual regression that
     * rewrote [`in`.jphe.storyvox.source.endless.EndlessLitrpgSource.latestRevisionToken]
     * — not a synthetic mutation. Chapters 3 and 4 were re-rendered with
     * the correct narrator voice; 1, 2 and 5 are byte-identical across the
     * pair. The chapter *count* and the story prompt are unchanged, which
     * is precisely why the original `latest_chapter:prompt_hash` token
     * could not see it.
     */
    const val INDEX_BEFORE_RERENDER: String = """
        [{"number":1,"title":"Collecting the Divine Shard","duration_ms":454738,
          "has_audio":true,"words":1479,"state_dirty":false,
          "pcm_url":"http://192.0.2.129:8093/media/0001.pcm",
          "mp3_url":"http://192.0.2.129:8093/media/0001.mp3","total_bytes":14551616},
         {"number":2,"title":"Collecting the Shadow Debt","duration_ms":595169,
          "has_audio":true,"words":2008,"state_dirty":false,
          "pcm_url":"http://192.0.2.129:8093/media/0002.pcm",
          "mp3_url":"http://192.0.2.129:8093/media/0002.mp3","total_bytes":19045408},
         {"number":3,"title":"Collecting Oren's Debt","duration_ms":1064350,
          "has_audio":true,"words":2588,"state_dirty":false,
          "pcm_url":"http://192.0.2.129:8093/media/0003.pcm",
          "mp3_url":"http://192.0.2.129:8093/media/0003.mp3","total_bytes":34059200},
         {"number":4,"title":"Settling the Oakhaven Tithe","duration_ms":854500,
          "has_audio":true,"words":2018,"state_dirty":false,
          "pcm_url":"http://192.0.2.129:8093/media/0004.pcm",
          "mp3_url":"http://192.0.2.129:8093/media/0004.mp3","total_bytes":27344000},
         {"number":5,"title":"The Mill at Blackwater","duration_ms":583723,
          "has_audio":true,"words":1886,"state_dirty":false,
          "pcm_url":"http://192.0.2.129:8093/media/0005.pcm",
          "mp3_url":"http://192.0.2.129:8093/media/0005.mp3","total_bytes":18679136}]
    """

    /**
     * The same index after the #15 re-render — chapters 3 and 4 only.
     * `duration_ms × 32 == total_bytes` verified exact on every entry.
     */
    const val INDEX_AFTER_RERENDER: String = """
        [{"number":1,"title":"Collecting the Divine Shard","duration_ms":454738,
          "has_audio":true,"words":1479,"state_dirty":false,
          "pcm_url":"http://192.0.2.129:8093/media/0001.pcm",
          "mp3_url":"http://192.0.2.129:8093/media/0001.mp3","total_bytes":14551616},
         {"number":2,"title":"Collecting the Shadow Debt","duration_ms":595169,
          "has_audio":true,"words":2008,"state_dirty":false,
          "pcm_url":"http://192.0.2.129:8093/media/0002.pcm",
          "mp3_url":"http://192.0.2.129:8093/media/0002.mp3","total_bytes":19045408},
         {"number":3,"title":"Collecting Oren's Debt","duration_ms":804975,
          "has_audio":true,"words":2588,"state_dirty":false,
          "pcm_url":"http://192.0.2.129:8093/media/0003.pcm",
          "mp3_url":"http://192.0.2.129:8093/media/0003.mp3","total_bytes":25759200},
         {"number":4,"title":"Settling the Oakhaven Tithe","duration_ms":637151,
          "has_audio":true,"words":2018,"state_dirty":false,
          "pcm_url":"http://192.0.2.129:8093/media/0004.pcm",
          "mp3_url":"http://192.0.2.129:8093/media/0004.mp3","total_bytes":20388832},
         {"number":5,"title":"The Mill at Blackwater","duration_ms":583723,
          "has_audio":true,"words":1886,"state_dirty":false,
          "pcm_url":"http://192.0.2.129:8093/media/0005.pcm",
          "mp3_url":"http://192.0.2.129:8093/media/0005.mp3","total_bytes":18679136}]
    """

    /**
     * A chapter WITH rendered audio and a per-sentence manifest (the
     * current engine's shape: many short segments).
     */
    const val CHAPTER_WITH_AUDIO: String = """
        {"number":5,"title":"The Mill at Blackwater",
         "text_md":"# Chapter 5: The Mill at Blackwater\n\n[narrator] The rain in Oakhaven did not wash things clean.\n\n[SYSTEM] Quest updated: The Blackwater Mill\n\n[Sera] \"We should go.\"",
         "prompt_hash":"fnv1a64:e8632d8729326d8d","duration_ms":767800,
         "has_audio":true,"state_dirty":false,
         "pcm_url":"http://192.0.2.129:8093/media/0005.pcm",
         "mp3_url":"http://192.0.2.129:8093/media/0005.mp3",
         "manifest":{"chapter":5,"sample_rate":16000,"bytes_per_ms":32,
           "duration_ms":767800,
           "segments":[
             {"idx":0,"speaker":"narrator","kind":"narrator",
              "voice_ref":"sherpa:piper-en_GB-cori-high:0",
              "text":"The rain in Oakhaven did not wash things clean.",
              "start_ms":0,"end_ms":3200},
             {"idx":1,"speaker":"SYSTEM","kind":"system",
              "voice_ref":"sherpa:kokoro-multi-lang-v1_0:24",
              "text":"Quest updated: The Blackwater Mill",
              "start_ms":3200,"end_ms":9600}]},
         "manifest_contiguous":true}
    """

    /**
     * A chapter WITHOUT rendered audio, still serving the superseded
     * manifest from its previous render.
     *
     * This is not a legacy shape — it is the **transient state of every
     * re-render**, and on an endless serial re-renders are routine (a cast
     * change, a voice-resolution fix). While a chapter is queued for
     * re-render the daemon nulls its media URLs but keeps serving the old
     * manifest, so manifest presence must never be read as evidence that
     * audio exists; only `has_audio` plus a non-null `mp3_url` are.
     *
     * Its segments are deliberately the coarse per-speaker-turn shape (one
     * spanning 112 seconds) rather than the current per-sentence shape:
     * the mapping must not care how finely a manifest happens to be split,
     * and pinning that here means a future engine change to segment
     * granularity cannot quietly break this client.
     */
    const val CHAPTER_WITHOUT_AUDIO: String = """
        {"number":1,"title":"Collecting the Divine Shard",
         "text_md":"# Chapter 1: Collecting the Divine Shard\n\n[narrator] The ash did not fall; it hovered, a suspended gray fog.\n\n[SYSTEM] Debt Entry: #8940-B Status: Unpaid.",
         "prompt_hash":"fnv1a64:a9f049ff31c62a4e","duration_ms":452729,
         "has_audio":false,"state_dirty":false,
         "pcm_url":null,"mp3_url":null,
         "manifest":{"chapter":1,"sample_rate":16000,"bytes_per_ms":32,
           "duration_ms":452729,
           "segments":[
             {"idx":0,"speaker":"narrator","kind":"narrator",
              "voice_ref":"sherpa:piper-en_GB-cori-high:0",
              "text":"The ash did not fall; it hovered, a suspended gray fog.",
              "start_ms":0,"end_ms":112120}]},
         "manifest_contiguous":true}
    """
}

/** [EndlessConfig] returning a fixed host — no DataStore in unit tests. */
internal class FakeEndlessConfig(private val host: String) : EndlessConfig {
    override val state: Flow<EndlessConfigState> = flowOf(EndlessConfigState(host))
    override suspend fun current(): EndlessConfigState = EndlessConfigState(host)
}
