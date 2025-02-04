package com.jakewharton.mosaic

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * True for a debug-like output that renders each "frame" on its own with a timestamp delta.
 * False when using ANSI control sequences to overwrite output.
 */
private const val debugOutput = false

internal fun mosaicNodes(content: @Composable () -> Unit): MosaicNode {
	val clock = BroadcastFrameClock()
	val job = Job()
	val composeContext = clock + job

	val rootNode = MosaicNode.root()
	val recomposer = Recomposer(composeContext)
	val composition = Composition(MosaicNodeApplier(rootNode), recomposer)

	composition.setContent(content)

	job.cancel()
	composition.dispose()

	return rootNode
}

public fun renderMosaic(content: @Composable () -> Unit): String {
	val rootNode = mosaicNodes(content)
	val render = AnsiRendering().render(rootNode)
	return render.toString()
}

public interface MosaicScope : CoroutineScope {
	public fun setContent(content: @Composable () -> Unit)
}

public suspend fun runMosaic(body: suspend MosaicScope.() -> Unit): Unit = coroutineScope {
	val rendering = if (debugOutput) {
		@OptIn(ExperimentalTime::class) // Not used in production.
		DebugRendering()
	} else {
		AnsiRendering()
	}

	var hasFrameWaiters = false
	val clock = BroadcastFrameClock {
		hasFrameWaiters = true
	}

	val job = Job(coroutineContext[Job])
	val composeContext = coroutineContext + clock + job

	val rootNode = MosaicNode.root()
	val recomposer = Recomposer(composeContext)
	val composition = Composition(MosaicNodeApplier(rootNode), recomposer)

	// Start undispatched to ensure we can use suspending things inside the content.
	launch(start = UNDISPATCHED, context = composeContext) {
		recomposer.runRecomposeAndApplyChanges()
	}

	var displaySignal: CompletableDeferred<Unit>? = null
	launch(context = composeContext) {
		while (true) {
			if (hasFrameWaiters) {
				hasFrameWaiters = false
				clock.sendFrame(0L) // Frame time value is not used by Compose runtime.

				val render = rendering.render(rootNode)
				platformDisplay(render)

				displaySignal?.complete(Unit)
			}
			delay(50)
		}
	}

	coroutineScope {
		val scope = object : MosaicScope, CoroutineScope by this {
			override fun setContent(content: @Composable () -> Unit) {
				composition.setContent(content)
				hasFrameWaiters = true
			}
		}

		var snapshotNotificationsPending = false
		val observer: (state: Any) -> Unit = {
			if (!snapshotNotificationsPending) {
				snapshotNotificationsPending = true
				launch {
					snapshotNotificationsPending = false
					Snapshot.sendApplyNotifications()
				}
			}
		}
		val snapshotObserverHandle = Snapshot.registerGlobalWriteObserver(observer)
		try {
			scope.body()
		} finally {
			snapshotObserverHandle.dispose()
		}
	}

	// Ensure the final state modification is discovered. We need to ensure that the coroutine
	// which is running the recomposition loop wakes up, notices the changes, and waits for the
	// next frame. If you are using snapshots this only requires a single yield. If you are not
	// then it requires two yields. THIS IS NOT GREAT! But at least it's implementation detail...
	// TODO https://issuetracker.google.com/issues/169425431
	yield()
	yield()
	Snapshot.sendApplyNotifications()
	yield()
	yield()

	if (hasFrameWaiters) {
		CompletableDeferred<Unit>().also {
			displaySignal = it
			it.await()
		}
	}

	job.cancel()
	composition.dispose()
}
