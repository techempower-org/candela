package `in`.jphe.storyvox.data

import `in`.jphe.storyvox.data.repository.AddByUrlResult
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.sync.domain.InboxDelivery
import org.junit.Assert.assertEquals
import org.junit.Test

/** Issue #1469 — how an add-by-URL outcome maps to an inbox delivery. */
class InboxSinkImplTest {

    private fun failure(f: FictionResult.Failure) = AddByUrlResult.SourceFailure(f)

    @Test
    fun `success is delivered`() {
        assertEquals(InboxDelivery.Delivered, InboxSinkImpl.classify(AddByUrlResult.Success("readability:abc")))
    }

    @Test
    fun `structural misses are rejected`() {
        assertEquals(InboxDelivery.Rejected, InboxSinkImpl.classify(AddByUrlResult.UnrecognizedUrl))
        assertEquals(InboxDelivery.Rejected, InboxSinkImpl.classify(AddByUrlResult.UnsupportedSource("x")))
        assertEquals(InboxDelivery.Rejected, InboxSinkImpl.classify(failure(FictionResult.NotFound())))
        assertEquals(InboxDelivery.Rejected, InboxSinkImpl.classify(failure(FictionResult.AuthRequired())))
    }

    @Test
    fun `transient source failures retry`() {
        assertEquals(InboxDelivery.Retry, InboxSinkImpl.classify(failure(FictionResult.NetworkError("offline"))))
        assertEquals(InboxDelivery.Retry, InboxSinkImpl.classify(failure(FictionResult.RateLimited(retryAfter = null))))
        assertEquals(InboxDelivery.Retry, InboxSinkImpl.classify(failure(FictionResult.Cloudflare("https://c"))))
    }
}
