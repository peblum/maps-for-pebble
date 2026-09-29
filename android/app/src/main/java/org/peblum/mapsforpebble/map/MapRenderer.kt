package org.peblum.mapsforpebble.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import kotlin.math.hypot

class MapRenderer {
    class Spec(
        val width: Int,
        val height: Int,
        val lat: Double,
        val lon: Double,
        val headingDeg: Double,
        val zoom: Double,
        val tiles: List<TileData>,
        val preview: Preview?,
        val hasFix: Boolean,
        val noFixAdvice: List<String> = emptyList(),
        val markerFraction: Float = MARKER_FRACTION,
    )

    private val fill =
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = false
        }
    private val stroke =
        Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = false
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val text =
        Paint().apply {
            isAntiAlias = false
            isSubpixelText = false
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f
        }
    private val path = Path()
    private val matrix = Matrix()

    fun render(spec: Spec): Bitmap {
        val bitmap = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(FrameEncoder.COLOR_WHITE)
        if (!spec.hasFix) {
            drawCentered(canvas, listOf("Waiting for GPS") + spec.noFixAdvice, spec)
            return bitmap
        }
        val pixelsPerUnit = WebMercator.pixelsPerUnit(spec.zoom).toFloat()
        val centerX = WebMercator.toWorldX(spec.lon)
        val centerY = WebMercator.toWorldY(spec.lat)
        val markerX = spec.width / 2f
        val markerY = spec.height * spec.markerFraction

        matrix.reset()
        matrix.postScale(pixelsPerUnit, pixelsPerUnit)
        matrix.postRotate(-spec.headingDeg.toFloat())
        matrix.postTranslate(markerX, markerY)

        if (spec.tiles.isEmpty()) {
            drawCentered(canvas, listOf("No map data"), spec)
        }
        canvas.save()
        canvas.concat(matrix)
        for (tile in spec.tiles) {
            canvas.save()
            canvas.translate((tile.originX - centerX).toFloat(), (tile.originY - centerY).toFloat())
            drawTile(canvas, tile, pixelsPerUnit, spec.zoom)
            canvas.restore()
        }
        spec.preview?.let { drawPreview(canvas, it, pixelsPerUnit) }
        canvas.restore()

        spec.preview?.let { drawTurnMarker(canvas, it, spec) }
        drawPositionMarker(canvas, markerX, markerY)
        drawScaleBar(canvas, spec)
        drawCompass(canvas, spec)
        spec.preview?.currentRoad?.let { drawRoadLabel(canvas, it, spec) }
        return bitmap
    }

    private fun drawTile(
        canvas: Canvas,
        tile: TileData,
        pixelsPerUnit: Float,
        zoom: Double,
    ) {
        val detail = detailFor(zoom)
        fill.color = FrameEncoder.COLOR_GRAY
        for (area in tile.water) {
            path.reset()
            path.fillType = Path.FillType.EVEN_ODD
            for (ring in area.rings) addPolyline(ring, close = true)
            canvas.drawPath(path, fill)
        }
        stroke.pathEffect = null
        stroke.color = FrameEncoder.COLOR_GRAY
        stroke.strokeWidth = 3f / pixelsPerUnit
        for (line in tile.waterways) drawPolyline(canvas, line)

        if (detail >= 1) {
            stroke.color = FrameEncoder.COLOR_BLACK
            stroke.strokeWidth = 1f / pixelsPerUnit
            stroke.pathEffect = DashPathEffect(floatArrayOf(4f / pixelsPerUnit, 4f / pixelsPerUnit), 0f)
            for (road in tile.roads) if (road.kind == RoadKind.RAIL) drawPolyline(canvas, road.points)
            stroke.pathEffect = null
        }

        for (road in tile.roads) {
            val width = casingWidth(road.kind, detail)
            if (width == 0f) continue
            stroke.color = FrameEncoder.COLOR_BLACK
            stroke.strokeWidth = width / pixelsPerUnit
            drawPolyline(canvas, road.points)
        }
        for (road in tile.roads) {
            val width = coreWidth(road.kind, detail)
            if (width == 0f) continue
            stroke.color = FrameEncoder.COLOR_WHITE
            stroke.strokeWidth = width / pixelsPerUnit
            drawPolyline(canvas, road.points)
        }
        stroke.color = FrameEncoder.COLOR_BLACK
        for (road in tile.roads) {
            val width = thinWidth(road.kind, detail)
            if (width == 0f) continue
            stroke.strokeWidth = width / pixelsPerUnit
            drawPolyline(canvas, road.points)
        }
    }

    private fun detailFor(zoom: Double): Int =
        when {
            zoom >= 17.0 -> 3
            zoom >= 16.0 -> 2
            zoom >= 15.0 -> 1
            else -> 0
        }

    private fun casingWidth(
        kind: RoadKind,
        detail: Int,
    ): Float =
        when (kind) {
            RoadKind.MOTORWAY -> floatArrayOf(5f, 6f, 8f, 9f)[detail]
            RoadKind.PRIMARY -> floatArrayOf(4f, 5f, 7f, 8f)[detail]
            RoadKind.SECONDARY -> floatArrayOf(3f, 4f, 6f, 7f)[detail]
            RoadKind.MINOR -> floatArrayOf(1f, 3f, 5f, 6f)[detail]
            RoadKind.SERVICE -> floatArrayOf(0f, 0f, 3f, 4f)[detail]
            else -> 0f
        }

    private fun coreWidth(
        kind: RoadKind,
        detail: Int,
    ): Float =
        when (kind) {
            RoadKind.MOTORWAY -> floatArrayOf(3f, 4f, 4f, 5f)[detail]
            RoadKind.PRIMARY -> floatArrayOf(2f, 3f, 3f, 4f)[detail]
            RoadKind.SECONDARY -> floatArrayOf(1f, 2f, 2f, 3f)[detail]
            RoadKind.MINOR -> floatArrayOf(0f, 1f, 1f, 2f)[detail]
            RoadKind.SERVICE -> floatArrayOf(0f, 0f, 1f, 2f)[detail]
            else -> 0f
        }

    private fun thinWidth(
        kind: RoadKind,
        detail: Int,
    ): Float =
        when (kind) {
            RoadKind.PATH, RoadKind.STEPS -> floatArrayOf(0f, 0f, 1f, 1f)[detail]
            RoadKind.CYCLEWAY -> floatArrayOf(0f, 1f, 2f, 2f)[detail]
            else -> 0f
        }

    private fun drawPreview(
        canvas: Canvas,
        preview: Preview,
        pixelsPerUnit: Float,
    ) {
        stroke.pathEffect = null
        stroke.color = FrameEncoder.COLOR_ACCENT
        stroke.strokeWidth = 4f / pixelsPerUnit
        for (line in preview.nextStreet) drawPolyline(canvas, line)
        if (preview.path.size >= 4) {
            stroke.strokeWidth = 5f / pixelsPerUnit
            drawPolyline(canvas, preview.path)
        }
        if (preview.afterTurn.size >= 4) {
            stroke.strokeWidth = 5f / pixelsPerUnit
            drawPolyline(canvas, preview.afterTurn)
        }
    }

    private fun drawTurnMarker(
        canvas: Canvas,
        preview: Preview,
        spec: Spec,
    ) {
        if (!preview.hasTurn) return
        val point = floatArrayOf(preview.turnX, preview.turnY)
        matrix.mapPoints(point)
        val margin = 9f
        val inside = point[0] in margin..(spec.width - margin) && point[1] in margin..(spec.height - margin)
        val x = point[0].coerceIn(margin, spec.width - margin)
        val y = point[1].coerceIn(margin, spec.height - margin)
        if (inside) {
            fill.color = FrameEncoder.COLOR_BLACK
            canvas.drawCircle(x, y, 8f, fill)
            fill.color = FrameEncoder.COLOR_ACCENT
            canvas.drawCircle(x, y, 6f, fill)
            fill.color = FrameEncoder.COLOR_WHITE
            canvas.drawCircle(x, y, 2f, fill)
        } else {
            stroke.pathEffect = null
            stroke.color = FrameEncoder.COLOR_ACCENT
            stroke.strokeWidth = 3f
            canvas.drawCircle(x, y, 6f, stroke)
        }
    }

    private fun drawPositionMarker(
        canvas: Canvas,
        x: Float,
        y: Float,
    ) {
        path.reset()
        path.fillType = Path.FillType.WINDING
        path.moveTo(x, y - 10f)
        path.lineTo(x - 8f, y + 8f)
        path.lineTo(x, y + 4f)
        path.lineTo(x + 8f, y + 8f)
        path.close()
        stroke.pathEffect = null
        stroke.color = FrameEncoder.COLOR_WHITE
        stroke.strokeWidth = 4f
        canvas.drawPath(path, stroke)
        fill.color = FrameEncoder.COLOR_BLACK
        canvas.drawPath(path, fill)
    }

    private fun drawScaleBar(
        canvas: Canvas,
        spec: Spec,
    ) {
        val metersPerPixel = WebMercator.metersPerPixel(spec.lat, spec.zoom)
        val meters = SCALE_STEPS.lastOrNull { it / metersPerPixel <= 64.0 } ?: SCALE_STEPS.first()
        val pixels = (meters / metersPerPixel).toFloat()
        val left = 6f
        val baseline = spec.height - 6f
        stroke.pathEffect = null
        stroke.color = FrameEncoder.COLOR_BLACK
        stroke.strokeWidth = 2f
        stroke.strokeCap = Paint.Cap.BUTT
        canvas.drawLine(left, baseline, left + pixels, baseline, stroke)
        canvas.drawLine(left + 1f, baseline - 4f, left + 1f, baseline, stroke)
        canvas.drawLine(left + pixels - 1f, baseline - 4f, left + pixels - 1f, baseline, stroke)
        stroke.strokeCap = Paint.Cap.ROUND
        val label = if (meters >= 1000) "${meters / 1000} km" else "$meters m"
        text.textSize = 11f
        text.color = FrameEncoder.COLOR_BLACK
        drawLabel(canvas, label, left, baseline - 7f, spec.width.toFloat())
    }

    private fun drawCompass(
        canvas: Canvas,
        spec: Spec,
    ) {
        val cx = spec.width - 13f
        val cy = 13f
        fill.color = FrameEncoder.COLOR_WHITE
        canvas.drawCircle(cx, cy, 10f, fill)
        stroke.pathEffect = null
        stroke.color = FrameEncoder.COLOR_BLACK
        stroke.strokeWidth = 1f
        canvas.drawCircle(cx, cy, 10f, stroke)
        canvas.save()
        canvas.rotate(-spec.headingDeg.toFloat(), cx, cy)
        path.reset()
        path.moveTo(cx, cy - 8f)
        path.lineTo(cx - 4f, cy + 3f)
        path.lineTo(cx + 4f, cy + 3f)
        path.close()
        fill.color = FrameEncoder.COLOR_BLACK
        canvas.drawPath(path, fill)
        canvas.restore()
    }

    private fun drawRoadLabel(
        canvas: Canvas,
        name: String,
        spec: Spec,
    ) {
        text.textSize = 12f
        text.color = FrameEncoder.COLOR_BLACK
        drawLabel(canvas, name, 4f, 4f + 11f, spec.width - 30f)
    }

    private fun drawLabel(
        canvas: Canvas,
        value: String,
        x: Float,
        baseline: Float,
        maxWidth: Float,
    ) {
        var label = value
        while (label.length > 4 && text.measureText(label) > maxWidth - 6f) {
            label = label.dropLast(2).trimEnd() + "…"
        }
        val bounds = Rect()
        text.getTextBounds(label, 0, label.length, bounds)
        fill.color = FrameEncoder.COLOR_WHITE
        canvas.drawRect(x - 2f, baseline + bounds.top - 2f, x + bounds.right + 4f, baseline + bounds.bottom + 2f, fill)
        canvas.drawText(label, x, baseline, text)
    }

    private fun drawCentered(
        canvas: Canvas,
        lines: List<String>,
        spec: Spec,
    ) {
        text.color = FrameEncoder.COLOR_BLACK
        val headingSize = 14f
        val adviceSize = 12f
        val adviceGap = 6f
        val blockHeight = headingSize + (lines.size - 1) * (adviceSize + 2f) + if (lines.size > 1) adviceGap else 0f
        var baseline = (spec.height - blockHeight) / 2f + headingSize
        lines.forEachIndexed { index, line ->
            text.textSize = if (index == 0) headingSize else adviceSize
            if (index == 1) baseline += adviceGap
            canvas.drawText(line, (spec.width - text.measureText(line)) / 2f, baseline, text)
            baseline += adviceSize + 2f
        }
    }

    private fun addPolyline(
        points: FloatArray,
        close: Boolean,
    ) {
        if (points.size < 4) return
        path.moveTo(points[0], points[1])
        var i = 2
        while (i + 1 < points.size) {
            path.lineTo(points[i], points[i + 1])
            i += 2
        }
        if (close) path.close()
    }

    private fun drawPolyline(
        canvas: Canvas,
        points: FloatArray,
    ) {
        if (points.size < 4) return
        path.reset()
        addPolyline(points, close = false)
        canvas.drawPath(path, stroke)
    }

    companion object {
        const val MARKER_FRACTION = 0.7f
        private val SCALE_STEPS = intArrayOf(5, 10, 20, 50, 100, 200, 500, 1000, 2000)

        fun distanceToTop(
            height: Int,
            markerFraction: Float = MARKER_FRACTION,
        ): Float = height * markerFraction - 16f

        fun screenRadiusPixels(
            width: Int,
            height: Int,
            markerFraction: Float = MARKER_FRACTION,
        ): Float = hypot(width / 2.0, maxOf(height * markerFraction, height * (1 - markerFraction)).toDouble()).toFloat()
    }
}
