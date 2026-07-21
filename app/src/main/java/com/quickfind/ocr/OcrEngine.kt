package com.quickfind.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * OCR 文字识别引擎
 * 使用 Google ML Kit 进行文字识别，支持中文和英文
 */
class OcrEngine {

    // 使用中文识别器（同时支持中文和拉丁字符）
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    /**
     * 识别图片中的文字
     * @param bitmap 待识别的图片
     * @return 识别出的文字内容
     */
    suspend fun recognizeText(bitmap: Bitmap): String {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()
            result.text
        } catch (e: Exception) {
            "识别失败: ${e.message}"
        }
    }

    /**
     * 识别图片中的文字（带置信度信息）
     * @param bitmap 待识别的图片
     * @return Pair(识别文字, 平均置信度)
     */
    suspend fun recognizeTextWithConfidence(bitmap: Bitmap): Pair<String, Float> {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()

            val text = result.text
            val confidence = if (result.textBlocks.isEmpty()) {
                0f
            } else {
                result.textBlocks.flatMap { it.lines }
                    .flatMap { it.elements }
                    .map { it.confidence ?: 0f }
                    .average().toFloat()
            }

            Pair(text, confidence)
        } catch (e: Exception) {
            Pair("识别失败: ${e.message}", 0f)
        }
    }

    /**
     * 释放资源
     */
    fun close() {
        recognizer.close()
    }
}
