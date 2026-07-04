package `in`.jphe.storyvox.source.epicfreegames

import `in`.jphe.storyvox.source.epicfreegames.net.EpicFreeGamesApi
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure parse + mapping coverage for the Epic Free Games feed. Exercises the
 * [giveaways] projection and the narration helpers directly off a captured
 * fixture — no network, no MockWebServer (the contract kit owns the HTTP
 * behaviours).
 */
class EpicFreeGamesMappingTest {

    private val api = EpicFreeGamesApi(OkHttpClient())
    private fun parse() = giveaways(api.parsePromotions(EPIC_FIXTURE_BODY))

    @Test
    fun `only actual giveaways survive — plain sales are dropped`() {
        val list = parse()
        // Fixture has 4 elements: current-free, upcoming-free, a 20%-off sale,
        // and a null-promotions entry. Only the two giveaways survive.
        assertEquals(2, list.size)
        assertTrue(list.none { it.title.contains("Sale Only") })
        assertTrue(list.none { it.title.contains("No Promo") })
    }

    @Test
    fun `current giveaways sort ahead of upcoming`() {
        val list = parse()
        assertTrue("first must be current", list.first().current)
        assertFalse("last must be upcoming", list.last().current)
        assertEquals("River City Girls 2", list.first().title)
        assertEquals("Nova Lands", list.last().title)
    }

    @Test
    fun `upcoming free week is picked out of a mix of percentage sales`() {
        // Nova Lands has 50%/0%/50% upcoming offers; the 0% (free) window must
        // be the one selected, not the first-listed 50% one.
        val nova = parse().first { it.title == "Nova Lands" }
        assertFalse(nova.current)
        assertEquals("2026-07-09T15:00:00.000Z", nova.startDate)
        assertEquals("2026-07-16T15:00:00.000Z", nova.endDate)
    }

    @Test
    fun `store url prefers a slug and is absolute`() {
        val rcg = parse().first { it.title == "River City Girls 2" }
        assertNotNull(rcg.storeUrl)
        assertTrue(rcg.storeUrl!!.startsWith("https://store.epicgames.com/p/"))
    }

    @Test
    fun `element key is stable and falls back off id`() {
        val rcg = parse().first { it.title == "River City Girls 2" }
        assertEquals("9bf3d3188d1e498095bc23c0955538d9", rcg.key)
    }

    @Test
    fun `date formatting is UTC and deterministic`() {
        assertEquals("Jul 2, 2026", formatEpicDate("2026-07-02T15:00:00.000Z"))
        assertNull(formatEpicDate(null))
        assertNull(formatEpicDate(""))
        // Unparseable input degrades to the raw string rather than throwing.
        assertEquals("garbage", formatEpicDate("garbage"))
    }

    @Test
    fun `narration reads title, window, price and store link`() {
        val rcg = parse().first { it.title == "River City Girls 2" }
        val body = narrateGiveaway(rcg)
        assertTrue(body.startsWith("River City Girls 2"))
        assertTrue(body.contains("Free to claim now"))
        assertTrue(body.contains("from Jul 2, 2026 to Jul 9, 2026"))
        assertTrue(body.contains("Normally"))
        assertTrue(body.contains("Published by WayForward"))
        assertTrue(body.contains("store.epicgames.com/p/"))
    }

    @Test
    fun `upcoming chapter title is flagged coming soon`() {
        val nova = parse().first { it.title == "Nova Lands" }
        val body = narrateGiveaway(nova)
        assertTrue(body.contains("Free to claim soon"))
    }

    @Test
    fun `empty document parses to no giveaways, never throws`() {
        val empty = giveaways(api.parsePromotions("""{"data":{"Catalog":{"searchStore":{"elements":[]}}}}"""))
        assertTrue(empty.isEmpty())
    }

    @Test
    fun `unknown and missing fields are tolerated`() {
        // A totally foreign shape must degrade to empty, not throw — the feed
        // is unofficial and can reshape without notice.
        val weird = giveaways(api.parsePromotions("""{"data":{},"unexpected":true}"""))
        assertTrue(weird.isEmpty())
    }
}

/**
 * A trimmed real `freeGamesPromotions` body:
 *  - River City Girls 2 — currently free (`promotionalOffers`, 0% pay)
 *  - Nova Lands — upcoming free (a 0% week bundled among 50% sales)
 *  - Sale Only Game — a plain 20%-off sale (has promotions, but no 0% offer)
 *  - No Promo Game — `promotions: null`
 * The `$` in the localized price strings is escaped for the Kotlin raw string.
 */
