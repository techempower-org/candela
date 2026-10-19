package `in`.jphe.storyvox.source.spacebattles

/**
 * Trimmed XenForo 2 markup in the shape the live boards serve (captured from
 * Questionable Questing, 2026-10 — SB/SV run the same templates).
 */
object XfFixtures {
    /** Forum thread listing: one sticky row (skipped) + two story threads + page nav. */
    const val FORUM = """<html><body>
<div class="structItemContainer-group structItemContainer-group--sticky">
<div class="structItem structItem--thread js-threadListItem-953" data-author="Mod">
 <div class="structItem-cell structItem-cell--main">
  <ul class="structItem-statuses"><li><i class="structItem-status structItem-status--sticky" title="Sticky"></i></li></ul>
  <div class="structItem-title"><a href="/threads/forum-rules.953/" data-tp-primary="on">Forum Rules</a></div>
 </div>
</div>
</div>
<div class="structItemContainer-group js-threadList">
<div class="structItem structItem--thread js-threadListItem-8961" data-author="Mr Zoat">
 <div class="structItem-cell structItem-cell--main">
  <div class="structItem-title">
   <a href="/forums/story-archive.28/?prefix_id=2" class="labelLink"><span class="label">[Archive]</span></a>
   <a href="/threads/with-this-ring-young-justice-si-story-only.8961/" data-tp-primary="on">With This Ring (Young Justice SI) (Story Only)</a>
  </div>
  <div class="structItem-minor"><ul class="structItem-parts">
   <li><a href="/members/mr-zoat.12669/" class="username">Mr Zoat</a></li>
   <li class="structItem-startDate"><a href="/threads/with-this-ring-young-justice-si-story-only.8961/"><time class="u-dt" data-time="1421119695">Jan 13, 2015</time></a></li>
  </ul></div>
 </div>
 <div class="structItem-cell structItem-cell--meta" title="First message reaction score: 35">
  <dl class="pairs pairs--justified"><dt>Replies</dt><dd><a href="/threads/with-this-ring-young-justice-si-story-only.8961/who-replied/">892</a></dd></dl>
  <dl class="pairs pairs--justified structItem-minor"><dt>Views</dt><dd>752K</dd></dl>
 </div>
 <div class="structItem-cell structItem-cell--latest">
  <a href="/threads/with-this-ring-young-justice-si-story-only.8961/latest"><time class="structItem-latestDate u-dt" data-time="1760641554">Oct 16, 2026</time></a>
 </div>
</div>
<div class="structItem structItem--thread js-threadListItem-41108" data-author="Gacha">
 <div class="structItem-cell structItem-cell--main">
  <div class="structItem-title"><a href="/threads/a-chaotic-whirlpool-naruto-with-the-chaos-gacha.41108/" data-tp-primary="on">A Chaotic Whirlpool</a></div>
 </div>
 <div class="structItem-cell structItem-cell--meta"><dl class="pairs pairs--justified"><dt>Replies</dt><dd>1.2k</dd></dl></div>
</div>
</div>
<nav class="pageNavWrapper pageNavWrapper--full">
<div class="pageNav"><ul class="pageNav-main">
<li class="pageNav-page pageNav-page--current"><a href="/forums/creative-writing.19/">1</a></li>
<li class="pageNav-page pageNav-page--later"><a href="/forums/creative-writing.19/page-2">2</a></li>
</ul><a href="/forums/creative-writing.19/page-2" class="pageNav-jump pageNav-jump--next">Next</a></div>
</nav>
</body></html>"""

