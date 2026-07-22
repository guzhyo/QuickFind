package com.quickfind.ocr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager

/**
 * 屏幕截图管理器
 * 通过前台服务实现 MediaProjection 截图（Android 14+ 要求）
 */
class ScreenCaptureManager(private val context: Context) {

    companion object {
        const val REQUEST_CODE_SCREEN_CAPTURE = 1001
    }

    var onError: ((String) -> Unit)? = null

    fun requestScreenCapture(activity: Activity) {
        val projectionManager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        activity.startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_CODE_SCREEN_CAPTURE
        )
    }

    /**
     * 使用静态字段中的 resultCode 和 data 启动截图服务
     */
    fun startCapture() {
        val resultCode = MainActivity.pendingResultCode
        val data = MainActivity.pendingResultData

        if (resultCode == -1 || data == null) {
            onError?.invoke("截图数据无效")
            return
        }

        try {
            val serviceIntent = Intent(context, ScreenCaptureService::class.java)
            context.startForegroundService(serviceIntent)
        } catch (e: SecurityException) {
            onError?.invoke("需要通知权限才能截图，请在设置中开启")
        } catch (e: Exception) {
            onError?.invoke("启动截图服务失败: ${e.message}")
        }
    }
}
