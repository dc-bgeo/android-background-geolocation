package dev.bgeo.example.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable

/**
 * Small filled-circle-with-border icon — the dot styling `components/
 * DotMarker.tsx` (RN) and `Components/DotMarker.swift` (iOS) both draw, used
 * here as an osmdroid `Marker` icon rather than hosted view content.
 *
 * Neither reference's actual machinery applies on this platform: RN needs a
 * `redrawKey`-driven `tracksViewChanges` dance because `react-native-maps` on
 * Android snapshots a marker's child View to a bitmap once and only
 * re-snapshots on demand; iOS hosts a live SwiftUI view per annotation via
 * `UIHostingController` and just reassigns `rootView`. osmdroid's `Marker`
 * renders a plain `Drawable` — there is nothing to snapshot and nothing to
 * re-host, so this is just a bitmap factory: build a new [drawable] (cheap;
 * these are a few dozen pixels square) whenever a dot's size/colour needs to
 * change, same as every other overlay this screen rebuilds through
 * `MapRebuild`'s decision.
 */
object DotMarker {
    fun drawable(
        context: Context,
        diameterDp: Float,
        fillColor: Int,
        borderColor: Int,
        borderWidthDp: Float,
    ): BitmapDrawable {
        val density = context.resources.displayMetrics.density
        val diameterPx = (diameterDp * density).toInt().coerceAtLeast(1)
        val borderPx = borderWidthDp * density
        val bitmap = Bitmap.createBitmap(diameterPx, diameterPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = diameterPx / 2f
        val fillRadius = (center - borderPx / 2f).coerceAtLeast(0f)

        canvas.drawCircle(center, center, fillRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
        })
        if (borderPx > 0f) {
            canvas.drawCircle(center, center, fillRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = borderColor
                style = Paint.Style.STROKE
                strokeWidth = borderPx
            })
        }
        return BitmapDrawable(context.resources, bitmap)
    }
}
