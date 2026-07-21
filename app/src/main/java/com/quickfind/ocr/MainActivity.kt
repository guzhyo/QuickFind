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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {

    private lateinit var ocrEngine: OcrEngine
    private lateinit var captureManager: ScreenCaptureManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ocrEngine = OcrEngine()
        captureManager = ScreenCaptureManager(this)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF1976D2),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFBBDEFB),
                    secondary = Color(0xFF455A64),
                    background = Color(0xFFF5F5F5),
                    surface = Color.White,
                    error = Color(0xFFD32F2F)
                )
            ) {
                MainScreen(
                    ocrEngine = ocrEngine,
                    captureManager = captureManager,
                    activity = this
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ScreenCaptureManager.REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == Activity.RESULT_OK) {
                captureManager.startCapture(resultCode, data) { bitmap ->
                    runOnUiThread {
                        if (bitmap != null) {
                            // 通过全局回调传递截图
                            MainActivity.pendingBitmap = bitmap
                            MainActivity.onBitmapCaptured?.invoke(bitmap)
                        } else {
                            Toast.makeText(this, "截图失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                Toast.makeText(this, "截图权限被拒绝", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ocrEngine.close()
        captureManager.release()
    }

    companion object {
        var pendingBitmap: Bitmap? = null
        var onBitmapCaptured: ((Bitmap) -> Unit)? = null
    }
}

// ==================== 主界面 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    ocrEngine: OcrEngine,
    captureManager: ScreenCaptureManager,
    activity: MainActivity
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 状态
    var fileName by remember { mutableStateOf("") }
    var fileContent by remember { mutableStateOf("") }
    var ocrText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<FuzzySearch.SearchResult>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("") }
    var showOcrResult by remember { mutableStateOf(false) }
    var showSearchPanel by remember { mutableStateOf(false) }
    var similarityThreshold by remember { mutableStateOf(0.6f) }
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    // 文件选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                isLoading = true
                loadingMessage = "正在读取文件..."
                val (name, content) = withContext(Dispatchers.IO) {
                    readTextFile(context, it)
                }
                fileName = name
                fileContent = content
                searchResults = emptyList()
                showSearchPanel = false
                isLoading = false
            }
        }
    }

    // 截图回调
    DisposableEffect(Unit) {
        MainActivity.onBitmapCaptured = { bitmap ->
            scope.launch {
                isLoading = true
                loadingMessage = "正在进行 OCR 识别..."

                val (text, confidence) = ocrEngine.recognizeTextWithConfidence(bitmap)
                ocrText = text
                showOcrResult = true
                searchQuery = text.lines().firstOrNull { it.isNotBlank() }?.trim() ?: text

                isLoading = false
                showSearchPanel = true

                if (fileContent.isNotEmpty() && searchQuery.isNotEmpty()) {
                    loadingMessage = "正在搜索..."
                    isLoading = true
                    searchResults = withContext(Dispatchers.Default) {
                        FuzzySearch.search(searchQuery, fileContent, similarityThreshold)
                    }
                    isLoading = false
                }

                Toast.makeText(context, "OCR 识别完成", Toast.LENGTH_SHORT).show()
            }
        }
        onDispose {
            MainActivity.onBitmapCaptured = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("快速查找", fontWeight = FontWeight.Bold)
                        if (fileName.isNotEmpty()) {
                            Text(
                                fileName,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    if (fileContent.isNotEmpty()) {
                        IconButton(onClick = {
                            showSearchPanel = !showSearchPanel
                        }) {
                            Icon(Icons.Default.Search, contentDescription = "搜索")
                        }
                    }
                }
            )
        },
        bottomBar = {
            // 底部操作栏
            Surface(
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // 打开文件按钮
                    OutlinedButton(
                        onClick = {
                            filePickerLauncher.launch(arrayOf("text/plain"))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (fileName.isEmpty()) "打开TXT" else "更换文件")
                    }

                    Spacer(Modifier.width(12.dp))

                    // 截图识别按钮
                    Button(
                        onClick = {
                            captureManager.requestScreenCapture(activity)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("截图识别")
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 加载指示器
            AnimatedVisibility(
                visible = isLoading,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    loadingMessage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                        .padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 主内容区域
            if (fileContent.isEmpty() && !showOcrResult) {
                // 空状态提示
                EmptyStateView()
            } else {
                // Tab 切换
                if (showOcrResult && fileContent.isNotEmpty()) {
                    TabRow(selectedTabIndex = selectedTabIndex) {
                        Tab(
                            selected = selectedTabIndex == 0,
                            onClick = { selectedTabIndex = 0 },
                            text = { Text("文件内容") }
                        )
                        Tab(
                            selected = selectedTabIndex == 1,
                            onClick = { selectedTabIndex = 1 },
                            text = { Text("搜索结果 (${searchResults.size})") }
                        )
                        Tab(
                            selected = selectedTabIndex == 2,
                            onClick = { selectedTabIndex = 2 },
                            text = { Text("OCR 文本") }
                        )
                    }
                }

                when {
                    selectedTabIndex == 0 || !showOcrResult -> {
                        // 文件内容显示
                        FileContentView(fileContent, searchResults)
                    }
                    selectedTabIndex == 1 -> {
                        // 搜索结果显示
                        SearchResultView(
                            results = searchResults,
                            fileContent = fileContent,
                            onResultClick = { /* 可跳转到对应位置 */ }
                        )
                    }
                    selectedTabIndex == 2 -> {
                        // OCR 文本显示
                        OcrTextView(ocrText)
                    }
                }
            }

            // 搜索面板
            AnimatedVisibility(
                visible = showSearchPanel && fileContent.isNotEmpty(),
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                SearchPanel(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    threshold = similarityThreshold,
                    onThresholdChange = { similarityThreshold = it },
                    onSearch = {
                        if (searchQuery.isNotBlank() && fileContent.isNotEmpty()) {
                            scope.launch {
                                isLoading = true
                                loadingMessage = "正在模糊搜索..."
                                searchResults = withContext(Dispatchers.Default) {
                                    FuzzySearch.search(searchQuery, fileContent, similarityThreshold)
                                }
                                isLoading = false
                                selectedTabIndex = 1
                            }
                        }
                    }
                )
            }
        }
    }
}

// ==================== 空状态视图 ====================

@Composable
fun EmptyStateView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "快速查找",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "1. 点击下方「打开TXT」选择文本文件\n2. 点击「截图识别」进行屏幕截图 OCR\n3. 自动在文件中模糊搜索识别文字",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                lineHeight = 22.sp
            )
        }
    }
}

