package `in`.jphe.storyvox.source.hackernews

import org.junit.Assert.fail
import org.junit.Test

/** THROWAWAY positive control for the step-summary change: 12 failures (> the 10-annotation cap). Reverted before merge. */
class SummaryCanaryTest {
    @Test fun `canary 01 fails on purpose`() = fail("canary 01")
    @Test fun `canary 02 fails on purpose`() = fail("canary 02")
    @Test fun `canary 03 fails on purpose`() = fail("canary 03")
    @Test fun `canary 04 fails on purpose`() = fail("canary 04")
    @Test fun `canary 05 fails on purpose`() = fail("canary 05")
    @Test fun `canary 06 fails on purpose`() = fail("canary 06")
    @Test fun `canary 07 fails on purpose`() = fail("canary 07")
    @Test fun `canary 08 fails on purpose`() = fail("canary 08")
    @Test fun `canary 09 fails on purpose`() = fail("canary 09")
    @Test fun `canary 10 fails on purpose`() = fail("canary 10")
    @Test fun `canary 11 fails on purpose`() = fail("canary 11")
    @Test fun `canary 12 fails on purpose`() = fail("canary 12")
}
