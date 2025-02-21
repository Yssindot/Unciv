package com.unciv.utils.concurrency

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.LifecycleListener
import com.unciv.UncivGame
import com.unciv.ui.crashhandling.wrapCrashHandlingUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.coroutines.CoroutineContext

/**
 * Created to make handling multiple threads as simple as possible. Everything is based upon [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-guide.html),
 * so fully understanding this code requires familiarity with that.
 *
 * However, the simple usage guide:
 * - Use the `run...` functions within code that does not use any concurrency yet.
 * - Then, use the `launch...` functions within `run...` code blocks.
 * - Within `suspend` functions, use [kotlinx.coroutines.coroutineScope] to gain access to the `launch...` functions.
 *
 * All methods in this file automatically wrap the given code blocks to catch all uncaught exceptions, calling [UncivGame.handleUncaughtThrowable].
 */
object Concurrency {

    /**
     * See [kotlinx.coroutines.runBlocking]. Runs on a non-daemon thread pool by default.
     *
     * @return null if an uncaught exception occured
     */
    fun <T> runBlocking(
        name: String? = null,
        context: CoroutineContext = Dispatcher.NON_DAEMON,
        block: suspend CoroutineScope.() -> T
    ): T? {
        return kotlinx.coroutines.runBlocking(addName(context, name)) {
            try {
                block(this)
            } catch (ex: Throwable) {
                UncivGame.Current.handleUncaughtThrowable(ex)
                null
            }
        }
    }

    /** Non-blocking version of [runBlocking]. Runs on a daemon thread pool by default. Use this for code that does not necessarily need to finish executing. */
    fun run(
        name: String? = null,
        scope: CoroutineScope = CoroutineScope(Dispatcher.DAEMON),
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        return scope.launchCrashHandling(scope.coroutineContext, name, block)
    }

    /** Non-blocking version of [runBlocking]. Runs on a non-daemon thread pool. Use this if you do something that should always finish if possible, like saving the game. */
    fun runOnNonDaemonThreadPool(name: String? = null, block: suspend CoroutineScope.() -> Unit) = run(name, CoroutineScope(Dispatcher.NON_DAEMON), block)

    /** Non-blocking version of [runBlocking]. Runs on the GDX GL thread. Use this for all code that manipulates the GDX UI classes. */
    fun runOnGLThread(name: String? = null, block: suspend CoroutineScope.() -> Unit) = run(name, CoroutineScope(Dispatcher.GL), block)

    /** Must only be called in [com.unciv.UncivGame.dispose] to not have any threads running that prevent JVM shutdown. */
    fun stopThreadPools() = EXECUTORS.forEach(ExecutorService::shutdown)
}

/** See [launch] */
// This method is not called `launch` (with a default DAEMON dispatcher) to prevent ambiguity between our `launch` methods and kotlin coroutine `launch` methods.
fun CoroutineScope.launchCrashHandling(
    context: CoroutineContext,
    name: String? = null,
    block: suspend CoroutineScope.() -> Unit
): Job {
    return launch(addName(context, name)) {
        try {
            block(this)
        } catch (ex: Throwable) {
            UncivGame.Current.handleUncaughtThrowable(ex)
        }
    }
}

/** See [launch]. Runs on a daemon thread pool. Use this for code that does not necessarily need to finish executing. */
fun CoroutineScope.launchOnThreadPool(name: String? = null, block: suspend CoroutineScope.() -> Unit) = launchCrashHandling(Dispatcher.DAEMON, name, block)
/** See [launch]. Runs on a non-daemon thread pool. Use this if you do something that should always finish if possible, like saving the game. */
fun CoroutineScope.launchOnNonDaemonThreadPool(name: String? = null, block: suspend CoroutineScope.() -> Unit) = launchCrashHandling(Dispatcher.NON_DAEMON, name, block)
/** See [launch]. Runs on the GDX GL thread. Use this for all code that manipulates the GDX UI classes. */
fun CoroutineScope.launchOnGLThread(name: String? = null, block: suspend CoroutineScope.() -> Unit) = launchCrashHandling(Dispatcher.GL, name, block)


/**
 * All dispatchers here bring the main game loop to a [com.unciv.CrashScreen] if an exception happens.
 */
object Dispatcher {
    /** Runs coroutines on a daemon thread pool. */
    val DAEMON: CoroutineDispatcher = createThreadpoolDispatcher("threadpool-daemon-", isDaemon = true)

    /** Runs coroutines on a non-daemon thread pool. */
    val NON_DAEMON: CoroutineDispatcher = createThreadpoolDispatcher("threadpool-nondaemon-", isDaemon = false)

    /** Runs coroutines on the GDX GL thread. */
    val GL: CoroutineDispatcher = CrashHandlingDispatcher(GLDispatcher())
}

private fun addName(context: CoroutineContext, name: String?) = if (name != null) context + CoroutineName(name) else context

private val EXECUTORS = mutableListOf<ExecutorService>()

private class GLDispatcher : CoroutineDispatcher(), LifecycleListener {
    var isDisposed = false

    init {
        Gdx.app.addLifecycleListener(this)
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (isDisposed) {
            context.cancel(CancellationException("GDX GL thread is not handling runnables anymore"))
            Dispatcher.DAEMON.dispatch(context, block) // dispatch contract states that block has to be invoked
            return
        }
        Gdx.app.postRunnable(block)
    }

    override fun dispose() {
        isDisposed = true
    }
    override fun pause() {}
    override fun resume() {}
}

private fun createThreadpoolDispatcher(threadPrefix: String, isDaemon: Boolean): CrashHandlingDispatcher {
    val executor = Executors.newCachedThreadPool(object : ThreadFactory {
        var n = 0
        override fun newThread(r: Runnable): Thread {
            val thread = Thread(r, "${threadPrefix}${n++}")
            thread.isDaemon = isDaemon
            return thread
        }
    })
    EXECUTORS.add(executor)
    return CrashHandlingDispatcher(executor.asCoroutineDispatcher())
}

class CrashHandlingDispatcher(
    private val decoratedDispatcher: CoroutineDispatcher
) : CoroutineDispatcher() {

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        decoratedDispatcher.dispatch(context, block::run.wrapCrashHandlingUnit())
    }
}