    /** `/threads/<id>/threadmarks` index: title with a prefix label, two marks, page nav. */
    const val THREADMARKS = """<html><body>
<div class="p-title"><h1 class="p-title-value"><span class="label label--hidden">[Archive]</span><span class="label-append">&nbsp;</span>With This Ring (Young Justice SI) (Story Only)</h1></div>
<div class="block-formSectionHeader"><span class="block-formSectionHeader-aligner">Threadmarks · 171 threadmarks · 207k words</span></div>
<div class="block-body block-body--threadmarkBody category-1 is-active">
<div class="structItem structItem--threadmark js-inlineModContainer" data-likes="472" data-content-author="Mr Zoat" data-content-date="1421119695">
 <div class="structItem-cell structItem-cell--main">
  <div class="structItem-title threadmark_depth0"><ul class="listInline listInline--bullet"><li>
   <a href="https://forums.example.com/threads/with-this-ring-young-justice-si-story-only.8961/post-2408634" data-tp-primary="on">Dependency Day</a>
  </li></ul></div>
 </div>
 <div class="structItem-cell structItem-cell--meta" title="Likes: 472"><dl class="pairs pairs--justified"><dt>Words</dt><dd>1.3k</dd></dl></div>
</div>
<div class="structItem structItem--threadmark js-inlineModContainer" data-likes="301" data-content-author="Mr Zoat" data-content-date="1421206095">
 <div class="structItem-cell structItem-cell--main">
  <div class="structItem-title threadmark_depth0"><ul class="listInline listInline--bullet"><li>
   <a href="https://forums.example.com/threads/with-this-ring-young-justice-si-story-only.8961/post-2408700" data-tp-primary="on">Renegade Reasoning</a>
  </li></ul></div>
 </div>
 <div class="structItem-cell structItem-cell--meta"><dl class="pairs pairs--justified"><dt>Words</dt><dd>980</dd></dl></div>
</div>
</div>
<div class="threadmarks-pagenav threadmarks-pagenav--bottom"><nav class="pageNavWrapper"><div class="pageNav"><ul class="pageNav-main">
<li class="pageNav-page pageNav-page--current"><a href="/threads/with-this-ring-young-justice-si-story-only.8961/threadmarks?per_page=100">1</a></li>
<li class="pageNav-page pageNav-page--later"><a href="/threads/with-this-ring-young-justice-si-story-only.8961/threadmarks?per_page=100&amp;page=2">2</a></li>
</ul></div></nav></div>
</body></html>"""

    /** Threadmarks index with a single page and no page nav (the common case). */
    const val THREADMARKS_LAST_PAGE = """<html><body>
<div class="p-title"><h1 class="p-title-value">Short Story</h1></div>
<div class="structItem structItem--threadmark" data-content-author="Author" data-content-date="1700000000">
 <div class="structItem-cell structItem-cell--main"><div class="structItem-title threadmark_depth0">
  <a href="/threads/short-story.42/post-77">Only Chapter</a>
 </div></div>
 <div class="structItem-cell structItem-cell--meta"><dl><dt>Words</dt><dd>2.5k</dd></dl></div>
</div>
</body></html>"""

    /** A thread page holding the target post (plus a non-threadmarked reply). */
    const val THREAD_PAGE = """<html><body>
<div class="p-title"><h1 class="p-title-value">With This Ring (Young Justice SI) (Story Only)</h1></div>
<div class="p-description"><ul class="listInline listInline--bullet"><li>
 <a href="/members/mr-zoat.12669/" class="username u-concealed">Mr Zoat</a>
</li></ul></div>
<article class="message message--post hasThreadmark js-post" data-author="Mr Zoat" data-content="post-2408634" id="js-post-2408634">
 <span class="u-anchorTarget" id="post-2408634"></span>
 <div class="message-cell message-cell--threadmark-header">
  <span class="primary"><label for="threadmark-1">Threadmarks</label> <span id="threadmark-1" class="threadmarkLabel">Dependency Day</span></span>
 </div>
 <div class="message-cell message-cell--main">
  <div class="message-userContent lbContainer js-lbContainer">
   <div class="bbWrapper">The ring hums.<br><br>I look at my hand.<script>track()</script>
   <div class="message-lastEdit">Last edited: Jan 30, 2019</div></div>
  </div>
 </div>
</article>
<article class="message message--post js-post" data-author="Mr Zoat" data-content="post-2408639" id="js-post-2408639">
 <div class="message-cell message-cell--main"><div class="message-userContent"><div class="bbWrapper">A reply, not a chapter.</div></div></div>
</article>
</body></html>"""
}
