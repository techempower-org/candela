package `in`.jphe.storyvox.source.quotev

/**
 * Quotev fixtures. The live site is behind its JS wall (see QuotevApi), so
 * these encode the URL shapes the generic parser targets, not verified page
 * chrome.
 */
object QuotevFixtures {
    const val LIST = """<html><body>
<div class="feed">
 <a href="/story/14503869/The-Last-Door">The Last Door</a>
 <a href="/story/14503869/The-Last-Door"><img src="/img/cover.jpg"></a>
 <a href="/story/222/Second-Story">Second Story</a>
 <a href="/quiz/999/not-a-story">A quiz</a>
</div>
</body></html>"""

    const val STORY = """<html><head><title>The Last Door - Quotev</title>
<meta name="description" content="A house with one door too many."></head><body>
<h1>The Last Door</h1>
<a href="/Doorwright" rel="author">Doorwright</a>
<div class="chapters">
 <a href="/story/14503869/The-Last-Door/1">One</a>
 <a href="/story/14503869/The-Last-Door/2">Two</a>
 <a href="/story/14503869/The-Last-Door/2#c">Two again</a>
</div>
</body></html>"""

    const val CHAPTER = """<html><body>
<h1>The Last Door</h1>
<div id="rescontent"><p>The house had eleven doors.</p><script>x()</script><p>We only built ten.</p></div>
</body></html>"""

    /** The JS wall every anonymous request currently gets. */
    const val CHALLENGE = """<script src="/js/x.js/a5933e5360.js"></script>"""
}
