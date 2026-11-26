package io.github.lyzio.ajnav.feature.charging.planner

import io.github.lyzio.ajnav.core.model.GeoPoint
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Small geodesy helpers (WGS84 sphere; plenty for route corridors of a few km). */
object GeoMath {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Compass bearing from [a] to [b], 0–360°, 0 = north. */
    fun bearingDeg(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Local flat projection in metres around [origin] (x east, y north). */
    internal fun toXY(p: GeoPoint, origin: GeoPoint): Pair<Double, Double> {
        val x = Math.toRadians(p.longitude - origin.longitude) * EARTH_RADIUS_M * cos(Math.toRadians(origin.latitude))
        val y = Math.toRadians(p.latitude - origin.latitude) * EARTH_RADIUS_M
        return x to y
    }

    internal const val DEG = PI / 180.0
}

/** Where a point lies relative to the route. */
data class RouteProjection(val distanceAlongM: Double, val offsetM: Double)

/** A route line with cumulative distances, so chargers and energy can be placed along it. */
class RouteGeometry(points: List<GeoPoint>) {

    val points: List<GeoPoint> = points.zipWithNext().filter { (a, b) -> a != b }.map { it.first } +
        listOfNotNull(points.lastOrNull())

    /** Cumulative distance from the start to each point. */
    val cumulativeM: DoubleArray = DoubleArray(this.points.size).also { arr ->
        for (i in 1 until this.points.size) arr[i] = arr[i - 1] + GeoMath.distanceM(this.points[i - 1], this.points[i])
    }

    val totalM: Double get() = cumulativeM.lastOrNull() ?: 0.0

    val start: GeoPoint get() = points.first()
    val end: GeoPoint get() = points.last()

    /** Closest point on the route to [p]: how far along, and how far off to the side. */
    fun locate(p: GeoPoint): RouteProjection {
        if (points.size < 2) return RouteProjection(0.0, points.firstOrNull()?.let { GeoMath.distanceM(it, p) } ?: 0.0)
        var bestOffset = Double.MAX_VALUE
        var bestAlong = 0.0
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val (bx, by) = GeoMath.toXY(points[i + 1], a)
            val (px, py) = GeoMath.toXY(p, a)
            val len2 = bx * bx + by * by
            val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
            val dx = px - t * bx
            val dy = py - t * by
            val off = sqrt(dx * dx + dy * dy)
            if (off < bestOffset) {
                bestOffset = off
                bestAlong = cumulativeM[i] + t * (cumulativeM[i + 1] - cumulativeM[i])
            }
        }
        return RouteProjection(bestAlong, bestOffset)
    }

    /** The point [distanceM] metres from the start (clamped to the route). */
    fun pointAt(distanceM: Double): GeoPoint {
        if (points.size < 2) return points.first()
        val d = distanceM.coerceIn(0.0, totalM)
        val i = indexBefore(d)
        val segLen = cumulativeM[i + 1] - cumulativeM[i]
        val t = if (segLen == 0.0) 0.0 else (d - cumulativeM[i]) / segLen
        val a = points[i]
        val b = points[i + 1]
        return GeoPoint(a.latitude + t * (b.latitude - a.latitude), a.longitude + t * (b.longitude - a.longitude))
    }

    /** Travel direction at [distanceM]. */
    fun bearingAt(distanceM: Double): Double {
        if (points.size < 2) return 0.0
        val i = indexBefore(distanceM.coerceIn(0.0, totalM))
        return GeoMath.bearingDeg(points[i], points[i + 1])
    }

    /** Points sampled every [stepM] metres, start and end included. */
    fun sample(stepM: Double): List<Double> {
        if (totalM <= 0.0) return listOf(0.0)
        val out = ArrayList<Double>()
        var d = 0.0
        while (d < totalM) {
            out += d
            d += stepM
        }
        out += totalM
        return out
    }

    private fun indexBefore(d: Double): Int {
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (cumulativeM[mid] <= d) lo = mid else hi = mid
        }
        return lo
    }
}

/** Google "encoded polyline" (precision 5), as Open Charge Map expects, plus simplification. */
object PolylineCodec {
    fun encode(points: List<GeoPoint>): String {
        val sb = StringBuilder()
        var lastLat = 0L
        var lastLon = 0L
        for (p in points) {
            val lat = Math.round(p.latitude * 1e5)
            val lon = Math.round(p.longitude * 1e5)
            encodeValue(lat - lastLat, sb)
            encodeValue(lon - lastLon, sb)
            lastLat = lat
            lastLon = lon
        }
        return sb.toString()
    }

    private fun encodeValue(value: Long, sb: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            sb.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        sb.append((v + 63).toInt().toChar())
    }

    /** Douglas–Peucker: keeps the shape within [toleranceM] metres. */
    fun simplify(points: List<GeoPoint>, toleranceM: Double): List<GeoPoint> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            var maxDist = 0.0
            var index = -1
            val origin = points[first]
            val (bx, by) = GeoMath.toXY(points[last], origin)
            val len2 = bx * bx + by * by
            for (i in first + 1 until last) {
                val (px, py) = GeoMath.toXY(points[i], origin)
                val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
                val dx = px - t * bx
                val dy = py - t * by
                val d = sqrt(dx * dx + dy * dy)
                if (d > maxDist) {
                    maxDist = d
                    index = i
                }
            }
            if (index >= 0 && maxDist > toleranceM) {
                keep[index] = true
                stack.addLast(first to index)
                stack.addLast(index to last)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }
}
