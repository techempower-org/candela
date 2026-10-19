package `in`.jphe.storyvox.source.wattpad

/** Shapes captured from the live Wattpad API (Oct 2026), trimmed. */
object WattpadFixtures {
    const val LIST = """{"stories":[
{"id":"26327373","title":"The Dragon and the Princess","user":{"name":"Dragon__Wolf"},"cover":"https://img.wattpad.com/cover/26327373-256-k228682.jpg","description":"A fairy tale, told differently.","tags":["dragon","fantasy"],"numParts":34,"completed":true,"mature":false},
{"id":"999","title":"Hidden","user":{"name":"x"},"completed":false,"mature":true}
],"nextUrl":"https://www.wattpad.com/api/v3/stories?filter=hot&offset=20"}"""

    const val STORY = """{"id":"26327373","title":"The Dragon and the Princess","user":{"name":"Dragon__Wolf"},"description":"A fairy tale.","cover":"https://img.wattpad.com/cover/26327373-256-k228682.jpg","completed":true,"tags":["dragon"],"mature":false,
"parts":[{"id":80845154,"title":"Declaration","createDate":"2015-01-23T10:09:19Z","wordCount":109},{"id":80847228,"title":"Chapter 1","createDate":"2015-01-24T10:00:00Z","wordCount":2000},{"id":1,"title":"draft","draft":true}]}"""
}
