package com.example.albumlight

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Extracts the perceptually dominant, vibrant colour from album artwork.
 *
 * Port of the Python pick_dominant_rgb() logic:
 *  - Boost saturation by 1.75×
 *  - Discard near-black, near-white, and desaturated pixels
 *  - Score remaining pixels by saturation × brightness-fitness
 *  - Average the top 20 %
 */
object ColorExtractor {

    data class Rgb(val r: Int, val g: Int, val b: Int)

    /**
     * @param source  Any-size bitmap (will be scaled to 100×100 internally).
     * @param minSaturation Tuya saturation floor (0–1000) read from prefs;
     *                      used only in the HSV→Tuya conversion, NOT here.
     */
    fun pickDominantRgb(source: Bitmap): Rgb {
        // Scale down — 100 px is plenty and keeps the loop fast
        val scaled = Bitmap.createScaledBitmap(source, 100, 100, true)

        // Boost saturation 1.75× (mirrors Pillow ImageEnhance.Color(img).enhance(1.75))
        val enhanced = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().also { it.setSaturation(1.75f) }
        Canvas(enhanced).drawBitmap(scaled, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(cm)
        })
        if (scaled !== source) scaled.recycle()

        val pixels = IntArray(100 * 100)
        enhanced.getPixels(pixels, 0, 100, 0, 0, 100, 100)
        enhanced.recycle()

        val scored = ArrayList<Pair<Float, Rgb>>(pixels.size)
        val hsv = FloatArray(3)

        for (pixel in pixels) {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            Color.RGBToHSV(r, g, b, hsv)
            val v = hsv[2]   // 0..1
            val s = hsv[1]   // 0..1

            // Skip near-blacks, near-whites, and grays (mirrors Python thresholds)
            if (v < 0.15f) continue
            if (v > 0.95f && s < 0.1f) continue
            if (s < 0.15f) continue

            // Score: high saturation + mid brightness wins; penalise extremes of brightness
            val brightnessPenalty = 1f - abs(v - 0.65f) * 1.2f
            val score = s * max(brightnessPenalty, 0.1f)
            scored.add(score to Rgb(r, g, b))
        }

        if (scored.isEmpty()) {
            // Fallback: simple average of all pixels
            var sumR = 0L; var sumG = 0L; var sumB = 0L
            for (p in pixels) { sumR += Color.red(p); sumG += Color.green(p); sumB += Color.blue(p) }
            val n = pixels.size
            return Rgb((sumR / n).toInt(), (sumG / n).toInt(), (sumB / n).toInt())
        }

        // Take top 20 % by score and average them
        scored.sortByDescending { it.first }
        val topN = scored.subList(0, max(1, scored.size / 5))

        val avgR = topN.sumOf { it.second.r }.toFloat() / topN.size
        val avgG = topN.sumOf { it.second.g }.toFloat() / topN.size
        val avgB = topN.sumOf { it.second.b }.toFloat() / topN.size

        return Rgb(avgR.roundToInt(), avgG.roundToInt(), avgB.roundToInt())
    }

    // ── Tuya conversions ─────────────────────────────────────────────────────

    data class TuyaHsv(val h: Int, val s: Int, val v: Int, val hex: String)

    /**
     * Convert RGB → Tuya HSV integers and the 12-hex-char colour string.
     *
     * Tuya ranges: H 0–360, S 0–1000, V 0–1000.
     */
    fun rgbToTuyaHsv(rgb: Rgb, brightnessFixed: Int, minSaturation: Int): TuyaHsv {
        val hsv = FloatArray(3)
        Color.RGBToHSV(rgb.r, rgb.g, rgb.b, hsv)

        val h = hsv[0].roundToInt().coerceIn(0, 360)
        val s = (hsv[1] * 1000).roundToInt().coerceIn(minSaturation, 1000)
        val v = brightnessFixed.coerceIn(10, 1000)
        val hex = "%04x%04x%04x".format(h, s, v)
        return TuyaHsv(h, s, v, hex)
    }

    /** Short-path version for transitions where H/S/V are already in Tuya units. */
    fun tuyaHex(h: Int, s: Int, v: Int, minSaturation: Int): String {
        val hc = h.coerceIn(0, 360)
        val sc = s.coerceIn(minSaturation, 1000)
        val vc = v.coerceIn(10, 1000)
        return "%04x%04x%04x".format(hc, sc, vc)
    }

    /**
     * Interpolate hue along the shortest arc (handles 350→10 wrapping).
     */
    fun interpolateHue(startH: Int, endH: Int, t: Float): Int {
        val s = startH.toFloat()
        val e = endH.toFloat()
        val delta = ((e - s + 180f) % 360f) - 180f
        return ((s + delta * t + 360f) % 360f).roundToInt()
    }
}
