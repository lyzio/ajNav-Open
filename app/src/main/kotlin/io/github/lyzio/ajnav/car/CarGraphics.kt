package io.github.lyzio.ajnav.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.car.app.model.CarIcon
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.lyzio.ajnav.R
import io.github.lyzio.ajnav.feature.navigation.session.LaneUi
import kotlin.math.cos
import kotlin.math.sin

/**
 * Small bitmaps for the car screen: the turn arrow, the lane arrows and the speed badge.
 * Android Auto shows only what an app hands it as images, so these are drawn here.
 */
internal object CarGraphics {

    private const val ICE = Color.WHITE
    private val MUTED = Color.parseColor("#7E8A93")
    private val TEAL = Color.parseColor("#4FB3A9")
    private val CORAL = Color.parseColor("#E8735A")
    private val CARD = Color.parseColor("#1B252D")

    /** Turn arrow for the maneuver card. */
    fun maneuverIcon(type: String?, modifier: String?, drivingSide: String?): CarIcon {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val leftHand = drivingSide == "left"
        when {
            type == "arrive" -> drawArrive(canvas, size.toFloat(), ICE)
            type in ROUNDABOUT_TYPES -> drawRoundabout(canvas, size.toFloat(), angleFor(modifier), ICE)
            modifier == "uturn" -> drawUTurn(canvas, size.toFloat(), toRight = leftHand, color = ICE)
            else -> drawArrow(canvas, size.toFloat(), angleFor(modifier), ICE)
        }
        return bmp.toCarIcon()
    }

