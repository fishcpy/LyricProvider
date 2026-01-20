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

package io.github.proify.lyricon.amprovider.xposed.model

import kotlinx.serialization.Serializable

@Serializable
data class LyricLine(
    override var agent: String? = null,
    override var begin: Int = 0,
    override var duration: Int = 0,
    override var end: Int = 0,

    var htmlLineText: String? = null, //主要歌词
    var words: MutableList<LyricWord> = mutableListOf(), // 主要歌词单词
    var htmlTranslationLineText: String? = null, // 主要翻译

    // 副歌词
    var htmlBackgroundVocalsLineText: String? = null,
    var backgroundWords: MutableList<LyricWord> = mutableListOf(),

    var htmlTranslatedBackgroundVocalsLineText: String? = null,
    //var translatedBackgroundWords: MutableList<LyricWord> = mutableListOf(),

    // 音译
    var htmlPronunciationLineText: String? = null,
    //var pronunciationWords: MutableList<LyricWord> = mutableListOf(),

    var htmlPronunciationBackgroundVocalsLineText: String? = null,
    //var pronunciationBackgroundWords: MutableList<LyricWord> = mutableListOf(),

) : LyricTiming