internal const val EPIC_FIXTURE_BODY: String = """
{
  "errors": null,
  "data": {
    "Catalog": {
      "searchStore": {
        "elements": [
          {
            "title": "River City Girls 2",
            "id": "9bf3d3188d1e498095bc23c0955538d9",
            "namespace": "973ce75835994a35ab386d56ed2dffa3",
            "description": "Beat-'em-up sequel — brawl across a sprawling city.",
            "seller": { "name": "WayForward" },
            "productSlug": null,
            "urlSlug": "f6229ed5b32d4e098b930103b6470b49",
            "keyImages": [
              { "type": "OfferImageWide", "url": "https://cdn1.epicgames.com/rcg2-wide.png" },
              { "type": "Thumbnail", "url": "https://cdn1.epicgames.com/rcg2-thumb.png" }
            ],
            "price": {
              "totalPrice": {
                "discountPrice": 0,
                "originalPrice": 3999,
                "fmtPrice": { "originalPrice": "${'$'}39.99" },
                "currencyCode": "USD"
              }
            },
            "promotions": {
              "promotionalOffers": [
                { "promotionalOffers": [
                  { "startDate": "2026-07-02T15:00:00.000Z", "endDate": "2026-07-09T15:00:00.000Z",
                    "discountSetting": { "discountType": "PERCENTAGE", "discountPercentage": 0 } }
                ] }
              ],
              "upcomingPromotionalOffers": []
            },
            "offerMappings": [ { "pageSlug": "river-city-girls-2-77af3a", "pageType": "productHome" } ],
            "catalogNs": { "mappings": [ { "pageSlug": "river-city-girls-2-77af3a", "pageType": "productHome" } ] }
          },
          {
            "title": "Nova Lands",
            "id": "e5da2c017d4d4afab9f4c56525b66100",
            "namespace": "740ba4a6fbed4062a9c43d52c5996b0b",
            "description": "Factory-building island management.",
            "seller": { "name": "Hypetrain Digital" },
            "productSlug": null,
            "urlSlug": "acb83bd813054e599598de9e76f6d95d",
            "keyImages": [
              { "type": "OfferImageWide", "url": "https://cdn1.epicgames.com/nova-wide.png" }
            ],
            "price": {
              "totalPrice": {
                "discountPrice": 1999,
                "originalPrice": 1999,
                "fmtPrice": { "originalPrice": "${'$'}19.99" },
                "currencyCode": "USD"
              }
            },
            "promotions": {
              "promotionalOffers": [],
              "upcomingPromotionalOffers": [
                { "promotionalOffers": [
                  { "startDate": "2026-07-16T15:00:00.000Z", "endDate": "2026-07-30T15:00:00.000Z",
                    "discountSetting": { "discountType": "PERCENTAGE", "discountPercentage": 50 } },
                  { "startDate": "2026-07-09T15:00:00.000Z", "endDate": "2026-07-16T15:00:00.000Z",
                    "discountSetting": { "discountType": "PERCENTAGE", "discountPercentage": 0 } },
                  { "startDate": "2026-09-03T15:00:00.000Z", "endDate": "2026-09-17T15:00:00.000Z",
                    "discountSetting": { "discountType": "PERCENTAGE", "discountPercentage": 50 } }
                ] }
              ]
            },
            "offerMappings": [ { "pageSlug": "nova-lands-4d1788", "pageType": "productHome" } ],
            "catalogNs": { "mappings": [ { "pageSlug": "nova-lands-4d1788", "pageType": "productHome" } ] }
          },
          {
            "title": "Sale Only Game",
            "id": "sale0001",
            "description": "On sale, but never free.",
            "seller": { "name": "Some Publisher" },
            "productSlug": "sale-only-game",
            "price": { "totalPrice": { "discountPrice": 1599, "originalPrice": 1999, "fmtPrice": { "originalPrice": "${'$'}19.99" }, "currencyCode": "USD" } },
            "promotions": {
              "promotionalOffers": [
                { "promotionalOffers": [
                  { "startDate": "2026-07-02T15:00:00.000Z", "endDate": "2026-07-09T15:00:00.000Z",
                    "discountSetting": { "discountType": "PERCENTAGE", "discountPercentage": 20 } }
                ] }
              ],
              "upcomingPromotionalOffers": []
            }
          },
          {
            "title": "No Promo Game",
            "id": "nopromo1",
            "description": "Just a catalog entry.",
            "seller": { "name": "Another Publisher" },
            "productSlug": "no-promo-game",
            "price": { "totalPrice": { "discountPrice": 999, "originalPrice": 999, "currencyCode": "USD" } },
            "promotions": null
          }
        ]
      }
    }
  }
}
"""
