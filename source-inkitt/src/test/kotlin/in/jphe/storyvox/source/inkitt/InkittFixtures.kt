package `in`.jphe.storyvox.source.inkitt

/** Trimmed Inkitt markup in the live site's shape (captured 2026-10). */
object InkittFixtures {
    /** `/stories/<id>`: schema.org Book + react-on-rails StoryChaptersList props. */
    const val STORY = """<html><head>
<script type="application/ld+json">{"@context":"https://schema.org","@type":"Book","@id":"https://www.inkitt.com/stories/7#book","url":"https://www.inkitt.com/stories/7","name":"Watching eachother souls","author":{"@type":"Person","name":"Nandani Agarwal","url":"https://www.inkitt.com/Nan_Ag"},"bookFormat":"https://schema.org/EBook","inLanguage":"en","genre":["romance","mystery"],"contentRating":"16+","isAccessibleForFree":true,"creativeWorkStatus":"Ongoing","datePublished":"2021-07-24T17:23:59Z","dateModified":"2024-07-24T22:33:49Z","description":"If you think that this project...","image":{"@type":"ImageObject","url":"https://cdn-gcs.inkitt.com/cover.jpg"}}</script>
</head><body>
<h1>Watching eachother souls</h1>
<script type="application/json" class="js-react-on-rails-component" data-component-name="StoryChaptersList" data-dom-id="StoryChaptersList-react-component-1">{"language":"en","current_chapter_number":1,"chapters":[{"number":1,"name":"Meeting My Sister's Boyfriend","path":"/stories/7/chapters/1","patron_icon":"no_icon"},{"number":2,"name":"The Dinner","path":"/stories/7/chapters/2","patron_icon":"no_icon"}]}</script>
</body></html>"""

    /** `/stories/<id>/chapters/<n>` (trimmed to the reader article). */
    const val CHAPTER = """<html><body>
<article class='default-style' id='story-text-container' style='font-size: 22px'>
 <h2 class='chapter-head-title'> Meeting My Sister's Boyfriend </h2>
 <div class='' style='position: relative'>
  <div class='story-page-text' id='chapter-text'>
   <p>The doorbell rang twice.</p>
   <p>I knew before I opened it.</p>
  </div>
 </div>
</article>
</body></html>"""

    /** What the SPA search endpoint returns today: the app shell, not JSON. */
    const val SEARCH_HTML_SHELL = """<!DOCTYPE html><html lang="en"><head><title>Inkitt</title></head><body></body></html>"""

    /** The shape the tolerant search reader accepts, should the API surface. */
    const val SEARCH_JSON = """{"stories":[{"id":7,"title":"Watching eachother souls"},{"id":99,"name":"Other"}]}"""
}
