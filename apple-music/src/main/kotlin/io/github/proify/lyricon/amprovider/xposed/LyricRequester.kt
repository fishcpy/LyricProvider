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

package io.github.proify.lyricon.amprovider.xposed

import android.app.Application
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XposedHelpers

class LyricRequester(
    private val classLoader: ClassLoader,
    private val application: Application
) {
    private var playerLyricsViewModel: Any? = null

    /**
     * 欺骗 Apple Music 触发歌词下载
     *
     * @see Apple.hookLyricBuildMethod
     */
    fun requestDownload(mediaId: String) {
        if (mediaId.isBlank()) {
            YLog.debug("LyricRequester: mediaId is null or blank")
            return
        }
        try {
            val song =
                XposedHelpers.newInstance(classLoader.loadClass("com.apple.android.music.model.Song"))
            XposedHelpers.callMethod(song, "setId", mediaId)
            XposedHelpers.callMethod(song, "setHasLyrics", true)

            if (playerLyricsViewModel == null) {
                playerLyricsViewModel = classLoader
                    .loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
                    .getConstructor(Application::class.java)
                    .newInstance(application)
            }

            XposedHelpers.callMethod(playerLyricsViewModel, "loadLyrics", song)
            YLog.debug("LyricRequester: Triggered download for $mediaId")

        } catch (e: Exception) {
            YLog.error("LyricRequester: Failed to trigger download", e)
        }
    }
}