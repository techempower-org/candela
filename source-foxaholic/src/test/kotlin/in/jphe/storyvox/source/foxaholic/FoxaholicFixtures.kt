package `in`.jphe.storyvox.source.foxaholic

/** Madara-theme markup (the WordPress theme Foxaholic runs), trimmed. */
object FoxaholicFixtures {
    const val LIST = """<html><body>
<div class="page-item-detail text">
  <div class="item-thumb"><a href="https://www.foxaholic.com/novel/moonlit-court/" title="Moonlit Court"><img class="img-responsive" data-src="https://www.foxaholic.com/wp-content/uploads/moon.jpg" src="data:image/gif;base64,R0lGOD"></a></div>
  <div class="item-summary"><div class="post-title font-title"><h3 class="h5"><a href="https://www.foxaholic.com/novel/moonlit-court/">Moonlit Court</a></h3></div></div>
</div>
<div class="nav-links"><div class="nav-previous float-left"><a href="https://www.foxaholic.com/novel/page/2/?m_orderby=views">Older Posts</a></div></div>
</body></html>"""

    const val NOVEL = """<html><body>
<div class="post-title"><h1><span class="manga-title-badges hot">HOT</span> Moonlit Court </h1></div>
<div class="summary_image"><img data-src="https://www.foxaholic.com/wp-content/uploads/moon.jpg"></div>
<div class="author-content"><a href="/author/lan/">Lan Yue</a></div>
<div class="genres-content"><a>Romance</a>, <a>Historical</a></div>
<div class="post-status"><div class="post-content_item"><div class="summary-heading"><h5>Status</h5></div><div class="summary-content">Completed</div></div></div>
<div class="summary__content"><p>An exiled princess returns.</p></div>
</body></html>"""

    const val CHAPTERS = """<ul class="main version-chap">
<li class="wp-manga-chapter"><a href="https://www.foxaholic.com/novel/moonlit-court/chapter-3/">Chapter 3</a></li>
<li class="wp-manga-chapter premium"><a href="https://www.foxaholic.com/novel/moonlit-court/chapter-2-5/">Chapter 2.5</a></li>
<li class="wp-manga-chapter"><a href="https://www.foxaholic.com/novel/moonlit-court/vol-1/chapter-2/">Chapter 2</a></li>
<li class="wp-manga-chapter"><a href="https://www.foxaholic.com/novel/moonlit-court/chapter-1/">Chapter 1</a></li>
</ul>"""

    const val CHAPTER = """<html><body><h1 id="chapter-heading">Moonlit Court - Chapter 1</h1>
<div class="reading-content"><div class="text-left"><p>The gates opened at dawn.</p><div class="code-block">AD</div><p>No one bowed.</p></div></div>
</body></html>"""
}
