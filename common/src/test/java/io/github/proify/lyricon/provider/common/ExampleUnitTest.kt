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

package io.github.proify.lyricon.provider.common

import io.github.proify.lyricon.provider.common.parser.lrc.LrcParser
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {

    }


    @Test
    fun main() {
        val lrcContent = """
        [ti:示例歌曲]
        [ar:歌手名称]
        [offset:100]
        [00:01.50]第一行歌词
        [00:05.00][00:12.00]这是一行在两个时间点显示的歌词
    """.trimIndent()

        val lrcData = LrcParser.parse(lrcContent)

        println("歌曲信息: ${lrcData.metadata}")
        lrcData.entries.forEach {
            println("${it.time}ms -> ${it.text}")
        }
    }


}