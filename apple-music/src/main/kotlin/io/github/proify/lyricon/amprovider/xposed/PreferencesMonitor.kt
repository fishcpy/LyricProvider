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

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

@SuppressLint("StaticFieldLeak")
object PreferencesMonitor {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    var listener: Listener? = null

    fun initialize(context: Context) {
        if (::context.isInitialized) return
        this.context = context.applicationContext
        prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)

        XposedHelpers.findAndHookMethod(
            "com.apple.android.music.utils.AppSharedPreferences",
            context.classLoader,
            "setLyricsTranslationSelected",
            Boolean::class.javaPrimitiveType,
            object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun afterHookedMethod(param: MethodHookParam?) {
                    listener?.onTranslationSelectedChanged(param?.args[0] as Boolean)
                }
            })
    }

    fun isTranslationSelected(): Boolean =
        prefs.getBoolean("key_player_lyrics_translation_selected", false)

    interface Listener {
        fun onTranslationSelectedChanged(selected: Boolean)
    }
}