package work.gadmin.finora.data

import java.io.IOException
import kotlinx.coroutines.*

/** One user refresh at a time. Cancelling a screen also cancels its HTTP calls. */
internal class RefreshController(
    private val scope: CoroutineScope,
    private val onRefreshing: (Boolean) -> Unit,
    private val onError: (Exception) -> Unit,
    private val timeoutMillis: Long = 30_000,
) {
    private var job: Job? = null
    private var generation = 0L

    fun launch(onSuccess: () -> Unit, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        val request = ++generation
        onRefreshing(true)
        job = scope.launch {
            try {
                withTimeout(timeoutMillis) { block() }
                ensureActive()
                if (request == generation) onSuccess()
            } catch (error: TimeoutCancellationException) {
                if (request == generation) onError(IOException("Refresh timed out", error))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (request == generation) onError(error)
            } finally {
                if (request == generation) onRefreshing(false)
            }
        }
    }

    fun cancel() {
        generation++
        job?.cancel()
        job = null
        onRefreshing(false)
    }
}

/** A temporary outage must not discard the saved account or its local drafts. */
internal fun canUseSavedSession(error: Exception): Boolean =
    error is IOException || error is ApiException && (error.code == 429 || error.code in 500..599)
