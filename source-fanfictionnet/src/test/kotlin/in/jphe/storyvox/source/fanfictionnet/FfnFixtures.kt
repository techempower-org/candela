package `in`.jphe.storyvox.source.fanfictionnet

/** Trimmed FanFiction.net markup (same shape FanFicFare's adapter parses). */
object FfnFixtures {
    const val LIST = """<html><head><title>Harry Potter FanFiction Archive | FanFiction</title></head><body>
<div id="content_wrapper_inner">
<div class="z-list zhover zpointer " style="min-height:77px;border-bottom:1px #cdcdcd solid;">
<a class="stitle" href="/s/2636963/1/Harry-Potter-and-the-Methods-of-Rationality"><img class="lazy cimage " style="clear:left;float:left;margin-right:3px;padding:2px;border:1px solid #ccc;" src="/static/images/d_60_90.jpg" data-original="/image/180/75/" width="50" height="66">Harry Potter and the Methods of Rationality</a>
<a href="/s/2636963/122/Harry-Potter-and-the-Methods-of-Rationality"><span class="icon-chevron-right xicon-section-arrow"></span></a>
by <a href="/u/1004015/Less-Wrong">Less Wrong</a> <a class="reviews" href="/r/2636963/">reviews</a>
<div class="z-indent z-padtop">Petunia married a biochemist, and Harry grew up reading science.<div class="z-padtop2 xgray">Rated: T - English - Drama/Humor - Chapters: 122 - Words: 661,619 - Reviews: 37,000 - Favs: 25,000 - Follows: 17,000 - Updated: <span data-xutime="1426316400">3/14/2015</span> - Published: <span data-xutime="1267660800">2/28/2010</span> - Harry P., Hermione G. - Complete</div></div>
</div>
<div class="z-list zhover zpointer ">
<a class="stitle" href="/s/12345/1/Second-Story">Second Story</a>
by <a href="/u/42/Someone">Someone</a>
<div class="z-indent z-padtop">A summary.<div class="z-padtop2 xgray">Rated: K+ - English - Adventure - Chapters: 3 - Words: 9,000</div></div>
</div>
</div>
<center style="margin-top:5px;margin-bottom:5px;"> 1 <a href="/book/Harry-Potter/?&srt=4&p=2">2</a> <a href="/book/Harry-Potter/?&srt=4&p=2">Next &#187;</a></center>
</body></html>"""

    const val STORY = """<html><head><title>Harry Potter and the Methods of Rationality Chapter 1, a harry potter fanfic | FanFiction</title></head><body>
<div id="pre_story_links"><span class="lc-left"><a class="xcontrast_txt" href="/book/">Books</a><span class="xcontrast_txt icon-chevron-right xicon-section-arrow"></span><a class="xcontrast_txt" href="/book/Harry-Potter/">Harry Potter</a></span></div>
<div id="profile_top" style="min-height:112px;">
<span class="novisited"><img class="cimage" src="//www.fanfiction.net/image/180/180/" width="75" height="100"></span>
<button class="btn pull-right">Follow/Fav</button><b class="xcontrast_txt">Harry Potter and the Methods of Rationality</b>
<span class="xcontrast_txt"><div style="height:5px"></div>By:</span> <a class="xcontrast_txt" href="/u/1004015/Less-Wrong">Less Wrong</a>
<div style="margin-top:2px" class="xcontrast_txt">Petunia married a biochemist, and Harry grew up reading science.</div>
<span class="xgray xcontrast_txt">Rated: <a class="xcontrast_txt" href="https://www.fictionratings.com/" target="rating">Fiction  T</a> - English - Drama/Humor - Harry P., Hermione G. - Chapters: 3 - Words: 661,619 - Reviews: 37,000 - Favs: 25,000 - Follows: 17,000 - Updated: <span data-xutime="1426316400">3/14/2015</span> - Published: <span data-xutime="1267660800">2/28/2010</span> - Status: Complete - id: 2636963 </span>
</div>
<span style="float:right;"><select id="chap_select" title="Chapter Navigation" name="chapter"><option value="1" selected>1. A Day of Very Low Probability</option><option value="2">2. Everything I Believe Is False</option><option value="3">3. Comparing Reality To Its Alternatives</option></select></span>
<div role="main" aria-label="story content" class="storytextp" id="storytextp"><div class="storytext xcontrast_txt nocopy" id="storytext">
<p>Beneath the moonlight glints a tiny fragment of silver.</p><div class="google-auto-placed">AD</div><p>Every inch of wall space is covered by a bookcase.</p>
</div></div>
<span style="float:right;"><select id="chap_select" title="Chapter Navigation" name="chapter"><option value="1" selected>1. A Day of Very Low Probability</option><option value="2">2. Everything I Believe Is False</option><option value="3">3. Comparing Reality To Its Alternatives</option></select></span>
</body></html>"""

    const val NOT_FOUND = """<html><body><span class="gui_warning">Story Not Found<hr size="1" noshade>Unable to locate story. Code 1.</span></body></html>"""
}
