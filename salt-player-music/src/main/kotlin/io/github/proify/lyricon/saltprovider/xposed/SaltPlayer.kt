/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.saltprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

object SaltPlayer : YukiBaseHooker() {

    private const val TAG = "SaltPlayer"
    private const val SALT_PKG = "com.salt.music"

    private var lyriconProvider: LyriconProvider? = null

    @Volatile
    private var currentMetadata: MediaMetadata? = null

    @Volatile
    private var songSent: Boolean = false

    @Volatile
    private var lastDuration: Long = -1L

    override fun onHook() {
        hookMediaSession()
        onAppLifecycle {
            onCreate {
                setupProvider()
                hookWithDexKit()
            }
        }
    }

    private fun setupProvider() {
        lyriconProvider = LyriconFactory.createProvider(
            appContext!!,
            Constants.PROVIDER_PACKAGE_NAME,
            SALT_PKG,
            ProviderLogo.fromBase64(Constants.ICON)
        ).apply { register() }
    }

    private fun hookWithDexKit() {
        try {
            System.loadLibrary("dexkit")
        } catch (_: UnsatisfiedLinkError) { }

        val apkPath = appContext?.applicationInfo?.sourceDir ?: run {
            YLog.error("$TAG: Cannot get APK path")
            return
        }

        val dexKit = DexKitBridge.create(apkPath) ?: run {
            YLog.error("$TAG: Cannot create DexKitBridge")
            return
        }

        try {
            val docClassName = dexKit.findClass {
                matcher {
                    addUsingString("LyricsDocument(sourceText=", StringMatchType.Contains)
                }
            }.firstOrNull()?.name

            val pkgClassName = dexKit.findClass {
                matcher {
                    addUsingString("LRC FILE ", StringMatchType.Contains)
                }
            }.firstOrNull()?.name

            if (docClassName != null) hookLyricsDocument(docClassName)
            if (pkgClassName != null) hookLyricsPackage(pkgClassName)

            if (docClassName == null && pkgClassName == null) {
                YLog.error("$TAG: Neither LyricsDocument nor LyricsPackage found!")
            }
        } finally {
            dexKit.close()
        }
    }

    private fun hookLyricsDocument(className: String) {
        val cls = className.toClass()
        val targetCtor = cls.declaredConstructors.firstOrNull { it.parameterTypes.size >= 2 }
            ?: cls.declaredConstructors.firstOrNull() ?: return

        targetCtor.hook {
            after {
                try {
                    extractFromLyricsDocument(this.instance)
                } catch (e: Throwable) {
                    YLog.error("$TAG: Failed to extract from LyricsDocument: ${e.message}")
                }
            }
        }
    }

    private var pendingRawLrc: String? = null

