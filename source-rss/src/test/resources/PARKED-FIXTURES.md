# Parked fixtures

## `tricycle-feed-snapshot.xml`

The real `https://tricycle.org/feed/` body (WordPress RSS 2.0, ~176 KB,
captured 2026-07-02 while investigating #1489).

**No test references it yet — deliberately.** During #1489 we confirmed the
parser already handles this exact feed in production (JP's chapters loaded on
re-entry once the fetch succeeded), so a fetch-vs-parse discriminator test is
unnecessary. It's parked here as future **androidTest** material: a
`RssParser.parse` round-trip belongs in `androidTest` (where `android.util.Xml`
is the real framework impl), not in a plain-JUnit unit test — this module's
unit tests avoid Robolectric per the project convention.

If you wire that androidTest later: assert the feed yields 10 items with
non-blank titles. If it ever fails, the on-device parser has regressed.