    /** One image with all lanes side by side; recommended lanes white, the rest grey. */
    fun lanesIcon(lanes: List<LaneUi>, drivingSide: String?): CarIcon {
        val laneW = 64
        val h = 64
        val bmp = Bitmap.createBitmap(laneW * lanes.size, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val leftHand = drivingSide == "left"
        lanes.forEachIndexed { i, lane ->
            val dir = (if (lane.isActive) lane.activeDirection else null) ?: lane.directions.firstOrNull() ?: "straight"
            val color = if (lane.isActive) ICE else MUTED
            canvas.save()
            canvas.translate((i * laneW).toFloat(), 0f)
            if (dir == "uturn") {
                drawUTurn(canvas, h.toFloat(), toRight = leftHand, color = color)
            } else {
                drawArrow(canvas, h.toFloat(), angleFor(dir), color)
            }
            canvas.restore()
        }
        return bmp.toCarIcon()
    }

    /** Speed and speed-limit badge drawn on the car map. */
    fun speedBadge(speedKmh: Int?, limitKmh: Int?, density: Float): Bitmap {
        val d = density.coerceAtLeast(1f)
        val circle = 56f * d
        val gap = 8f * d
        val hasLimit = limitKmh != null
        val w = (circle + if (hasLimit) gap + circle else 0f).toInt()
        val h = circle.toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        // Current speed: dark card, coral when over the limit.
        val over = speedKmh != null && limitKmh != null && speedKmh > limitKmh + 2
        fill.color = CARD
        canvas.drawRoundRect(RectF(0f, 0f, circle, circle), 14f * d, 14f * d, fill)
        text.color = if (over) CORAL else ICE
        text.textSize = 24f * d
        canvas.drawText(speedKmh?.toString() ?: "–", circle / 2, circle / 2 + 2f * d, text)
        text.textSize = 10f * d
        text.color = MUTED
        canvas.drawText("km/h", circle / 2, circle / 2 + 16f * d, text)

        // Speed limit: classic white sign with red ring.
        if (limitKmh != null) {
            val cx = circle + gap + circle / 2
            val cy = circle / 2
            fill.color = Color.WHITE
            canvas.drawCircle(cx, cy, circle / 2, fill)
            fill.color = Color.parseColor("#D32F2F")
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = 6f * d
            canvas.drawCircle(cx, cy, circle / 2 - 3f * d, fill)
            fill.style = Paint.Style.FILL
            text.color = Color.BLACK
            text.textSize = 22f * d
            canvas.drawText(limitKmh.toString(), cx, cy + 8f * d, text)
        }
        return bmp
    }

    /**
     * Full-screen logo screen for the car (N2): dark LyzLarm background, the ajNav logo and name.
     * The speed badge is a separate widget drawn on top.
     */
    fun logoScreen(context: Context, width: Int, height: Int, density: Float): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#0F1A1C"))
        val d = density.coerceAtLeast(1f)
        val size = (minOf(width, height) * 0.42f).toInt()
        val left = (width - size) / 2
        val top = (height * 0.40f - size / 2f).toInt().coerceAtLeast(0)
        ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)?.let { logo ->
            logo.setBounds(left, top, left + size, top + size)
            logo.draw(canvas)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EEF3F3")
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 34f * d
        }
        canvas.drawText("ajNav", width / 2f, top + size + 28f * d, text)
        return bmp
    }

    // ---------------------------------------------------------------- drawing

    private val ROUNDABOUT_TYPES = setOf("roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary")

    /** Arrow angle in degrees: 0 = straight up, positive = to the right. */
    private fun angleFor(modifier: String?): Float = when (modifier) {
        "slight right" -> 45f
        "right" -> 90f
        "sharp right" -> 135f
        "slight left" -> -45f
        "left" -> -90f
        "sharp left" -> -135f
        else -> 0f
    }

    private fun strokePaint(size: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = size * 0.11f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private fun drawArrow(canvas: Canvas, s: Float, angleDeg: Float, color: Int) {
        val cx = s / 2
        val cy = s * 0.58f
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = sin(rad).toFloat()
        val dy = -cos(rad).toFloat()
        val len = if (angleDeg == 0f) s * 0.22f else s * 0.24f
        val ex = cx + dx * len
        val ey = cy + dy * len
        val path = Path().apply {
            moveTo(cx, s * 0.92f)
            lineTo(cx, cy)
            lineTo(ex, ey)
        }
        canvas.drawPath(path, strokePaint(s, color))
        drawHead(canvas, s, ex, ey, dx, dy, color)
    }

    private fun drawHead(canvas: Canvas, s: Float, x: Float, y: Float, dx: Float, dy: Float, color: Int) {
        val h = s * 0.16f
        val w = s * 0.14f
        val head = Path().apply {
            moveTo(x + dx * h, y + dy * h)
            lineTo(x - dy * w, y + dx * w)
            lineTo(x + dy * w, y - dx * w)
            close()
        }
        canvas.drawPath(head, fillPaint(color))
    }

    private fun drawUTurn(canvas: Canvas, s: Float, toRight: Boolean, color: Int) {
        val startX = if (toRight) s * 0.32f else s * 0.68f
        val endX = if (toRight) s * 0.68f else s * 0.32f
        val top = s * 0.40f
        val r = kotlin.math.abs(endX - startX) / 2
        val cxArc = (startX + endX) / 2
        val path = Path().apply {
            moveTo(startX, s * 0.92f)
            lineTo(startX, top)
            arcTo(RectF(cxArc - r, top - r, cxArc + r, top + r), 180f, if (toRight) 180f else -180f)
            lineTo(endX, s * 0.62f)
        }
        canvas.drawPath(path, strokePaint(s, color))
        drawHead(canvas, s, endX, s * 0.62f, 0f, 1f, color)
    }

    private fun drawArrive(canvas: Canvas, s: Float, color: Int) {
        canvas.drawLine(s / 2, s * 0.92f, s / 2, s * 0.55f, strokePaint(s, color))
        canvas.drawCircle(s / 2, s * 0.36f, s * 0.18f, fillPaint(TEAL))
        canvas.drawCircle(s / 2, s * 0.36f, s * 0.07f, fillPaint(color))
    }

    private fun drawRoundabout(canvas: Canvas, s: Float, angleDeg: Float, color: Int) {
        val cx = s / 2
        val cy = s * 0.50f
        val r = s * 0.17f
        val ring = strokePaint(s, color).apply { strokeWidth = s * 0.08f; alpha = 160 }
        canvas.drawCircle(cx, cy, r, ring)
        canvas.drawLine(cx, s * 0.94f, cx, cy + r, strokePaint(s, color))
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = sin(rad).toFloat()
        val dy = -cos(rad).toFloat()
        val sx = cx + dx * r
        val sy = cy + dy * r
        val ex = cx + dx * (r + s * 0.14f)
        val ey = cy + dy * (r + s * 0.14f)
        canvas.drawLine(sx, sy, ex, ey, strokePaint(s, color))
        drawHead(canvas, s, ex, ey, dx, dy, color)
    }

    private fun Bitmap.toCarIcon(): CarIcon = CarIcon.Builder(IconCompat.createWithBitmap(this)).build()
}
