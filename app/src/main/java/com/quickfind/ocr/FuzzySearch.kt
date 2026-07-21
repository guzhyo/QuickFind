package com.quickfind.ocr

/**
 * 模糊搜索工具类
 * 基于 Levenshtein 编辑距离算法，支持对 OCR 识别结果进行容错匹配
 */
object FuzzySearch {

    data class SearchResult(
        val position: Int,          // 匹配起始位置
        val length: Int,            // 匹配文本长度
        val matchedText: String,    // 匹配到的文本片段
        val similarity: Float       // 相似度 0.0 ~ 1.0
    )

    /**
     * 计算两个字符串的 Levenshtein 编辑距离
     * 使用空间优化的单行 DP 算法
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length

        if (m == 0) return n
        if (n == 0) return m

        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,        // 删除
                    curr[j - 1] + 1,    // 插入
                    prev[j - 1] + cost   // 替换
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }

        return prev[n]
    }

    /**
     * 在文本中模糊搜索查询字符串
     * 使用滑动窗口算法，对 OCR 错误有良好容错性
     *
     * @param query     搜索关键词（OCR 识别结果）
     * @param text      目标文本（TXT 文件内容）
     * @param threshold 相似度阈值 (0.0-1.0)，默认 0.6
     * @param maxResults 最大返回结果数
     */
    fun search(
        query: String,
        text: String,
        threshold: Float = 0.6f,
        maxResults: Int = 50
    ): List<SearchResult> {
        if (query.isBlank() || text.isBlank()) return emptyList()

        val cleanQuery = query.trim()
        val cleanText = text
        val queryLen = cleanQuery.length
        val results = mutableListOf<SearchResult>()

        // 容差范围：允许查询长度的 50% 作为编辑距离上限
        val tolerance = maxOf(2, queryLen / 2)

        // 滑动窗口：从 queryLen - tolerance 到 queryLen + tolerance
        val minWinLen = maxOf(1, queryLen - tolerance)
        val maxWinLen = minOf(cleanText.length, queryLen + tolerance)

        for (winLen in minWinLen..maxWinLen) {
            if (winLen > cleanText.length) break

            for (i in 0..cleanText.length - winLen) {
                val candidate = cleanText.substring(i, i + winLen)
                val distance = levenshteinDistance(cleanQuery, candidate)
                val maxLen = maxOf(cleanQuery.length, candidate.length)
                val similarity = 1.0f - (distance.toFloat() / maxLen)

                if (similarity >= threshold) {
                    // 检查是否与已有结果重叠
                    val overlaps = results.any { existing ->
                        i < existing.position + existing.length &&
                                i + winLen > existing.position
                    }

                    if (!overlaps) {
                        results.add(
                            SearchResult(
                                position = i,
                                length = winLen,
                                matchedText = candidate,
                                similarity = similarity
                            )
                        )

                        // 按相似度排序，保留最佳结果
                        results.sortByDescending { it.similarity }
                        if (results.size > maxResults) {
                            results.removeAt(results.lastIndex)
                        }
                    }
                }
            }
        }

        return results.sortedByDescending { it.similarity }
    }

    /**
     * 精确匹配（用于快速预筛选）
     */
    fun exactSearch(query: String, text: String): List<SearchResult> {
        if (query.isBlank() || text.isBlank()) return emptyList()

        val results = mutableListOf<SearchResult>()
        var startIndex = 0

        while (true) {
            val pos = text.indexOf(query, startIndex, ignoreCase = true)
            if (pos == -1) break

            results.add(
                SearchResult(
                    position = pos,
                    length = query.length,
                    matchedText = text.substring(pos, pos + query.length),
                    similarity = 1.0f
                )
            )
            startIndex = pos + 1
        }

        return results
    }
}
