package com.jakewharton.mosaic

import kotlin.time.ExperimentalTime
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal interface Rendering {
	/**
	 * Render [node] to a single string for display.
	 *
	 * Note: The returned [CharSequence] is only valid until the next call to this function,
	 * as implementations are free to reuse buffers across invocations.
	 */
	fun render(node: MosaicNode): CharSequence
}

@ExperimentalTime
internal class DebugRendering(
	private val systemClock: TimeSource = TimeSource.Monotonic,
) : Rendering {
	private var lastRender: TimeMark? = null

	override fun render(node: MosaicNode): CharSequence {
		return buildString {
			lastRender?.let { lastRender ->
				repeat(50) { append('~') }
				append(" +")
				appendLine(lastRender.elapsedNow())
			}
			lastRender = systemClock.markNow()

			val canvas = node.draw()
			val statics = node.drawStatics()

			appendLine("NODES:")
			appendLine(node)
			appendLine()
			if (statics.isNotEmpty()) {
				appendLine("STATIC:")
				for (static in statics) {
					appendLine(static)
				}
				appendLine()
			}
			appendLine("OUTPUT:")
			appendLine(canvas)
		}
	}
}

internal class AnsiRendering : Rendering {
	private val stringBuilder = StringBuilder(100)
	private var lastHeight = 0

	override fun render(node: MosaicNode): CharSequence {
		val canvas = node.draw()
		val statics = node.drawStatics()

		return stringBuilder.apply {
			clear()

			var staleLines = lastHeight
			repeat(staleLines) {
				append(cursorUp)
			}

			val staticLines = statics.flatMap { it.render().split("\n") }
			val lines = canvas.render().split("\n")
			for (line in staticLines + lines) {
				append(line)
				if (staleLines-- > 0) {
					// We have previously drawn on this line. Clear the rest to be safe.
					append(clearLine)
				}
				append('\n')
			}

			// If the new output contains fewer lines than the last output, clear those old lines.
			for (i in 0 until staleLines) {
				if (i > 0) {
					append('\n')
				}
				append(clearLine)
			}

			// Move cursor back up to end of the new output.
			repeat(staleLines - 1) {
				append(cursorUp)
			}

			lastHeight = lines.size
		}
	}
}
