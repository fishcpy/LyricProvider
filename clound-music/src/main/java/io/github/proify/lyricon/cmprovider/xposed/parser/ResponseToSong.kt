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

package io.github.proify.lyricon.cmprovider.xposed.parser

import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.cmprovider.xposed.MediaMetadataCache
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song

private const val TAG: String = "LyricResponse"

/**
 * 将 [LyricResponse] 转换为 [Song] 对象，并自动匹配歌词与翻译。
 */
fun LyricResponse.toSong(): Song {
    val musicIdStr = musicId.toString()
    val metadata = MediaMetadataCache.getMetadataById(musicIdStr)

    return Song().apply {
        id = musicIdStr
        metadata?.let {
            name = it.title
            artist = it.artist
            duration = it.duration
        }

        // 优先解析 YRC，若为空则解析 LRC
        val lyrics = parseToRichLines(yrc, isYrc = true).ifEmpty {
            parseToRichLines(lrc, isYrc = false)
        }

        if (lyrics.isNotEmpty()) {
            attachTranslations(lyrics)
        }

        this.lyrics = lyrics
    }
}

/**
 * 解析原始歌词字符串并转换为 [RichLyricLine] 列表。
 */
private fun parseToRichLines(rawLyric: String, isYrc: Boolean): List<RichLyricLine> {
    if (rawLyric.isBlank()) return emptyList()
    return try {
        if (isYrc) {
            LyricParser.parseYrc(rawLyric).lines.map { it.toRichLyricLine() }
        } else {
            LyricParser.parseLrc(rawLyric).lines.map { it.toRichLyricLine() }
        }
    } catch (e: Exception) {
        YLog.error(tag = TAG, msg = "Parse ${if (isYrc) "YRC" else "LRC"} error: ${e.message}")
        emptyList()
    }
}

/**
 * 为歌词行列表匹配并填充翻译数据。
 */
private fun LyricResponse.attachTranslations(lyrics: List<RichLyricLine>) {

    //yrc可能是lrc格式，尝试以lrc格式解析yrc
    val yrcLrc = LyricParser.parseLrc(yrcTranslateLyric).lines

    //解析lrc
    val lrc = LyricParser.parseLrc(lrcTranslateLyric).lines

    val lrcTrans = yrcLrc.ifEmpty { lrc }
    if (lrcTrans.isEmpty()) return

    lyrics.forEach { rich ->
        lrcTrans.find {
            it.startTime >= rich.begin && it.endTime <= rich.end
        }?.let { match ->
            rich.translation = match.content
        }
    }
}

/**
 * 将 [LrcLine] 转换为通用歌词行模型。
 */
private fun LrcLine.toRichLyricLine() = RichLyricLine(
    begin = startTime,
    end = endTime,
    duration = duration,
    text = content,
)

/**
 * 将 [YrcLine] 转换为通用歌词行模型。
 */
private fun YrcLine.toRichLyricLine() = RichLyricLine(
    begin = startTime,
    end = endTime,
    duration = duration,
    text = words.joinToString("") { it.word },
    words = words.map { it.toLyricWord() }
)

/**
 * 将 [YrcWord] 转换为逐字歌词模型。
 */
private fun YrcWord.toLyricWord() = LyricWord(
    text = word,
    begin = startTime,
    end = endTime,
    duration = duration
)