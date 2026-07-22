package com.quickfind.ocr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var ocrEngine: OcrEngine
    private lateinit var captureManager: ScreenCaptureManager

    // 用 Activity 级别的回调，避免 DisposableEffect
    var onBitmapCaptured: ((Bitmap) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            ocrEngine = OcrEngine()
            captureManager = ScreenCaptureManager(this)
        } catch (e: Exception) {
            Toast.makeText(this, "初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContent {
            MaterialTheme {
                QuickFindApp()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ScreenCaptureManager.REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                try {
                    captureManager.startCapture(resultCode, data) { bitmap ->
                        runOnUiThread {
                            if (bitmap != null) {
                                onBitmapCaptured?.invoke(bitmap)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "截图失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            ocrEngine.close()
            captureManager.release()
        } catch (_: Exception) {}
    }

    // ==================== 主界面 ====================

    @Composable
    fun QuickFindApp() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        var fileName by remember { mutableStateOf("") }
        var fileContent by remember { mutableStateOf("") }
        var ocrText by remember { mutableStateOf("") }
        var searchQuery by remember { mutableStateOf("") }
        var searchResults by remember { mutableStateOf<List<FuzzySearch.SearchResult>>(emptyList()) }
        var isLoading by remember { mutableStateOf(false) }
        var statusMessage by remember { mutableStateOf("") }

        // 文件选择器 - 使用 */* 兼容所有文件管理器
        val filePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) {
                scope.launch {
                    try {
                        isLoading = true
                        statusMessage = "正在读取文件..."

                        val (name, content) = withContext(Dispatchers.IO) {
                            readTextFile(context, uri)
                        }

                        fileName = name
                        fileContent = content
                        searchResults = emptyList()
                        ocrText = ""
                        statusMessage = "已加载: $name (${content.length} 字符)"
                        isLoading = false
                    } catch (e: Exception) {
                        isLoading = false
                        statusMessage = "打开失败: ${e.message}"
                    }
                }
            }
        }

        // 截图回调 - 直接设置在 Activity 上
        remember {
            this@MainActivity.onBitmapCaptured = { bitmap ->
                scope.launch {
                    try {
                        isLoading = true
                        statusMessage = "正在 OCR 识别..."

                        val (text, _) = withContext(Dispatchers.Default) {
                            ocrEngine.recognizeTextWithConfidence(bitmap)
                        }
                        ocrText = text
                        searchQuery = text.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""

                        // 自动搜索
                        if (fileContent.isNotEmpty() && searchQuery.isNotEmpty()) {
                            statusMessage = "正在搜索..."
                            searchResults = withContext(Dispatchers.Default) {
                                FuzzySearch.search(searchQuery, fileContent, 0.6f)
                            }
                            statusMessage = "识别完成，找到 ${searchResults.size} 个匹配"
                        } else {
                            statusMessage = "识别完成"
                        }
                        isLoading = false
                    } catch (e: Exception) {
                        isLoading = false
                        statusMessage = "识别失败: ${e.message}"
                    }
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (fileName.isNotEmpty()) fileName else "快速查找",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // 状态消息
                if (statusMessage.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            statusMessage,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // 加载指示
                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    )
                }

                // 操作按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            try {
                                filePickerLauncher.launch(arrayOf("*/*"))
                            } catch (e: Exception) {
                                statusMessage = "无法打开文件选择器: ${e.message}"
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (fileName.isEmpty()) "打开TXT" else "更换文件")
                    }

                    Button(
                        onClick = {
                            try {
                                captureManager.requestScreenCapture(this@MainActivity)
                            } catch (e: Exception) {
                                statusMessage = "截图请求失败: ${e.message}"
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("截图识别")
                    }
                }

                // OCR 文本和搜索
                if (ocrText.isNotEmpty()) {
                    Text("OCR 识别结果:", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            ocrText,
                            modifier = Modifier.padding(12.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }

                // 搜索结果摘要
                if (searchResults.isNotEmpty()) {
                    Text(
                        "找到 ${searchResults.size} 个匹配结果",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // 文件内容
                if (fileContent.isNotEmpty()) {
                    Text("文件内容:", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))

                    // 限制显示大小
                    val displayText = if (fileContent.length > 100_000) {
                        fileContent.substring(0, 100_000) + "\n\n... [文件过大，仅显示前10万字符]"
                    } else {
                        fileContent
                    }

                    // 构建带高亮文本
                    val annotatedText = if (searchResults.isEmpty()) {
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)) {
                                append(displayText)
                            }
                        }
                    } else {
                        buildHighlightedText(displayText, searchResults)
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Text(
                            annotatedText,
                            modifier = Modifier.padding(12.dp),
                            lineHeight = 20.sp
                        )
                    }
                }

                // 空状态
                if (fileContent.isEmpty() && ocrText.isEmpty()) {
                    Spacer(Modifier.height(40.dp))
                    Text(
                        "使用说明:\n\n" +
                        "1. 点击「打开TXT」选择文本文件\n" +
                        "2. 点击「截图识别」截取屏幕文字\n" +
                        "3. 自动在文件中模糊搜索匹配内容\n\n" +
                        "提示: 降低搜索阈值可找到更多匹配结果",
                        color = Color.Gray,
                        lineHeight = 24.sp
                    )
                }

                // 底部留白
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    // ==================== 高亮文本构建 ====================

    private fun buildHighlightedText(
        content: String,
        results: List<FuzzySearch.SearchResult>
    ): androidx.compose.ui.text.AnnotatedString {
        return try {
            buildAnnotatedString {
                val sorted = results.sortedBy { it.position }
                    .filter { it.position < content.length && it.length > 0 }

                var lastEnd = 0
                for (result in sorted) {
                    val start = result.position.coerceIn(lastEnd, content.length)
                    val end = (start + result.length).coerceAtMost(content.length)
                    if (start >= end) continue

                    // 普通文本
                    if (start > lastEnd) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)) {
                            append(content.substring(lastEnd, start))
                        }
                    }

                    // 高亮
                    val bgColor = when {
                        result.similarity > 0.9f -> Color(0xFFFFEB3B)
                        result.similarity > 0.7f -> Color(0xFFFFF176)
                        else -> Color(0xFFFFF9C4)
                    }
                    withStyle(SpanStyle(
                        background = bgColor,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )) {
                        append(content.substring(start, end))
                    }
                    lastEnd = end
                }

                if (lastEnd < content.length) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)) {
                        append(content.substring(lastEnd))
                    }
                }
            }
        } catch (e: Exception) {
            buildAnnotatedString {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)) {
                    append(content)
                }
            }
        }
    }

    // ==================== 文件读取 ====================

    companion object {
        private const val MAX_FILE_SIZE = 2 * 1024 * 1024 // 2MB

        fun readTextFile(context: Context, uri: Uri): Pair<String, String> {
            // 获取文件名
            val displayName = try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
            } catch (_: Exception) { null }
            val fileName = displayName ?: uri.lastPathSegment ?: "未知文件"

            // 读取内容
            val content = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(MAX_FILE_SIZE)
                    val totalRead = input.read(buffer)
                    if (totalRead <= 0) {
                        "文件为空"
                    } else {
                        val bytes = buffer.copyOf(totalRead)
                        // 尝试 UTF-8
                        val text = String(bytes, Charsets.UTF_8)
                        // 检查是否有乱码标记，尝试 GBK
                        if (text.contains('\uFFFD') && totalRead < 100_000) {
                            try {
                                String(bytes, charset("GBK"))
                            } catch (_: Exception) {
                                text
                            }
                        } else {
                            text
                        }
                    }
                } ?: "无法打开文件"
            } catch (e: SecurityException) {
                "没有读取权限"
            } catch (e: Exception) {
                "读取失败: ${e.message}"
            }

            return Pair(fileName, content)
        }
    }
}
