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

    /**
     * 发起屏幕截图权限请求
     */
    fun requestScreenCapture(activity: Activity) {
        val projectionManager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        activity.startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_CODE_SCREEN_CAPTURE
        )
    }

    /**
     * 处理权限结果，启动前台服务进行截图
     */
    fun startCapture(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) return

        try {
            val serviceIntent = ScreenCaptureService.createIntent(context, resultCode, data)
            context.startForegroundService(serviceIntent)
        } catch (e: SecurityException) {
            onError?.invoke("需要通知权限才能截图，请在设置中开启")
        } catch (e: Exception) {
            onError?.invoke("启动截图服务失败: ${e.message}")
        }
    }
}
