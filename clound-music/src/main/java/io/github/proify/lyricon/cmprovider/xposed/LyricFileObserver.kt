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

package io.github.proify.lyricon.cmprovider.xposed

import android.content.Context
import android.os.FileObserver
import com.highcapable.yukihookapi.hook.log.YLog
import java.io.File

class LyricFileObserver(context: Context, private val callback: FileObserverCallback) {

    private val cacheDir = File(context.externalCacheDir, "Cache/Lyric")

    @Suppress("DEPRECATION")
    private val fileObserver =
        object : FileObserver(cacheDir.absolutePath, CREATE or DELETE or MODIFY) {
            override fun onEvent(event: Int, path: String?) {

                YLog.debug("LyricFileObserver: $event $path")
                if (path.isNullOrEmpty()) return

                val file = File(cacheDir, path)
                if (!file.exists() || !file.isFile) return

                callback.onFileChanged(event, file)
            }
        }

    init {
        if (!cacheDir.exists()) cacheDir.mkdirs()
    }

    fun start() {
        fileObserver.startWatching()
    }

    fun stop() {
        fileObserver.stopWatching()
    }

    fun getFile(id: String): File {
        return File(cacheDir, id)
    }

    interface FileObserverCallback {
        fun onFileChanged(event: Int, file: File)
    }
}