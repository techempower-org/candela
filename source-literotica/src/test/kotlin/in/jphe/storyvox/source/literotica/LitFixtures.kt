package `in`.jphe.storyvox.source.literotica

/** `/api/3`-shaped fixtures (the endpoint is undocumented; shapes as observed from the site's SPA). */
object LitFixtures {
    const val LIST = """{"submissions":[
 {"id":123,"url":"https://www.literotica.com/s/the-lighthouse-keeper","title":"The Lighthouse Keeper",
  "description":"A storm, a stranger, a lamp that must stay lit.",
  "author":{"username":"SeaGlass"},"category_info":{"name":"Romance"},"rate_all":4.62,
  "meta":{"pages_count":3}},
 {"id":124,"url":"second-watch","title":"Second Watch","author_name":"Quill",
  "rate_all":"4.1"}
],"meta":{"current_page":1,"last_page":5}}"""

    const val STORY = """{"submission":{"id":123,"url":"the-lighthouse-keeper","title":"The Lighthouse Keeper",
 "description":"A storm, a stranger, a lamp that must stay lit.",
 "author":{"username":"SeaGlass"},"category_info":{"name":"Romance"},"rate_all":4.62},
"pageText":"The storm came in from the west.\r\n\r\nShe climbed the stairs with the oil can.",
"meta":{"pages_count":3}}"""

    const val STORY_PAGE_2 = """{"submission":{"id":123,"url":"the-lighthouse-keeper","title":"The Lighthouse Keeper",
 "author":{"username":"SeaGlass"}},
"pageText":"<p>Morning found the lamp still burning.</p><p>He was gone.</p>",
"meta":{"pages_count":3}}"""
}
