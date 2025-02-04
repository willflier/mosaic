package com.jakewharton.mosaic

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Applier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReusableComposeNode
import com.jakewharton.mosaic.Measurable.MeasureScope
import com.jakewharton.mosaic.Placeable.PlacementScope.Companion.place

internal fun interface DrawPolicy {
	fun performDraw(canvas: TextCanvas)
}

internal fun interface StaticDrawPolicy {
	fun MosaicNode.performDrawStatics(): List<TextCanvas>

	companion object {
		val None = StaticDrawPolicy { emptyList() }
		val Children = StaticDrawPolicy {
			var statics: MutableList<TextCanvas>? = null
			for (child in children) {
				val childStatics = child.drawStatics()
				if (childStatics.isNotEmpty()) {
					if (statics == null) {
						statics = mutableListOf()
					}
					statics += childStatics
				}
			}
			statics ?: emptyList()
		}
	}
}

internal fun interface DebugPolicy {
	fun MosaicNode.renderDebug(): String
}

internal abstract class MosaicNodeLayer : Placeable(), Measurable {
	abstract val x: Int
	abstract val y: Int
	abstract fun drawTo(canvas: TextCanvas)
}

internal class MosaicNode(
	var measurePolicy: MeasurePolicy,
	var drawPolicy: DrawPolicy?,
	var staticDrawPolicy: StaticDrawPolicy,
	var debugPolicy: DebugPolicy,
) : Measurable {
	val children = mutableListOf<MosaicNode>()

	private val layer: MosaicNodeLayer = object : MosaicNodeLayer() {
		private var measureResult: MeasureResult = NotMeasured

		override val width get() = measureResult.width
		override val height get() = measureResult.height

		override fun measure() = apply {
			measureResult = measurePolicy.run { MeasureScope.run { measure(children) } }
		}

		override var x = 0
			private set
		override var y = 0
			private set

		override fun placeAt(x: Int, y: Int) {
			this.x = x
			this.y = y
			measureResult.placeChildren()
		}

		override fun drawTo(canvas: TextCanvas) {
			val drawPolicy = drawPolicy
			if (drawPolicy != null) {
				drawPolicy.performDraw(canvas)
			} else {
				for (child in children) {
					if (child.width != 0 && child.height != 0) {
						val left = child.x
						val top = child.y
						val right = left + child.width - 1
						val bottom = top + child.height - 1
						child.layer.drawTo(canvas[top..bottom, left..right])
					}
				}
			}
		}
	}

	override fun measure(): Placeable = layer.measure()

	val width: Int get() = layer.width
	val height: Int get() = layer.height
	val x: Int get() = layer.x
	val y: Int get() = layer.y

	fun draw(): TextCanvas {
		val placeable = measure()
		placeable.place(0, 0)
		val surface = TextSurface(placeable.width, placeable.height)
		layer.drawTo(surface)
		return surface
	}

	fun drawStatics() = staticDrawPolicy.run { performDrawStatics() }

	override fun toString() = debugPolicy.run { renderDebug() }

	companion object {
		val Factory: () -> MosaicNode = {
			MosaicNode(
				measurePolicy = ThrowingPolicy,
				drawPolicy = ThrowingPolicy,
				staticDrawPolicy = ThrowingPolicy,
				debugPolicy = ThrowingPolicy,
			)
		}

		fun root(): MosaicNode {
			return MosaicNode(
				measurePolicy = { measurables ->
					var width = 0
					var height = 0
					val placeables = measurables.map { measurable ->
						measurable.measure().also {
							width = maxOf(width, it.width)
							height = maxOf(height, it.height)
						}
					}
					layout(width, height) {
						for (placeable in placeables) {
							placeable.place(0, 0)
						}
					}
				},
				drawPolicy = null,
				staticDrawPolicy = StaticDrawPolicy.Children,
				debugPolicy = {
					children.joinToString(separator = "\n")
				}
			)
		}

		private val ThrowingPolicy = object : MeasurePolicy, DrawPolicy, StaticDrawPolicy, DebugPolicy {
			override fun MeasureScope.measure(measurables: List<Measurable>) = throw AssertionError()
			override fun performDraw(canvas: TextCanvas) = throw AssertionError()
			override fun MosaicNode.performDrawStatics() = throw AssertionError()
			override fun MosaicNode.renderDebug() = throw AssertionError()
		}
	}
}

@Composable
internal inline fun Node(
	content: @Composable () -> Unit = {},
	measurePolicy: MeasurePolicy,
	drawPolicy: DrawPolicy?,
	staticDrawPolicy: StaticDrawPolicy,
	debugPolicy: DebugPolicy,
) {
	ReusableComposeNode<MosaicNode, Applier<Any>>(
		factory = MosaicNode.Factory,
		update = {
			set(measurePolicy) { this.measurePolicy = measurePolicy }
			set(drawPolicy) { this.drawPolicy = drawPolicy }
			set(staticDrawPolicy) { this.staticDrawPolicy = staticDrawPolicy }
			set(debugPolicy) { this.debugPolicy = debugPolicy }
		},
		content = content,
	)
}

internal class MosaicNodeApplier(root: MosaicNode) : AbstractApplier<MosaicNode>(root) {
	override fun insertTopDown(index: Int, instance: MosaicNode) {
		// Ignored, we insert bottom-up.
	}

	override fun insertBottomUp(index: Int, instance: MosaicNode) {
		current.children.add(index, instance)
	}

	override fun remove(index: Int, count: Int) {
		current.children.remove(index, count)
	}

	override fun move(from: Int, to: Int, count: Int) {
		current.children.move(from, to, count)
	}

	override fun onClear() {}
}
