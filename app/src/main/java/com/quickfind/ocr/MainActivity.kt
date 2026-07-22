package com.quickfind.ocr

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnOpenFile: Button
    private lateinit var btnCapture: Button
    private lateinit var tvOcrLabel: TextView
    private lateinit var tvOcrText: TextView
    private lateinit var scrollOcr: ScrollView
    private lateinit var tvSearchSummary: TextView
    private lateinit var tvFileLabel: TextView
    private lateinit var tvFileContent: TextView

    private lateinit var ocrEngine: OcrEngine
    private lateinit var captureManager: ScreenCaptureManager

    private var fileName = ""
    private var fileContent = ""

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        // 静态字段传递 Bitmap，避免 Intent 大小限制
        var capturedBitmap: Bitmap? = null
    }

    // 文件选择器
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { loadFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化视图
        tvTitle = findViewById(R.id.tvTitle)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        btnOpenFile = findViewById(R.id.btnOpenFile)
        btnCapture = findViewById(R.id.btnCapture)
        tvOcrLabel = findViewById(R.id.tvOcrLabel)
        tvOcrText = findViewById(R.id.tvOcrText)
        scrollOcr = findViewById(R.id.scrollOcr)
        tvSearchSummary = findViewById(R.id.tvSearchSummary)
        tvFileLabel = findViewById(R.id.tvFileLabel)
        tvFileContent = findViewById(R.id.tvFileContent)

        // 初始化引擎
        try {
            ocrEngine = OcrEngine()
            captureManager = ScreenCaptureManager(this)
            captureManager.onError = { msg -> showStatus(msg) }
        } catch (e: Exception) {
            showStatus("初始化失败: ${e.message}")
        }

        // 按钮事件
        btnOpenFile.setOnClickListener {
            try {
                filePickerLauncher.launch(arrayOf("text/plain", "text/*", "*/*"))
            } catch (e: Exception) {
                showStatus("无法打开文件选择器: ${e.message}")
            }
        }

        btnCapture.setOnClickListener {
            try {
                captureManager.requestScreenCapture(this)
            } catch (e: Exception) {
                showStatus("截图请求失败: ${e.message}")
            }
        }
    }

    private fun startScreenCapture() {
        try {
            captureManager.requestScreenCapture(this)
        } catch (e: Exception) {
            showStatus("截图请求失败: ${e.message}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ScreenCaptureManager.REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                try {
                    captureManager.startCapture(resultCode, data)
                    showStatus("正在截图...")
                } catch (e: Exception) {
                    showStatus("截图失败: ${e.message}")
                }
            } else {
                showStatus("截图权限被拒绝")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 检查是否有截图结果
        val bitmap = capturedBitmap
        if (bitmap != null) {
            capturedBitmap = null
            processScreenshot(bitmap)
        }
    }

    // 加载文件
    private fun loadFile(uri: Uri) {
        scope.launch {
            try {
                showLoading("正在读取文件...")
                val (name, content) = withContext(Dispatchers.IO) {
                    readTextFile(uri)
                }
                fileName = name
                fileContent = content
                tvTitle.text = name
                tvFileLabel.visibility = View.VISIBLE
                tvFileContent.text = if (content.length > 100_000) {
                    content.substring(0, 100_000) + "\n\n... [文件过大，仅显示前10万字符]"
                } else {
                    content
                }
                tvSearchSummary.visibility = View.GONE
                showLoading(null)
                showStatus("已加载: $name (${content.length} 字符)")
            } catch (e: Exception) {
                showLoading(null)
                showStatus("打开文件失败: ${e.message}")
            }
        }
    }

    // 处理截图结果
    private fun processScreenshot(bitmap: Bitmap) {
        scope.launch {
            try {
                showLoading("正在 OCR 识别...")
                val (text, _) = withContext(Dispatchers.Default) {
                    ocrEngine.recognizeTextWithConfidence(bitmap)
                }

                tvOcrLabel.visibility = View.VISIBLE
                scrollOcr.visibility = View.VISIBLE
                tvOcrText.text = text

                // 自动搜索
                if (fileContent.isNotEmpty() && text.isNotBlank()) {
                    val query = text.lines().firstOrNull { it.isNotBlank() }?.trim() ?: text
                    showLoading("正在搜索...")
                    val results = withContext(Dispatchers.Default) {
                        FuzzySearch.search(query, fileContent, 0.6f)
                    }
                    if (results.isNotEmpty()) {
                        tvSearchSummary.visibility = View.VISIBLE
                        tvSearchSummary.text = "找到 ${results.size} 个匹配结果 (关键词: ${query.take(30)})"
                        // 显示带高亮的文件内容
                        highlightSearchResults(results)
                    } else {
                        tvSearchSummary.visibility = View.VISIBLE
                        tvSearchSummary.text = "未找到匹配结果"
                    }
                }
                showLoading(null)
                showStatus("识别完成")
            } catch (e: Exception) {
                showLoading(null)
                showStatus("识别失败: ${e.message}")
            }
        }
    }

    // 高亮搜索结果
    private fun highlightSearchResults(results: List<FuzzySearch.SearchResult>) {
        val displayText = if (fileContent.length > 100_000) {
            fileContent.substring(0, 100_000)
        } else {
            fileContent
        }

        val spannable = android.text.SpannableString(displayText)
        val sorted = results.sortedBy { it.position }
            .filter { it.position < displayText.length && it.length > 0 }

        for (result in sorted) {
            val start = result.position.coerceIn(0, displayText.length)
            val end = (start + result.length).coerceAtMost(displayText.length)
            if (start >= end) continue

            val color = when {
                result.similarity > 0.9f -> 0xFFFFFF00.toInt() // 黄色
                result.similarity > 0.7f -> 0xFFFFFF99.toInt() // 浅黄
                else -> 0xFFFFFFCC.toInt() // 淡黄
            }
            spannable.setSpan(
                android.text.style.BackgroundColorSpan(color),
                start, end,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        tvFileContent.text = spannable
    }

    // 读取文件
    private fun readTextFile(uri: Uri): Pair<String, String> {
        val displayName = try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) { null }
        val name = displayName ?: uri.lastPathSegment ?: "未知文件"

        val content = try {
            contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(2 * 1024 * 1024)
                val totalRead = input.read(buffer)
                if (totalRead <= 0) "文件为空"
                else {
                    val bytes = buffer.copyOf(totalRead)
                    try {
                        String(bytes, Charsets.UTF_8)
                    } catch (_: Exception) {
                        String(bytes, charset("GBK"))
                    }
                }
            } ?: "无法打开文件"
        } catch (e: SecurityException) {
            "没有读取权限"
        } catch (e: Exception) {
            "读取失败: ${e.message}"
        }
        return Pair(name, content)
    }

    private fun showStatus(msg: String) {
        tvStatus.text = msg
        tvStatus.visibility = View.VISIBLE
    }

    private fun showLoading(msg: String?) {
        if (msg != null) {
            progressBar.visibility = View.VISIBLE
            tvStatus.text = msg
            tvStatus.visibility = View.VISIBLE
        } else {
            progressBar.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try { ocrEngine.close() } catch (_: Exception) {}
    }
}
