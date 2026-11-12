package com.s25.iphotos

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class EditParams(
    val exposure: Float = 0f,     // -100 a +100
    val brilliance: Float = 0f,   // -100 a +100 (stile iPhone)
    val highlights: Float = 0f,   // -100 a +100
    val shadows: Float = 0f,      // -100 a +100
    val contrast: Float = 0f,     // -100 a +100
    val saturation: Float = 0f,   // -100 a +100
    val warmth: Float = 0f,       // -100 a +100
    val sharpness: Float = 0f,    // 0 a +100
    val filterIndex: Int = 0,     // 0=Originale, 1=Vivido, 2=Vivido Caldo, 3=Drammatico, 4=Mono
    val rotation: Float = 0f,     // 0, 90, 180, 270
    val flipHorizontal: Boolean = false
)

object ImageEngine {

    val filterNames = listOf("Originale", "Vivido", "Caldo", "Drammatico", "Mono")

    fun applyEdits(source: Bitmap, params: EditParams): Bitmap {
        val matrix = Matrix().apply {
            if (params.rotation != 0f) postRotate(params.rotation)
            if (params.flipHorizontal) postScale(-1f, 1f, source.width / 2f, source.height / 2f)
        }
        val transformed = if (params.rotation != 0f || params.flipHorizontal) {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        } else {
            source
        }

        val width = transformed.width
        val height = transformed.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val cm = ColorMatrix()

        when (params.filterIndex) {
            1 -> { // Vivido
                val sat = ColorMatrix().apply { setSaturation(1.28f) }
                val con = createContrastMatrix(1.12f)
                cm.postConcat(sat)
                cm.postConcat(con)
            }
            2 -> { // Vivido Caldo
                val sat = ColorMatrix().apply { setSaturation(1.22f) }
                val warm = createWarmthMatrix(22f)
                cm.postConcat(sat)
                cm.postConcat(warm)
            }
            3 -> { // Drammatico
                val sat = ColorMatrix().apply { setSaturation(0.82f) }
                val con = createContrastMatrix(1.28f)
                cm.postConcat(sat)
                cm.postConcat(con)
            }
            4 -> { // Mono
                val mono = ColorMatrix().apply { setSaturation(0f) }
                val con = createContrastMatrix(1.18f)
                cm.postConcat(mono)
                cm.postConcat(con)
            }
        }

        val expScale = 2f.pow(params.exposure / 65f)
        cm.postConcat(ColorMatrix(floatArrayOf(
            expScale, 0f, 0f, 0f, 0f,
            0f, expScale, 0f, 0f, 0f,
            0f, 0f, expScale, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )))

        val contrastFactor = 1f + (params.contrast / 110f)
        cm.postConcat(createContrastMatrix(contrastFactor))

        val satFactor = max(0f, 1f + (params.saturation / 100f))
        val satMatrix = ColorMatrix().apply { setSaturation(satFactor) }
        cm.postConcat(satMatrix)

        if (params.warmth != 0f) {
            cm.postConcat(createWarmthMatrix(params.warmth * 0.45f))
        }

        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(transformed, 0f, 0f, paint)

        if (params.brilliance != 0f || params.shadows != 0f || params.highlights != 0f) {
            applyToneCurve(result, params.brilliance / 100f, params.shadows / 100f, params.highlights / 100f)
        }

        return result
    }

    private fun createContrastMatrix(contrast: Float): ColorMatrix {
        val translate = (-.5f * contrast + .5f) * 255f
        return ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
    }

    private fun createWarmthMatrix(warmth: Float): ColorMatrix {
        return ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, warmth,
            0f, 1f, 0f, 0f, warmth * 0.25f,
            0f, 0f, 1f, 0f, -warmth,
            0f, 0f, 0f, 1f, 0f
        ))
    }

    private fun applyToneCurve(bitmap: Bitmap, brilliance: Float, shadows: Float, highlights: Float) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val lut = IntArray(256)
        for (i in 0..255) {
            var v = i / 255f
            if (brilliance != 0f) {
                val midWeight = 4f * v * (1f - v)
                v += brilliance * 0.32f * midWeight
            }
            if (shadows != 0f) {
                val shadowWeight = (1f - v).pow(2.2f)
                v += shadows * 0.35f * shadowWeight
            }
            if (highlights != 0f) {
                val highWeight = v.pow(2.2f)
                v += highlights * 0.35f * highWeight
            }
            lut[i] = min(255, max(0, (v * 255f).toInt()))
        }

        for (idx in pixels.indices) {
            val c = pixels[idx]
            val a = (c ushr 24) and 0xFF
            val r = lut[(c ushr 16) and 0xFF]
            val g = lut[(c ushr 8) and 0xFF]
            val b = lut[c and 0xFF]
            pixels[idx] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    fun saveToGallery(context: Context, bitmap: Bitmap): Uri? {
        val filename = "iPhoto_S25_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/iPhotos")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null

        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