    private fun hookLyricsPackage(className: String) {
        val cls = className.toClass()
        val targetCtor = cls.declaredConstructors.firstOrNull { ctor ->
            val params = ctor.parameterTypes
            params.size == 3 && params[1] == String::class.java && params[2] == String::class.java
        } ?: cls.declaredConstructors.firstOrNull { it.parameterTypes.size >= 2 } ?: return

        targetCtor.hook {
            after {
                val rawText = args.lastOrNull { it is String } as? String
                if (rawText != null && rawText.lines().size > 3) {
                    pendingRawLrc = rawText
                }
                try {
                    extractFromLyricsPackage(this.instance)
                } catch (e: Throwable) {
                    YLog.error("$TAG: Failed to extract from LyricsPackage: ${e.message}")
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractFromLyricsDocument(doc: Any) {
        if (songSent) return

        var lyricsLines: List<Any>? = null
        var sourceText: String? = null

        for (field in doc.javaClass.declaredFields) {
            field.isAccessible = true
            val value = field.get(doc) ?: continue
            if (value is String && sourceText == null) sourceText = value
            else if (value is List<*> && lyricsLines == null) lyricsLines = value as List<Any>
        }

        if (!lyricsLines.isNullOrEmpty()) {
            val richLines = lyricsLines!!.mapNotNull { lineObj ->
                try { parseLyricsLine(lineObj) } catch (_: Exception) { null }
            }
            if (richLines.isNotEmpty()) {
                sendStructuredLyrics(richLines)
                return
            }
        }

        if (!sourceText.isNullOrBlank()) parseAndSendFromLrc(sourceText!!)
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractFromLyricsPackage(pkg: Any) {
        if (songSent) return

        var document: Any? = null
        var sourceText: String? = null

        for (field in pkg.javaClass.declaredFields) {
            field.isAccessible = true
            val value = field.get(pkg) ?: continue
            if (value is String && sourceText == null) sourceText = value
            else if (document == null && value !is String && value !is List<*> && value !is Enum<*>) document = value
        }

        if (document != null) {
            try {
                extractFromLyricsDocument(document!!)
                if (songSent) return
            } catch (_: Exception) { }
        }

        if (!sourceText.isNullOrBlank()) parseAndSendFromLrc(sourceText!!)
    }

    private fun parseAndSendFromLrc(rawLrc: String) {
        if (songSent) return
        val duration = currentMetadata?.duration ?: 0L
        val doc = EnhanceLrcParser.parse(rawLrc, duration)
        if (doc.lines.isEmpty()) return

        val richLines = doc.lines.map { line ->
            RichLyricLine(
                begin = line.begin,
                end = line.end,
                duration = line.duration,
                isAlignedRight = line.isAlignedRight,
                metadata = null,
                text = line.text,
                words = line.words,
                secondary = line.secondary,
                secondaryWords = line.secondaryWords,
                translation = null,
                translationWords = null,
                roma = null
            )
        }
        sendStructuredLyrics(richLines)
    }

    private fun sendStructuredLyrics(richLines: List<RichLyricLine>) {
        val metadata = currentMetadata
        val song = Song(
            id = metadata?.mediaId ?: "",
            name = metadata?.title,
            artist = metadata?.artist,
            duration = metadata?.duration ?: 0L,
            lyrics = richLines
        )
        lyriconProvider?.player?.setSong(song)
        lyriconProvider?.player?.setDisplayTranslation(true)
        songSent = true
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseLyricsLine(lineObj: Any): RichLyricLine? {
        var startTime = 0L
        var endTime = 0L
        var mainText: String? = null
        var subText: String? = null
        var cells: List<Any>? = null
        var wordsData: ArrayList<Any>? = null

        for (field in lineObj.javaClass.declaredFields) {
            field.isAccessible = true
            val value = field.get(lineObj) ?: continue
            when {
                value is Long -> {
                    if (startTime == 0L) startTime = value
                    else if (endTime == 0L) endTime = value
                }
                value is String && mainText == null -> mainText = value
                value is String && mainText != null && subText == null -> subText = value
                value is List<*> && cells == null -> cells = value as List<Any>
                value is ArrayList<*> && wordsData == null -> wordsData = value as ArrayList<Any>
            }
        }

        if (mainText.isNullOrBlank()) return null

        val displayText: String
        val displayTranslation: String?

        if (!subText.isNullOrBlank()) {
            displayText = subText
            displayTranslation = mainText
        } else {
            displayText = mainText!!
            displayTranslation = null
        }

        val words = cells?.mapNotNull { cell -> parseLyricsCell(cell) } ?: emptyList()

        return RichLyricLine(
            begin = startTime,
            end = endTime,
            duration = if (endTime > startTime) endTime - startTime else 0L,
            isAlignedRight = false,
            metadata = null,
            text = displayText,
            words = words,
            secondary = null,
            secondaryWords = null,
            translation = displayTranslation,
            translationWords = null,
            roma = null
        )
    }

    private fun parseLyricsCell(cellObj: Any): LyricWord? {
        var startTime = 0L
        var endTime = 0L
        var text: String? = null

        for (field in cellObj.javaClass.declaredFields) {
            field.isAccessible = true
            val value = field.get(cellObj) ?: continue
            when {
                value is Long -> {
                    if (startTime == 0L) startTime = value
                    else if (endTime == 0L) endTime = value
                }
                value is String && text == null -> text = value
            }
        }

        if (text.isNullOrBlank()) return null
        return LyricWord(
            begin = startTime,
            end = endTime,
            duration = if (endTime > startTime) endTime - startTime else 0L,
            text = text,
            metadata = null
        )
    }

    private fun parseWordFromPair(pairObj: Any): LyricWord? {
        for (field in pairObj.javaClass.declaredFields) {
            field.isAccessible = true
            val value = field.get(pairObj) ?: continue
            if (value !is String && value !is Long && value !is Number) {
                return parseLyricsCell(value)
            }
        }
        return null
    }

    private fun hookMediaSession() {
        val sessionClass = "android.media.session.MediaSession".toClass()

        sessionClass.getDeclaredMethod("setPlaybackState", PlaybackState::class.java)
            .hook {
                after {
                    val state = args[0] as? PlaybackState ?: return@after
                    lyriconProvider?.player?.setPlaybackState(state)
                }
            }

        sessionClass.getDeclaredMethod("setMetadata", MediaMetadata::class.java)
            .hook {
                after {
                    val metadata = args[0] as? MediaMetadata ?: return@after
                    val duration = metadata.duration
                    if (duration != lastDuration && duration > 0) {
                        lastDuration = duration
                        currentMetadata = metadata
                        songSent = false
                    }
                }
            }
    }

    private val MediaMetadata.mediaId: String?
        get() = getString(MediaMetadata.METADATA_KEY_MEDIA_ID)

    private val MediaMetadata.title: String?
        get() = getString(MediaMetadata.METADATA_KEY_TITLE)

    private val MediaMetadata.artist: String?
        get() = getString(MediaMetadata.METADATA_KEY_ARTIST)

    private val MediaMetadata.duration: Long
        get() = getLong(MediaMetadata.METADATA_KEY_DURATION)
}
