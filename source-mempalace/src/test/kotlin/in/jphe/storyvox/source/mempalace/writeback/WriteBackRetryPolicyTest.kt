package `in`.jphe.storyvox.source.mempalace.writeback

import `in`.jphe.storyvox.source.mempalace.net.PalaceDaemonResult
import `in`.jphe.storyvox.source.mempalace.writeback.WriteBackRetryPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** Issue #1468 — which palace outcomes retry, which give up, and the attempt cap. */
class WriteBackRetryPolicyTest {

    private fun decide(r: PalaceDaemonResult<*>, attempt: Int = 0) = WriteBackRetryPolicy.decide(r, attempt)

    @Test fun `success is done`() {
        assertEquals(Decision.Done, decide(PalaceDaemonResult.Success(Unit)))
        // Even past the cap a success is a success.
        assertEquals(Decision.Done, decide(PalaceDaemonResult.Success(Unit), WriteBackRetryPolicy.MAX_ATTEMPTS))
    }

    @Test fun `offline and daemon-degraded retry`() {
        assertEquals(Decision.Retry, decide(PalaceDaemonResult.NotReachable(IOException("off LAN"))))
        assertEquals(Decision.Retry, decide(PalaceDaemonResult.Degraded("rebuilding")))
    }

    @Test fun `server errors and throttling retry`() {
        assertEquals(Decision.Retry, decide(PalaceDaemonResult.HttpError(500, "boom")))
        assertEquals(Decision.Retry, decide(PalaceDaemonResult.HttpError(502, "bad gateway")))
        assertEquals(Decision.Retry, decide(PalaceDaemonResult.HttpError(408, "timeout")))
        assertEquals(Decision.Retry, decide(PalaceDaemonResult.HttpError(429, "slow down")))
    }

    @Test fun `permanent failures give up immediately`() {
        assertEquals(Decision.GiveUp, decide(PalaceDaemonResult.Unauthorized("bad key")))
        assertEquals(Decision.GiveUp, decide(PalaceDaemonResult.HostRejected("8.8.8.8")))
        assertEquals(Decision.GiveUp, decide(PalaceDaemonResult.NotFound("no route")))
        assertEquals(Decision.GiveUp, decide(PalaceDaemonResult.ParseError(IOException("x"))))
        // 400 = the daemon rejected the room or wing; retrying cannot fix it.
        assertEquals(Decision.GiveUp, decide(PalaceDaemonResult.HttpError(400, "invalid room")))
        assertEquals(Decision.GiveUp, decide(PalaceDaemonResult.HttpError(413, "too large")))
    }

    @Test fun `transient failures stop at the attempt cap`() {
        val offline = PalaceDaemonResult.NotReachable(IOException("off LAN"))
        val last = WriteBackRetryPolicy.MAX_ATTEMPTS - 1
        assertEquals(Decision.Retry, decide(offline, last - 1))
        assertEquals(Decision.GiveUp, decide(offline, last))
        assertEquals(Decision.GiveUp, decide(offline, last + 5))
    }
}
