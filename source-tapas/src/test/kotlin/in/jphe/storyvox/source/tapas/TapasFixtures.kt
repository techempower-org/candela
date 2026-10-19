package `in`.jphe.storyvox.source.tapas

/** Trimmed Tapas markup/JSON in the live site's shape (captured 2026-10). */
object TapasFixtures {
    /** `/search?t=NOVELS&q=dragon` — thumb anchor (image) + title anchor per result. */
    const val SEARCH = """<html><body>
<ul class="search-item-wrap">
<li class="search__item js-search-item">
 <div class="item__thumb">
  <a class="js-fb-tracking" data-series-id="151010" data-series-type="COMMUNITY_BOOKS" href="/series/Dragontail1">
   <img src="https://us-a.tapas.io/sa/41/cover_m.jpg" alt="Dragontail" width="140" height="140">
  </a>
 </div>
 <div class="title-section">
  <p class="title force mbx">
   <a class="link link--ink js-fb-tracking" data-xref="SCH_N" data-series-id="151010" data-sale-type="FREE"
      href="/series/Dragontail1"><strong class="word-highlight">Dragontail:The</strong> firs wolfkin dragon</a>
  </p>
 </div>
</li>
<li class="search__item js-search-item">
 <div class="title-section"><p class="title">
  <a class="link link--ink" data-series-id="99001" href="/series/Dragonman">Dragonman</a>
 </p></div>
</li>
</ul>
<div class="pagination"><a href="/search?t=NOVELS&amp;q=dragon&amp;pageNumber=2">2</a></div>
</body></html>"""

    /** `/series/<slug>/info` (trimmed). */
    const val INFO = """<html><head><title>Read Dragontail:The firs wolfkin dragon | Tapas Web Community</title></head><body>
<div class="js-ad-slot" data-series-id="151010"></div>
<div class="info-body">
 <p class="title">Dragontail:The firs wolfkin dragon</p>
 <div class="creator"><a class="creator__item truncated js-fb-tracking" href="/lezdeath50">lezdeath50</a></div>
 <p class="description js-series-description"><span class="description__body">I am Red Drago and I am 10 and I am dragon wolfkin</span></p>
 <div class="genre-btn-group"><a class="genre-btn" href="/static-landing/genre?category=COMMUNITY_NOVEL&amp;genre_name=fantasy">Fantasy</a></div>
</div>
</body></html>"""

    /** `/series/<id>/episodes?sort=OLDEST` XHR JSON (trimmed to read fields). */
    const val EPISODES = """{"code":200,"msg":"","type":"DEFAULT","data":{
"pagination":{"page":1,"has_next":false,"sort":"OLDEST","total":3},
"episodes":[
 {"id":1516649,"title":"1","scene":1,"free":true,"unlocked":false,"must_pay":false,"nsfw":false,"publish_date":"2019-09-13T03:31:07Z"},
 {"id":1516700,"title":"","scene":2,"free":true,"unlocked":false,"must_pay":false,"publish_date":"2019-09-14T03:31:07Z"},
 {"id":1516800,"title":"Locked finale","scene":3,"free":false,"unlocked":false,"must_pay":true,"publish_date":"2019-09-15T03:31:07Z"}
]},"error_details":null}"""

    /** `/episode/<id>` reader page (trimmed). */
    const val EPISODE = """<html><body>
<div class="viewer">
 <div class="viewer__header"><p class="title">1</p><p class="date">Sep 12, 2019</p></div>
 <article class="viewer__body js-episode-article main__body--book ep-html-contents">
  <p>This is my story my name is Red Drago.<br></p>
  <p>My father is a dragon.</p><script>ads()</script>
 </article>
 <div class="viewer-section viewer-section--ad">AD</div>
</div>
</body></html>"""
}
