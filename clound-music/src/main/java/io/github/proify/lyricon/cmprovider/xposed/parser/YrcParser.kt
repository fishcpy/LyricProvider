/*
 * Copyright 2026 Proify, Tomakino
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("unused")

package io.github.proify.lyricon.cmprovider.xposed.parser

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ==================== LRC 格式 ====================

/**
 * Lrc 歌词行数据
 */
@Serializable
data class LrcLine(
    val startTime: Long,     // 开始时间(毫秒)
    val endTime: Long = 0,   // 结束时间(毫秒)
    val duration: Long = 0,  // 持续时间(毫秒)
    val content: String      // 歌词内容
)

/**
 * Lrc 歌词集合
 */
@Serializable
data class LrcLyric(
    val lines: List<LrcLine> = emptyList()
) {
    /** 获取歌词总时长 */
    val totalDuration: Long get() = lines.lastOrNull()?.endTime ?: 0L
}

// ==================== YRC 格式(逐字歌词) ====================

/**
 * Yrc 逐字数据
 */
@Serializable
data class YrcWord(
    val word: String,
    val startTime: Long,     // 相对于行开始的毫秒
    val endTime: Long,       // 相对结束时间
    val duration: Long       // 持续时间(毫秒)
)

/**
 * Yrc 歌词行数据
 */
@Serializable
data class YrcLine(
    val startTime: Long,     // 行开始时间(毫秒)
    val endTime: Long,       // 行结束时间(毫秒)
    val duration: Long,      // 行总持续时间(毫秒)
    val words: List<YrcWord> = emptyList()
) {
    /** 获取该行完整文本 */
    val fullText: String get() = words.joinToString("") { it.word }
}

/**
 * Yrc 歌词集合
 */
@Serializable
data class YrcLyric(
    val lines: List<YrcLine> = emptyList()
) {
    /** 获取歌词总时长 */
    val totalDuration: Long get() = lines.lastOrNull()?.endTime ?: 0L
}

// ==================== 解析器 ====================

/**
 * 歌词解析工具类，支持 LRC 和 YRC 格式
 */
object LyricParser {

    private const val DEFAULT_LINE_DURATION = 3000L

    // 预编译正则以提升性能
    // 捕获: [可选分钟:秒.可选毫秒]
    private val LRC_TIME_REGEX = Regex("""\[(?:(\d+):)?(\d+(?:\.\d+)?)]""")

    // 捕获: [开始时间,行时长]
    private val YRC_LINE_HEAD_REGEX = Regex("""\[(\d+),(\d+)]""")

    // 捕获: (开始,时长,保留)内容
    private val YRC_WORD_REGEX = Regex("""\((\d+),(\d+),\d+\)([^(]+?)(?=\(|$)""")

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 解析 LRC 格式字符串
     *
     * 支持格式: [mm:ss.xxx], [mm:ss], [m:ss.xxx] 等
     */
    fun parseLrc(lrcString: String): LrcLyric {
        if (lrcString.isBlank()) return LrcLyric()

        // 1. 解析所有行并按时间排序
        val rawLines = lrcString.lineSequence()
            .mapNotNull { parseLrcSingleLine(it) }
            .sortedBy { it.startTime }
            .toList()

        if (rawLines.isEmpty()) return LrcLyric()

        // 2. 计算每行的持续时间和结束时间
        // 使用 zipWithNext 优雅处理当前行与下一行的关系
        val processedLines = rawLines.zipWithNext { current, next ->
            val duration = next.startTime - current.startTime
            current.copy(
                endTime = next.startTime,
                duration = if (duration > 0) duration else 0
            )
        }.toMutableList()

        // 3. 处理最后一行（因为 zipWithNext 会忽略最后一个元素）
        val lastLine = rawLines.last()
        processedLines.add(
            lastLine.copy(
                endTime = lastLine.startTime + DEFAULT_LINE_DURATION,
                duration = DEFAULT_LINE_DURATION
            )
        )

        return LrcLyric(processedLines)
    }

    /**
     * 解析 YRC 逐字歌词格式字符串
     */
    fun parseYrc(yrcString: String): YrcLyric {
        if (yrcString.isBlank()) return YrcLyric()

        val lines = yrcString.lineSequence()
            .mapNotNull { parseYrcSingleLine(it) }
            .sortedBy { it.startTime }
            .toList()

        return YrcLyric(lines)
    }

    private fun parseLrcSingleLine(line: String): LrcLine? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("[")) return null

        val match = LRC_TIME_REGEX.find(trimmed) ?: return null
        val (minStr, secStr) = match.destructured

        val minutes = minStr.toLongOrNull() ?: 0L
        val seconds = secStr.toDoubleOrNull() ?: 0.0
        val time = (minutes * 60 * 1000) + (seconds * 1000).toLong()

        val content = trimmed.substringAfter("]").trim()
        if (content.isEmpty()) return null

        return LrcLine(startTime = time, content = content)
    }

    private fun parseYrcSingleLine(line: String): YrcLine? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("[")) return null

        return try {
            val headMatch = YRC_LINE_HEAD_REGEX.find(trimmed) ?: return null
            val (startStr, durationStr) = headMatch.destructured
            val startTime = startStr.toLong()
            val duration = durationStr.toLong()

            val words = YRC_WORD_REGEX.findAll(trimmed).map { match ->
                val (wStart, wDur, wText) = match.destructured
                YrcWord(
                    word = wText,
                    startTime = wStart.toLong(),
                    endTime = wStart.toLong() + wDur.toLong(),
                    duration = wDur.toLong()
                )
            }.toList()

            if (words.isNotEmpty()) {
                YrcLine(
                    startTime = startTime,
                    endTime = startTime + duration,
                    duration = duration,
                    words = words
                )
            } else null
        } catch (e: Exception) {
            null
        }
    }
}