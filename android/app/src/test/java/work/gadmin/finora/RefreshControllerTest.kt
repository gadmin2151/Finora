package work.gadmin.finora

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import work.gadmin.finora.data.*

class RefreshControllerTest {
    @Test
    fun repeatedPullsMakeOneRequest() = runBlocking {
        val response = CompletableDeferred<Unit>()
        val states = mutableListOf<Boolean>()
        var calls = 0
        var successes = 0
        val controller = RefreshController(this, states::add, { throw AssertionError(it) })
        repeat(4) {
            controller.launch({ successes++ }) {
                calls++
                response.await()
            }
        }
        yield()
        assertEquals(1, calls)
        assertEquals(listOf(true), states)
        response.complete(Unit)
        yield()
        assertEquals(1, successes)
        assertEquals(listOf(true, false), states)
    }

    @Test
    fun leavingScreenPreventsOldCompletionFromStoppingNewLoader() = runBlocking {
        val old = CompletableDeferred<Unit>()
        val fresh = CompletableDeferred<Unit>()
        val states = mutableListOf<Boolean>()
        var successes = 0
        val controller = RefreshController(this, states::add, { throw AssertionError(it) })
        controller.launch({ successes++ }) { withContext(NonCancellable) { old.await() } }
        yield()
        controller.cancel()
        controller.launch({ successes++ }) { fresh.await() }
        yield()
        old.complete(Unit)
        yield()
        assertEquals(0, successes)
        assertTrue(states.last())
        fresh.complete(Unit)
        yield()
        assertEquals(1, successes)
        assertFalse(states.last())
    }

    @Test
    fun failedRefreshStopsLoaderAndCanBeRetried() = runBlocking {
        val states = mutableListOf<Boolean>()
        val errors = mutableListOf<Exception>()
        var successes = 0
        val controller = RefreshController(this, states::add, errors::add)
        controller.launch({ successes++ }) { throw IOException("offline") }
        yield()
        assertEquals(1, errors.size)
        assertFalse(states.last())
        controller.launch({ successes++ }) {}
        yield()
        assertEquals(1, successes)
        assertFalse(states.last())
    }

    @Test
    fun refreshHasBoundedTimeout() = runBlocking {
        val error = CompletableDeferred<Exception>()
        val controller = RefreshController(this, {}, { error.complete(it) }, timeoutMillis = 20)
        controller.launch({ fail("Timed out request must not succeed") }) { awaitCancellation() }
        assertTrue(withTimeout(2000) { error.await() } is IOException)
    }

    @Test
    fun cachedAccountSurvivesOutagesButNotRevocation() {
        assertTrue(canUseSavedSession(IOException()))
        assertTrue(canUseSavedSession(ApiException(502, "gateway")))
        assertTrue(canUseSavedSession(ApiException(429, "rate limit")))
        assertFalse(canUseSavedSession(ApiException(401, "expired")))
        assertFalse(canUseSavedSession(ApiException(403, "forbidden")))
        assertFalse(canUseSavedSession(IllegalArgumentException("bad server")))
    }
}
