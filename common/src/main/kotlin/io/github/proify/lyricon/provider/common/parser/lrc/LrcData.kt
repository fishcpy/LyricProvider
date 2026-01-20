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
 * 解析后的 LRC 数据对象
 */
data class LrcData(
    /** 元数据，如 ti (标题), ar (歌手), offset (偏移量) 等 */
    val metadata: Map<String, String>,
    /** 按时间排序后的歌词行 */
    val entries: List<LrcEntry>
)