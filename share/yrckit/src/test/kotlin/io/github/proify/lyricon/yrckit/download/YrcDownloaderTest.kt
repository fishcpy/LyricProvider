/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.yrckit.download

import io.github.proify.lrckit.LrcParser
import kotlin.test.Test

class YrcDownloaderTest {

    @Test
    fun fetchLyric() {
        val r = YrcDownloader.fetchLyric(2021437775)
        println(r)
        val lines = LrcParser.parse(r.tlyric?.lyric.orEmpty())
        println(lines.lines)
    }

}