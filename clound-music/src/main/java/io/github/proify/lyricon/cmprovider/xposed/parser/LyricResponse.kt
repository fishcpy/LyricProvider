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

import kotlinx.serialization.Serializable

/**
 * 歌词响应实体，包含多种歌词格式及元数据
 */
@Serializable
data class LyricResponse(
    val briefDesc: String = "",
    val karaokeLyric: String = "",
    val karaokeVersion: Int = 0,
    val lrc: String = "",
    val lrcRomeLyric: String = "",
    val lrcRomeVersion: Int = 0,
    val lrcTranslateLyric: String = "",
    val lrcTranslateVersion: Int = 0,
    val lrcVersion: Int = 0,
    val lyricInfoType: String = "",
    val lyricUserId: Long = 0,
    val lyricUserName: String = "",
    val lyricUserOffset: Int = 0,
    val lyricUserTime: Long = 0,
    val lyricValid: Boolean = false,
    val musicId: Long = 0,
    val pureMusic: Boolean = false,
    val qfy: Boolean = false,
    val transUserId: Long = 0,
    val transUserName: String = "",
    val transUserTime: Long = 0,
    val yrc: String = "",
    val yrcRomeLyric: String = "",
    val yrcRomeVersion: Int = 0,
    val yrcTranslateLyric: String = "",
    val yrcTranslateVersion: Int = 0,
    val yrcVersion: Int = 0
)
