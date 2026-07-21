package com.quickfind.ocr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * 屏幕截图管理器
 * 使用 MediaProjection API 实现屏幕截图功能
 */
class ScreenCaptureManager(private val context: Context) {

    companion object {
        const val REQUEST_CODE_SCREEN_CAPTURE = 1001
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private var captureCallback: ((Bitmap?) -> Unit)? = null
    private var hasCaptured = false

    /**
     * 发起屏幕截图权限请求
     * 应在 Activity 中调用，并在 onActivityResult 中处理结果
     */
    fun requestScreenCapture(activity: Activity) {
        val projectionManager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        activity.startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_CODE_SCREEN_CAPTURE
        )
    }

    /**
     * 处理权限请求结果并开始截图
     * 应在 Activity.onActivityResult 中调用
     *
     * @param resultCode 请求结果码
     * @param data       请求数据
     * @param callback   截图完成回调，返回 Bitmap 或 null
     */
    fun startCapture(resultCode: Int, data: Intent?, callback: (Bitmap?) -> Unit) {
        this.captureCallback = callback
        this.hasCaptured = false

        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data!!)

        // 获取屏幕参数
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val screenWidth: Int
        val screenHeight: Int
        val screenDensity: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            val config = context.resources.configuration
            screenDensity = when (config.densityDpi) {
                in 0..119 -> DisplayMetrics.DENSITY_LOW
                in 120..159 -> DisplayMetrics.DENSITY_MEDIUM
                in 160..239 -> DisplayMetrics.DENSITY_HIGH
                else -> DisplayMetrics.DENSITY_XXHIGH
            }
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
        }

        // 创建后台线程处理图像
        handlerThread = HandlerThread("ScreenCapture").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        // 创建 ImageReader
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888, 2
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            if (hasCaptured) return@setOnImageAvailableListener

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            try {
                hasCaptured = true
                val bitmap = imageToBitmap(image)
                captureCallback?.invoke(bitmap)
            } catch (e: Exception) {
                captureCallback?.invoke(null)
            } finally {
                image.close()
                stopCapture()
            }
        }, handler)

        // 创建虚拟显示器
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, handler
        )
    }

    /**
     * 将 Image 转换为 Bitmap
     */
    private fun imageToBitmap(image: android.media.Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        // 裁剪掉 padding 部分
        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height).also {
                bitmap.recycle()
            }
        } else {
            bitmap
        }
    }

    /**
     * 停止截图并释放资源
     */
    fun stopCapture() {
        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        handlerThread?.quitSafely()
        handlerThread = null

        mediaProjection?.stop()
        mediaProjection = null
    }

    /**
     * 释放所有资源
     */
    fun release() {
        stopCapture()
        captureCallback = null
    }
}
