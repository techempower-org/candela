package `in`.jphe.storyvox.source.fimfiction

/** FimFiction fixtures: real-shaped legacy-API JSON (verified 2026-10) + trimmed listing/export HTML. */
object FimFixtures {
    /** `/api/story.php?story=141549`, trimmed to the fields the parser reads. */
    const val STORY_JSON = """{"story":{"id":141549,"title":"The Celestia Code",
"url":"https:\/\/www.fimfiction.net\/story\/141549\/the-celestia-code",
"short_description":"Princess Twilight Sparkle discovers a centuries-old mystery hidden in the Royal Archives.",
"description":"Princess Twilight Sparkle discovers a centuries-old mystery [i]with bbcode[\/i].",
"date_modified":1391018444,
"image":"https:\/\/cdn-img.fimfiction.net\/story\/pzq1-141549-medium",
"full_image":"https:\/\/cdn-img.fimfiction.net\/story\/pzq1-141549-full",
"views":38336,"total_views":123456,"likes":2977,"dislikes":59,
"words":68566,"chapter_count":2,"status":"Complete","content_rating":1,"content_rating_text":"Teen",
"categories":{"Adventure":true,"Mystery":true,"Romance":false},
"author":{"id":33084,"name":"iisaw"},
"chapters":[
 {"id":428863,"title":"1 The Treasure Room","words":2329,"views":38336,
  "link":"https:\/\/www.fimfiction.net\/story\/141549\/1\/the-celestia-code\/1-the-treasure-room","date_modified":1464747403},
 {"id":428864,"title":"2 The Hidden Door","words":2500,"views":30000,
  "link":"https:\/\/www.fimfiction.net\/story\/141549\/2\/the-celestia-code\/2-the-hidden-door","date_modified":1464747500}
]}}"""

    const val STORY_ERROR_JSON = """{"error":"Invalid story id"}"""

    /** `/stories` listing card (current story-card markup, trimmed). */
    const val LIST = """<html><body>
<ul class="story-card-list">
<li><div class="story-card">
 <a class="story-card__title" href="/story/141549/the-celestia-code">The Celestia Code</a>
 <div class="story-card__author">by <a href="/user/33084/iisaw">iisaw</a></div>
 <img data-src="https://cdn-img.fimfiction.net/story/pzq1-141549-medium" src="data:image/gif;base64,R0lGOD">
 <div class="story-card__description">Princess Twilight Sparkle discovers a centuries-old mystery.</div>
 <div class="story-card__tags"><a class="tag-genre" href="/tag/adventure">Adventure</a><a class="tag-genre" href="/tag/mystery">Mystery</a></div>
</div></li>
<li><div class="story-card">
 <a class="story-card__title" href="/story/9999/second-story">Second Story</a>
 <div class="story-card__author">by <a href="/user/1/Someone">Someone</a></div>
</div></li>
</ul>
<ul class="page-list"><li><a href="/stories?order=rating&page=2" rel="next">»</a></li></ul>
</body></html>"""

    /** Listing after a hypothetical class rename — only bare story anchors survive. */
    const val LIST_RENAMED = """<html><body>
<div class="totally-new-card"><a href="/story/141549/the-celestia-code">The Celestia Code</a></div>
<div class="totally-new-card"><a href="/story/141549/the-celestia-code">The Celestia Code (dupe link)</a></div>
<div class="totally-new-card"><a href="/story/9999/second-story">Second Story</a></div>
</body></html>"""

    /** `/chapters/download/<id>/html` export, trimmed. */
    const val CHAPTER = """<!DOCTYPE html><html><head><title>The Celestia Code</title></head><body>
<h3>1 The Treasure Room</h3>
<p>The Royal Archives smelled of dust and old parchment.</p>
<p>Twilight lit her horn.</p>
</body></html>"""
}
