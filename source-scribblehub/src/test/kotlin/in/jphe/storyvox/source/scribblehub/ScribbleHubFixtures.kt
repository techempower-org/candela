package `in`.jphe.storyvox.source.scribblehub

/** Scribble Hub markup in the shape FanFicFare's adapter parses (trimmed). */
object ScribbleHubFixtures {
    const val LIST = """<html><body>
<div class="search_main_box">
 <div class="search_img"><img src="https://cdn.scribblehub.com/images/1/cover.jpg" alt="Cover"></div>
 <div class="search_body">
  <div class="search_title"><a href="https://www.scribblehub.com/series/123456/the-last-alchemist/">The Last Alchemist</a></div>
  <div class="search_stats"><span class="nl_stat" title="Chapters"><i class="fa fa-list"></i> 87 Chapters</span></div>
  <div class="search_genre"><a class="fic_genre search">Fantasy</a><a class="fic_genre search">Adventure</a></div>
  A disgraced alchemist rebuilds her craft.
 </div>
</div>
<div class="simple-pagination"><a class="page-link next" href="?pg=2">Next</a></div>
</body></html>"""

    const val SERIES = """<html><body>
<div class="fic_image"><img src="https://cdn.scribblehub.com/images/1/cover.jpg"></div>
<div class="fic_title" title="The Last Alchemist">The Last Alchemist</div>
<span class="auth_name_fic">Quill</span>
<div class="wi_fic_desc"><p>A disgraced alchemist rebuilds her craft.</p></div>
<a class="fic_genre">Fantasy</a>
<span class="wi_fic_showtags_inner"><a>Alchemy</a><a>Female Protagonist</a></span>
<span title="Last updated: Oct 1, 2026">Ongoing</span>
</body></html>"""

    /** admin-ajax TOC fragment: newest first, with `order` attributes. */
    const val TOC = """<ol class="toc_ol">
<li class="toc_w" order="2"><a class="toc_a" href="https://www.scribblehub.com/read/123456-the-last-alchemist/chapter/222/">Chapter 2: Ash</a></li>
<li class="toc_w" order="1"><a class="toc_a" href="https://www.scribblehub.com/read/123456-the-last-alchemist/chapter/111/">Chapter 1: Smoke</a></li>
</ol>"""

    const val CHAPTER = """<html><body><div class="chapter-title">Chapter 1: Smoke</div>
<div id="chp_raw" class="chp_raw"><p>The furnace was cold.</p><div class="wi_authornotes"><div class="wi_authornotes_body">Thanks for reading!</div></div><p>She lit it anyway.</p></div>
</body></html>"""
}
