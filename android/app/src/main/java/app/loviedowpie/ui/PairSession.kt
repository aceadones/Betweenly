package app.loviedowpie.ui

import kotlinx.coroutines.launch
import android.content.Context
import app.loviedowpie.Pairing
import app.loviedowpie.PairingResult
import app.loviedowpie.AppScope
import app.loviedowpie.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** Which step of pairing the screen should show. */
enum class PairPhase {
    Choose,
    WaitingShow,
    WaitingJoin,
    Safety,
    Error,
}

/**
 * What the pair screen draws. [shownCode] is the code this phone is showing.
 * [toString] leaves it out so a crash report cannot print it.
 */
data class PairUi(
    val phase: PairPhase = PairPhase.Choose,
    val shownCode: String = "",
    val safety: String = "",
    val errorRes: Int = 0,
) {
    override fun toString(): String =
        "PairUi(phase=$phase, hasCode=${shownCode.isNotEmpty()}, errorRes=$errorRes)"
}

/**
 * One pairing attempt that survives a screen rotation. The activity is
 * recreated; this object is not. The code lives here only while it is on screen.
 *
 * DATA-FLOW: no network of its own. [Pairing] exchanges the handshake.
 */
object PairSession {
    private val lock = Any()
    private val runLock = Mutex()
    private var generation = 0
    private var job: Job? = null
    private val _state = MutableStateFlow(PairUi())
    val state: StateFlow<PairUi> = _state.asStateFlow()

    fun startShow(context: Context) {
        val app = context.applicationContext
        launch(PairUi(phase = PairPhase.WaitingShow)) { gen ->
            Pairing.startAsInitiator(app) { code ->
                synchronized(lock) {
                    val current = _state.value
                    if (gen == generation && current.phase == PairPhase.WaitingShow) {
                        _state.value = current.copy(shownCode = code)
                    }
                }
            }
        }
    }

    /** [code] is not stored on [state]. It exists only until this attempt ends. */
    fun startJoin(context: Context, code: String) {
        val app = context.applicationContext
        launch(PairUi(phase = PairPhase.WaitingJoin)) { _ ->
            Pairing.joinWithCode(app, code)
        }
    }

    fun cancel() {
        val previous = stop(PairUi())
        Pairing.cancel()
        previous?.cancel()
    }

    /** Leaves the safety or error screen. Does not unpair. */
    fun acknowledge() {
        synchronized(lock) {
            _state.value = PairUi()
        }
    }

    /** Drops any in-flight attempt. Called when the person unpairs. */
    fun reset() {
        val previous = stop(PairUi())
        Pairing.cancel()
        previous?.cancel()
    }

    private fun stop(next: PairUi): Job? {
        val previous: Job?
        synchronized(lock) {
            generation += 1
            previous = job
            job = null
            _state.value = next
        }
        return previous
    }

    private fun launch(waiting: PairUi, block: suspend (Int) -> PairingResult) {
        val gen: Int
        val previous: Job?
        synchronized(lock) {
            generation += 1
            gen = generation
            previous = job
            _state.value = waiting
        }
        Pairing.cancel()
        previous?.cancel()
        val newJob = AppScope.scope.launch {
            previous?.join()
            runLock.withLock {
                if (synchronized(lock) { gen != generation }) return@withLock
                val result = try {
                    block(gen)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    PairingResult.Failed("Pairing failed")
                }
                publish(gen, result)
            }
        }
        synchronized(lock) {
            if (gen == generation) job = newJob else newJob.cancel()
        }
    }

    private fun publish(gen: Int, result: PairingResult) {
        synchronized(lock) {
            if (gen != generation) return
            _state.value = when (result) {
                is PairingResult.Success -> PairUi(
                    phase = PairPhase.Safety,
                    safety = result.safetyNumber,
                )
                PairingResult.Cancelled -> PairUi()
                PairingResult.TimedOut -> PairUi(phase = PairPhase.Error, errorRes = R.string.pair_timeout)
                PairingResult.CodesDidNotMatch -> PairUi(
                    phase = PairPhase.Error,
                    errorRes = R.string.codes_did_not_match,
                )
                PairingResult.InvalidCode -> PairUi(phase = PairPhase.Error, errorRes = R.string.pair_invalid_code)
                is PairingResult.Failed -> PairUi(phase = PairPhase.Error, errorRes = failedRes(result.message))
            }
        }
    }

    private fun failedRes(message: String): Int = when (message) {
        "Could not reach the relay" -> R.string.pair_offline
        "This code was tried too many times. Make a new one." -> R.string.pair_burned
        "Already pairing" -> R.string.pair_busy
        else -> R.string.pair_failed
    }
}
