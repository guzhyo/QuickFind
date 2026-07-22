package com.quickfind.ocr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageFormat
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
import android.view.WindowManager

/**
 * 前台服务：处理 MediaProjection 屏幕截图
 * Android 14+ 要求 MediaProjection 必须在前台服务中运行
 */
class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "screen_capture_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "com.quickfind.ocr.STOP_CAPTURE"

        fun createIntent(context: Context, resultCode: Int, data: Intent): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var hasCaptured = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 检查是否是停止命令
        if (intent.action == ACTION_STOP) {
            cleanup()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == -1 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 启动前台服务
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // 开始截图
        startCapture(resultCode, data)

        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        // 获取屏幕参数
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val screenWidth: Int
        val screenHeight: Int
        val screenDensity: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            val config = resources.configuration
            screenDensity = config.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
        }

        // 创建后台线程
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

                // 通过广播发送截图结果
                val broadcastIntent = Intent("com.quickfind.ocr.SCREEN_CAPTURE_RESULT").apply {
                    setPackage(packageName)
                    putExtra("bitmap", bitmap)
                }
                sendBroadcast(broadcastIntent)

                // 延迟停止服务
                handler?.postDelayed({
                    cleanup()
                }, 500)
            } catch (e: Exception) {
                // 发送失败广播
                val broadcastIntent = Intent("com.quickfind.ocr.SCREEN_CAPTURE_ERROR").apply {
                    setPackage(packageName)
                    putExtra("error", e.message ?: "未知错误")
                }
                sendBroadcast(broadcastIntent)
                cleanup()
            } finally {
                image.close()
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
        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        handlerThread?.quitSafely()
        handlerThread = null

        mediaProjection?.stop()
        mediaProjection = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "屏幕截图",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于屏幕截图的前台服务通知"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("正在截图")
                .setContentText("快速查找正在截取屏幕")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        } else {
            return Notification.Builder(this)
                .setContentTitle("正在截图")
                .setContentText("快速查找正在截取屏幕")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }
}
