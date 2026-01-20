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

package io.github.proify.lyricon.provider.common.parser.lrc

/**
 * 单条歌词数据
 */
data class LrcEntry(
    /** 时间戳（毫秒） */
    val time: Long,
    /** 歌词内容 */
    val text: String
) : Comparable<LrcEntry> {
    override fun compareTo(other: LrcEntry): Int = time.compareTo(other.time)
}