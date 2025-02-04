package com.jakewharton.mosaic

import com.jakewharton.mosaic.TextStyle.Companion.Bold
import com.jakewharton.mosaic.TextStyle.Companion.Dim
import com.jakewharton.mosaic.TextStyle.Companion.Invert
import com.jakewharton.mosaic.TextStyle.Companion.Italic
import com.jakewharton.mosaic.TextStyle.Companion.None
import com.jakewharton.mosaic.TextStyle.Companion.Strikethrough
import com.jakewharton.mosaic.TextStyle.Companion.Underline

internal interface TextCanvas {
	val width: Int
	val height: Int

	operator fun get(row: Int, column: Int): TextPixel

	operator fun get(row: Int, columns: IntRange) = get(row..row, columns)
	operator fun get(rows: IntRange, column: Int) = get(rows, column..column)

	operator fun get(rows: IntRange, columns: IntRange): TextCanvas {
		val top = rows.first
		require(top in 0 until height) { "Row start value out of range [0,$height): $top"}
		val bottom = rows.last
		require(bottom < height) { "Row end value out of range [0,$height): $bottom"}
		val left = columns.first
		require(left in 0 until width) { "Column start value out of range [0,$width): $left"}
		val right = columns.last
		require(right < width) { "Column end value out of range [0,$width): $right"}

		return ClippedTextCanvas(this, left, top, right, bottom)
	}

	fun empty(): TextCanvas {
		return ClippedTextCanvas(this, 0, 0, -1, -1)
	}

	fun write(
		row: Int,
		column: Int,
		string: String,
		foreground: Color? = null,
		background: Color? = null,
		style: TextStyle? = null,
	) {
		var pixelIndex = 0
		var characterColumn = column
		while (pixelIndex < string.length) {
			val character = this[row, characterColumn++]

			val pixelEnd = if (string[pixelIndex].isHighSurrogate()) {
				pixelIndex + 2
			} else {
				pixelIndex + 1
			}
			character.value = string.substring(pixelIndex, pixelEnd)
			pixelIndex = pixelEnd

			if (background != null) {
				character.background = background
			}
			if (foreground != null) {
				character.foreground = foreground
			}
			if (style != null) {
				character.style = style
			}
		}
	}

	fun fill(body: TextPixel.() -> Unit) {
		for (row in 0 until height) {
			for (column in 0 until width) {
				this[row, column].body()
			}
		}
	}

	fun render(
		top: Int = 0,
		left: Int = 0,
		bottom: Int = height - 1,
		right: Int = width - 1,
	): String

	override fun toString(): String
}

internal class ClippedTextCanvas(
	private val delegate: TextCanvas,
	private val left: Int,
	private val top: Int,
	right: Int,
	bottom: Int,
) : TextCanvas {
	override val width = right - left + 1
	override val height = bottom - top + 1

	override fun get(row: Int, column: Int): TextPixel {
		require(row in 0 until height) { "Row value out of range [0,$height): $row"}
		require(column in 0 until width) { "Column value out of range [0,$width): $column"}
		return delegate[top + row, left + column]
	}

	override fun render(top: Int, left: Int, bottom: Int, right: Int): String {
		return delegate.render(
			this.top + top,
			this.left + left,
			this.top + bottom,
			this.left + right,
		)
	}

	override fun toString() = render()
}

private val blankPixel = TextPixel(' ')

internal class TextSurface(
	override val width: Int,
	override val height: Int,
) : TextCanvas {
	private val rows = Array(height) { Array(width) { TextPixel(' ') } }

	override operator fun get(row: Int, column: Int) = rows[row][column]

	override fun toString() = render()

	override fun render(
		top: Int,
		left: Int,
		bottom: Int,
		right: Int,
	): String {
		val renderWidth = right - left + 1
		val renderHeight = bottom - top + 1
		val sizeEstimate = (renderWidth * renderHeight + renderHeight /* newlines */) * 2 /* ANSI factor */
		return buildString(sizeEstimate) {
			// Reused heap allocation for building ANSI attributes inside the loop.
			val attributes = mutableListOf<Int>()

			var lastPixel = blankPixel
			for (rowIndex in top..bottom) {
				val row = rows[rowIndex]
				if (rowIndex > top) {
					if (lastPixel.background != null ||
						lastPixel.foreground != null ||
						lastPixel.style != None) {
						append("\u001B[0m")
					}
					append('\n')
					lastPixel = blankPixel
				}

				for (columnIndex in left..right) {
					val pixel = row[columnIndex]
					if (pixel.foreground != lastPixel.foreground) {
						attributes += pixel.foreground?.fg ?: 39
					}
					if (pixel.background != lastPixel.background) {
						attributes += pixel.background?.bg ?: 49
					}

					fun maybeToggleStyle(style: TextStyle, on: Int, off: Int) {
						if (style in pixel.style) {
							if (style !in lastPixel.style) {
								attributes += on
							}
						} else if (style in lastPixel.style) {
							attributes += off
						}
					}
					if (pixel.style != lastPixel.style) {
						maybeToggleStyle(Bold, 1, 22)
						maybeToggleStyle(Dim, 2, 22)
						maybeToggleStyle(Italic, 3, 23)
						maybeToggleStyle(Underline, 4, 24)
						maybeToggleStyle(Invert, 7, 27)
						maybeToggleStyle(Strikethrough, 9, 29)
					}
					if (attributes.isNotEmpty()) {
						attributes.joinTo(this, separator = ";", prefix = "\u001B[", postfix = "m")
						attributes.clear() // This list is reused!
					}

					append(pixel.value)
					lastPixel = pixel
				}
			}

			if (lastPixel.background != null ||
					lastPixel.foreground != null ||
					lastPixel.style != None) {
				append("\u001B[0m")
			}
		}
	}
}

internal class TextPixel(var value: String) {
	var background: Color? = null
	var foreground: Color? = null
	var style = None

	constructor(char: Char) : this(char.toString())
}
