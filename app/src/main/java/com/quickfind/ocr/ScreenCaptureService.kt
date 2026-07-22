package com.quickfind.ocr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "screen_capture_channel"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "ScreenCaptureService"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var hasCaptured = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 从静态字段获取数据
        val resultCode = MainActivity.pendingResultCode
        val data = MainActivity.pendingResultData

        if (resultCode == -1 || data == null) {
            Log.e(TAG, "No pending capture data")
            stopSelf()
            return START_NOT_STICKY
        }

        // 清理静态字段
        MainActivity.pendingResultCode = -1
        MainActivity.pendingResultData = null

        // 尝试启动前台服务
        try {
            createNotificationChannel()
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}")
            // 降级：尝试普通通知
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification())
            } catch (_: Exception) {}
        }

        // 开始截图
        startCapture(resultCode, data)

        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                Log.e(TAG, "Failed to create MediaProjection")
                cleanup()
                return
            }

            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val screenWidth: Int
            val screenHeight: Int
            val screenDensity: Int

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowMetrics = windowManager.currentWindowMetrics
                val bounds = windowMetrics.bounds
                screenWidth = bounds.width()
                screenHeight = bounds.height()
                screenDensity = resources.configuration.densityDpi
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                screenWidth = metrics.widthPixels
                screenHeight = metrics.heightPixels
                screenDensity = metrics.densityDpi
            }

            handlerThread = HandlerThread("ScreenCapture").also { it.start() }
            handler = Handler(handlerThread!!.looper)

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
                    MainActivity.capturedBitmap = bitmap
                    Log.d(TAG, "Screenshot captured: ${bitmap.width}x${bitmap.height}")

                    handler?.postDelayed({ cleanup() }, 500)
                } catch (e: Exception) {
                    Log.e(TAG, "Capture error: ${e.message}", e)
                    cleanup()
                } finally {
                    image.close()
                }
            }, handler)

            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "ScreenCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null, handler
            )

            Log.d(TAG, "VirtualDisplay created: ${screenWidth}x${screenHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "startCapture error: ${e.message}", e)
            cleanup()
        }
    }

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

        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height).also {
                bitmap.recycle()
            }
        } else {
            bitmap
        }
    }

    private fun cleanup() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { handlerThread?.quitSafely() } catch (_: Exception) {}
        handlerThread = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {
            @Suppress("DEPRECATION")
            try { stopForeground(true) } catch (_: Exception) {}
        }
        try { stopSelf() } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "屏幕截图", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("正在截图")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("正在截图")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }
}
