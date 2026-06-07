/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import org.schabi.newpipe.extractor.stream.StreamHeatmapEntry
import org.schabi.newpipe.extractor.stream.StreamSegment

/**
 * A [FocusAwareSeekBar] that renders narrow transparent gaps at chapter boundaries,
 * giving the seekbar a segmented "chopped" appearance, and optionally renders a
 * heatmap visualization behind the seekbar track.
 *
 * The drawing strategy depends on whether chapters are present:
 * - **No chapters**: heatmap (if any) is drawn on the main canvas, then the stock
 *   seekbar is drawn on top.
 * - **With chapters**: an offscreen layer is created so [PorterDuff.Mode.CLEAR] can
 *   punch transparent gaps through **both** the heatmap and the seekbar track.
 *   The layer is then composited back onto the main canvas, letting the parent
 *   background show through at each chapter boundary.
 */
class ChaptersSeekBar : FocusAwareSeekBar {

    /** Paint used to punch transparent chapter gaps via [PorterDuff.Mode.CLEAR]. */
    private val gapPaint = Paint().apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val heatmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x55FF9500 // ~33% transparent orange
    }
    private val heatmapPath = Path()

    private var chapters: List<StreamSegment> = emptyList()
    private var durationSeconds: Long = 0

    private var heatmapEntries: List<StreamHeatmapEntry> = emptyList()
    private var heatmapTotalDurationMillis: Long = 0

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr)

    /**
     * Stores chapter data for rendering segment gaps.
     *
     * @param newChapters     list of [StreamSegment]s; may be empty but never null
     * @param newDurationSecs total duration in seconds; used to compute fractional positions
     */
    fun setChapters(newChapters: List<StreamSegment>, newDurationSecs: Long) {
        chapters = newChapters
        durationSeconds = newDurationSecs
        invalidate()
    }

    /**
     * Stores heatmap data for rendering behind the seekbar track.
     *
     * @param entries            list of [StreamHeatmapEntry]s; may be empty
     * @param totalDurationMillis total stream duration in milliseconds
     */
    fun setHeatmap(entries: List<StreamHeatmapEntry>, totalDurationMillis: Long) {
        heatmapEntries = entries
        heatmapTotalDurationMillis = totalDurationMillis
        invalidate()
    }

    /** Clears any previously set heatmap data. */
    fun clearHeatmap() {
        heatmapEntries = emptyList()
        heatmapTotalDurationMillis = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (chapters.isEmpty() || durationSeconds <= 0) {
            // No chapters — simple layered draw: heatmap → seekbar
            drawHeatmap(canvas)
            super.onDraw(canvas)
            return
        }

        // With chapters we need an offscreen layer so CLEAR can punch transparent
        // gaps through both the heatmap and the seekbar track simultaneously.
        val sc = canvas.saveLayer(null, null)

        // 1. Draw heatmap inside the offscreen layer (behind seekbar)
        drawHeatmap(canvas)

        // 2. Draw the stock seekbar track + thumb inside the same layer
        super.onDraw(canvas)

        // 3. Punch transparent gaps at chapter boundaries
        val density = resources.displayMetrics.density
        val gapHalfWidth = (GAP_WIDTH_DP * density) / 2f
        val left = paddingLeft
        val trackWidth = (width - left - paddingRight).toFloat()

        if (trackWidth > 0) {
            for (seg in chapters) {
                val startSec = seg.startTimeSeconds
                if (startSec <= 0 || startSec.toLong() >= durationSeconds) {
                    continue
                }
                val x = left + (startSec.toFloat() / durationSeconds.toFloat()) * trackWidth
                canvas.drawRect(
                    x - gapHalfWidth,
                    0f,
                    x + gapHalfWidth,
                    height.toFloat(),
                    gapPaint
                )
            }
        }

        // 4. Composite the layer back — transparent gaps reveal the parent background
        canvas.restoreToCount(sc)

        // 5. Redraw the thumb on top so it visually overlaps the gaps
        val t = thumb
        if (t != null) {
            val thumbSave = canvas.save()
            canvas.translate((paddingLeft - thumbOffset).toFloat(), paddingTop.toFloat())
            t.draw(canvas)
            canvas.restoreToCount(thumbSave)
        }
    }

    private fun drawHeatmap(canvas: Canvas) {
        if (heatmapEntries.isEmpty() || heatmapTotalDurationMillis <= 0) {
            return
        }

        val trackLeft = paddingLeft.toFloat()
        val trackWidth = (width - paddingLeft - paddingRight).toFloat()
        val baseY = height / 2f
        val maxHeight = baseY - 1

        if (maxHeight <= 0 || trackWidth <= 0) {
            return
        }

        val n = heatmapEntries.size
        val px = FloatArray(n)
        val py = FloatArray(n)
        for (i in 0 until n) {
            val e = heatmapEntries[i]
            val startFrac = e.startTimeMillis.toFloat() / heatmapTotalDurationMillis.toFloat()
            val endFrac = (e.startTimeMillis + e.durationMillis).toFloat() /
                heatmapTotalDurationMillis.toFloat()
            px[i] = trackLeft + (startFrac + endFrac) * 0.5f * trackWidth
            py[i] = baseY - e.heatIntensity.toFloat() * maxHeight
        }

        heatmapPath.reset()
        heatmapPath.moveTo(px[0], baseY)
        heatmapPath.lineTo(px[0], py[0])

        for (i in 0 until n - 1) {
            val midX = (px[i] + px[i + 1]) * 0.5f
            val midPy = (py[i] + py[i + 1]) * 0.5f
            heatmapPath.quadTo(px[i], py[i], midX, midPy)
        }

        heatmapPath.lineTo(px[n - 1], py[n - 1])
        heatmapPath.lineTo(px[n - 1], baseY)
        heatmapPath.close()

        canvas.drawPath(heatmapPath, heatmapPaint)
    }

    companion object {
        private const val GAP_WIDTH_DP = 2f
    }
}