// ==================== 文件内容视图 ====================

@Composable
fun FileContentView(content: String, searchResults: List<FuzzySearch.SearchResult>) {
    val scrollState = rememberScrollState()

    // 构建带高亮的文本
    val annotatedText = buildAnnotatedString {
        if (searchResults.isEmpty()) {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)) {
                append(content)
            }
        } else {
            // 按位置排序结果
            val sortedResults = searchResults.sortedBy { it.position }
            var lastEnd = 0

            for (result in sortedResults) {
                val start = result.position.coerceAtLeast(lastEnd)
                val end = (start + result.length).coerceAtMost(content.length)
                if (start >= end) continue

                // 匹配前的普通文本
                if (start > lastEnd) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)) {
                        append(content.substring(lastEnd, start))
                    }
                }

                // 高亮匹配文本
                val alpha = result.similarity
                val bgColor = if (alpha > 0.9f) {
                    Color(0xFFFFEB3B) // 高相似度 - 黄色
                } else if (alpha > 0.7f) {
                    Color(0xFFFFF176) // 中相似度 - 浅黄
                } else {
                    Color(0xFFFFF9C4) // 低相似度 - 淡黄
                }

                withStyle(
                    SpanStyle(
                        background = bgColor,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                ) {
                    append(content.substring(start, end))
                }

                lastEnd = end
            }

            // 剩余文本
            if (lastEnd < content.length) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)) {
                    append(content.substring(lastEnd))
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(12.dp)
    ) {
        if (searchResults.isNotEmpty()) {
            // 搜索摘要
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            ) {
                Text(
                    "找到 ${searchResults.size} 个匹配结果（最高相似度: ${(searchResults.maxOf { it.similarity } * 100).toInt()}%）",
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Text(
            annotatedText,
            lineHeight = 20.sp
        )
    }
}

// ==================== 搜索结果视图 ====================

@Composable
fun SearchResultView(
    results: List<FuzzySearch.SearchResult>,
    fileContent: String,
    onResultClick: (FuzzySearch.SearchResult) -> Unit
) {
    if (results.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.SearchOff,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "未找到匹配结果",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
                Text(
                    "尝试降低相似度阈值",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(results) { index, result ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onResultClick(result) },
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "#${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        // 相似度标签
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = when {
                                result.similarity > 0.9f -> Color(0xFF4CAF50)
                                result.similarity > 0.7f -> Color(0xFFFF9800)
                                else -> Color(0xFFFF5722)
                            }
                        ) {
                            Text(
                                "${(result.similarity * 100).toInt()}%",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // 匹配的文本
                    Text(
                        result.matchedText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    // 上下文预览
                    val contextStart = maxOf(0, result.position - 20)
                    val contextEnd = minOf(fileContent.length, result.position + result.length + 20)
                    val beforeContext = fileContent.substring(contextStart, result.position)
                    val afterContext = fileContent.substring(
                        result.position + result.length,
                        contextEnd
                    )

                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = Color.Gray, fontSize = 11.sp)) {
                                append("...$beforeContext")
                            }
                            withStyle(SpanStyle(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )) {
                                append(result.matchedText)
                            }
                            withStyle(SpanStyle(color = Color.Gray, fontSize = 11.sp)) {
                                append("$afterContext...")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // 位置信息
                    Text(
                        "位置: ${result.position}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

// ==================== OCR 文本视图 ====================

@Composable
fun OcrTextView(text: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "OCR 识别结果",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "以下文字从屏幕截图中识别，可直接编辑后搜索",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text.ifEmpty { "暂无识别结果" },
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                lineHeight = 22.sp
            )
        )
    }
}

// ==================== 搜索面板 ====================

@Composable
fun SearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    threshold: Float,
    onThresholdChange: (Float) -> Unit,
    onSearch: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 搜索输入框
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索关键词") },
                placeholder = { Text("输入或修改 OCR 识别文字") },
                trailingIcon = {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                },
                maxLines = 3,
                textStyle = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(8.dp))

            // 相似度阈值滑块
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "模糊度:",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(50.dp)
                )
                Slider(
                    value = threshold,
                    onValueChange = onThresholdChange,
                    modifier = Modifier.weight(1f),
                    valueRange = 0.3f..1.0f,
                    steps = 6
                )
                Text(
                    "${(threshold * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(40.dp)
                )
            }

            Text(
                "降低模糊度可匹配更多结果（适合 OCR 误差较大的情况）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

// ==================== 工具函数 ====================

/**
 * 读取 TXT 文件内容
 */
fun readTextFile(context: Context, uri: Uri): Pair<String, String> {
    val fileName = uri.lastPathSegment ?: "未知文件"
    val content = try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                reader.readText()
            }
        } ?: "无法读取文件"
    } catch (e: Exception) {
        "读取文件失败: ${e.message}"
    }
    return Pair(fileName, content)
}
