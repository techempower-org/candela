package `in`.jphe.storyvox.source.livejournal

/** Real LiveJournal RSS shape (Oct 2026), content replaced. */
object LjFixtures {
    const val RSS = """<?xml version='1.0' encoding='utf-8' ?>
<rss version='2.0'  xmlns:lj='http://www.livejournal.org/rss/lj/1.0/' xmlns:atom10='http://www.w3.org/2005/Atom'>
<channel>
  <title>Tales of the Harbor</title>
  <link>https://harbor-tales.livejournal.com/</link>
  <description>Tales of the Harbor - LiveJournal.com</description>
  <lj:journal>harbor_tales</lj:journal>
  <item>
  <guid isPermaLink='true'>https://harbor-tales.livejournal.com/2048.html</guid>
  <pubDate>Mon, 19 Oct 2026 08:02:04 GMT</pubDate>
  <title>Part Two</title>
  <link>https://harbor-tales.livejournal.com/2048.html</link>
  <description>The tide came in.&lt;br /&gt;&lt;br /&gt;Nobody moved.</description>
  </item>
  <item>
  <guid isPermaLink='true'>https://harbor-tales.livejournal.com/1024.html</guid>
  <pubDate>Sun, 18 Oct 2026 08:02:04 GMT</pubDate>
  <title>Part One</title>
  <link>https://harbor-tales.livejournal.com/1024.html</link>
  <description>&lt;p&gt;It began at the docks.&lt;/p&gt;</description>
  </item>
</channel>
</rss>"""
}
