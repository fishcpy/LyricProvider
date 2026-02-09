/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.cmprovider.xposed

import android.content.Context
import android.os.FileObserver
import java.io.File

class LyricFileObserver(context: Context, callback: FileObserverCallback) {

    val downloadLyricDirectory = Constants.getDownloadLyricDirectory(context)

    /**
     * 一些版本的歌词路径
     */
    private val watchDirs by lazy {
        listOfNotNull(

            //9.4.65
            context.getExternalFilesDir("LrcCache"),

            context.getExternalFilesDir("LrcCache"),
            context.getExternalFilesDir("Cache/Lyric"),
            context.externalCacheDir?.let { File(it, "Cache/Lyric") },

            //离线音乐歌词
            context.getExternalFilesDir("LrcDownload"),
            context.getExternalFilesDir("Download/Lyric"),
        )
    }

    private val observers: List<FileObserver> by lazy {
        watchDirs.map { dir ->
            if (!dir.exists()) dir.mkdirs()

            @Suppress("DEPRECATION")
            object : FileObserver(dir.absolutePath, CREATE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null) return

                    val file = File(dir, path)
                    if (file.isFile) callback.onFileChanged(event, file)
                }
            }
        }
    }

    fun start() {
        observers.forEach { it.startWatching() }
    }

    fun stop() {
        observers.forEach { it.stopWatching() }
    }

    fun getFile(id: Long): File? {
        val fileName = id.toString()

        return watchDirs.asSequence()
            .map { File(it, fileName) }
            .firstOrNull { it.exists() && it.isFile }
            ?: File(downloadLyricDirectory, fileName).let {
                if (it.exists()) return it else null
            }
    }

    interface FileObserverCallback {
        fun onFileChanged(event: Int, file: File)
    }
}