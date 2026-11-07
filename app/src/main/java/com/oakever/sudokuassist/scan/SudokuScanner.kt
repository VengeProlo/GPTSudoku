package com.oakever.sudokuassist.scan

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Reads each cell independently. Uncertain or failed reads are left blank for manual correction. */
class SudokuScanner {
    fun scan(boardBitmap: Bitmap, onFinished: (IntArray) -> Unit) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val normalized = try {
            Bitmap.createScaledBitmap(boardBitmap, 900, 900, true)
        } catch (_: Exception) {
            recognizer.close()
            onFinished(IntArray(81))
            return
        }
        val digits = IntArray(81)
        var index = 0
        var finished = false

        fun finish() {
            if (finished) return
            finished = true
            recognizer.close()
            if (normalized !== boardBitmap && !normalized.isRecycled) normalized.recycle()
            onFinished(digits)
        }

        fun readNext() {
            if (index >= 81) {
                finish()
                return
            }
            val current = index
            val row = current / 9
            val col = current % 9
            val cell = try {
                // The interior crop avoids grid lines; ambiguous cells remain blank.
                Bitmap.createBitmap(normalized, col * 100 + 19, row * 100 + 19, 62, 62)
            } catch (_: Exception) {
                index++
                readNext()
                return
            }

            try {
                recognizer.process(InputImage.fromBitmap(cell, 0))
                    .addOnSuccessListener { result ->
                        val found = Regex("[1-9]").findAll(result.text)
                            .mapNotNull { it.value.toIntOrNull() }
                            .toList()
                        if (found.size == 1) digits[current] = found[0]
                    }
                    .addOnCompleteListener {
                        if (!cell.isRecycled) cell.recycle()
                        index++
                        readNext()
                    }
            } catch (_: Exception) {
                if (!cell.isRecycled) cell.recycle()
                index++
                readNext()
            }
        }

        readNext()
    }
}
