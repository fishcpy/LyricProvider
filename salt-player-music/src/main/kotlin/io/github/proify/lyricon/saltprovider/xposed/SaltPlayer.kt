/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.saltprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Bundle
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
    private var songGeneration: Int = 0

    @Volatile
    private var lastDuration: Long = -1L

    @Volatile
    private var cachedArtist: String? = null

    @Volatile
    private var cachedMediaId: String? = null

    @Volatile
    private var cachedDuration: Long = 0L

    // Pending lyrics waiting for provider/metadata to be ready
    @Volatile
    private var pendingLyrics: List<RichLyricLine>? = null

    // Flag: need to clear old lyrics when provider becomes available
    @Volatile
    private var needsClear: Boolean = false

    // Flag: DexKit hooks have been set up
    @Volatile
    private var dexKitHooksReady: Boolean = false

    // Saved class names for return-method hooks (set in onHook, used in onCreate)
    @Volatile
    private var savedDocClassName: String? = null

    @Volatile
    private var savedPkgClassName: String? = null

    override fun onHook() {
        hookMediaSession()

        // Hook Application.onCreate() with BEFORE callback to set up DexKit hooks
        // BEFORE Salt Player's onCreate() runs (which loads lyrics).
        // YukiHookAPI's onAppLifecycle { onCreate {} } fires AFTER, missing the constructors.
        "android.app.Application".toClass()
            .getDeclaredMethod("onCreate")
            .hook {
                before {
                    if (!dexKitHooksReady) {
                        val app = this.instance as? android.app.Application ?: return@before
                        val apkPath = app.applicationInfo?.sourceDir ?: return@before
                        YLog.debug("$TAG: Before onCreate, got APK path from Application: $apkPath")
                        hookWithDexKit(apkPath)
                    }
                }
            }

        onAppLifecycle {
            onCreate {
                setupProvider()
                // Fallback if before-hook didn't work
                if (!dexKitHooksReady) {
                    hookWithDexKit()
                }
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

        YLog.info("$TAG: Provider registered")

        // If we need to clear old lyrics (setSong(null) was skipped because provider was null)
        if (needsClear) {
            lyriconProvider?.player?.setSong(null)
            needsClear = false
        }

        // Try to send pending lyrics now that provider is ready
        flushLyrics()
    }

    private fun hookWithDexKit(apkPathOverride: String? = null) {
        if (dexKitHooksReady) return

        try {
            System.loadLibrary("dexkit")
        } catch (_: UnsatisfiedLinkError) { }

        val apkPath = apkPathOverride ?: appContext?.applicationInfo?.sourceDir ?: run {
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

            YLog.info("$TAG: DexKit found docClass=$docClassName, pkgClass=$pkgClassName")

            if (docClassName != null) {
                savedDocClassName = docClassName
                hookLyricsDocument(docClassName)
            }
            if (pkgClassName != null) {
                savedPkgClassName = pkgClassName
                hookLyricsPackage(pkgClassName)
            }

            if (docClassName == null && pkgClassName == null) {
                YLog.error("$TAG: Neither LyricsDocument nor LyricsPackage found!")
            }

            dexKitHooksReady = true
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

    private fun hookLyricsPackage(className: String) {
        val cls = className.toClass()
        val targetCtor = cls.declaredConstructors.firstOrNull { ctor ->
            val params = ctor.parameterTypes
            params.size == 3 && params[1] == String::class.java && params[2] == String::class.java
        } ?: cls.declaredConstructors.firstOrNull { it.parameterTypes.size >= 2 } ?: return

        targetCtor.hook {
            after {
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
        val gen = songGeneration

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
                cacheLyrics(richLines, gen)
                return
            }
        }

        if (!sourceText.isNullOrBlank()) parseAndSendFromLrc(sourceText!!, gen)
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractFromLyricsPackage(pkg: Any) {
        val gen = songGeneration

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
                if (songGeneration == gen) return
            } catch (_: Exception) { }
        }

        if (!sourceText.isNullOrBlank()) parseAndSendFromLrc(sourceText!!, gen)
    }

    private fun parseAndSendFromLrc(rawLrc: String, gen: Int) {
        if (songGeneration != gen) return
        val duration = cachedDuration
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
        cacheLyrics(richLines, gen)
    }

    /**
     * Cache lyrics and try to flush them if provider and metadata are ready.
     */
    private fun cacheLyrics(richLines: List<RichLyricLine>, gen: Int) {
        if (songGeneration != gen) return
        pendingLyrics = richLines
        YLog.debug("$TAG: Lyrics cached, ${richLines.size} lines, gen=$gen, provider=${lyriconProvider != null}")
        flushLyrics()
    }

    /**
     * Try to send pending lyrics to Lyricon if both provider and metadata are available.
     * This is the single point of lyrics sending - called whenever state changes.
     */
    private fun flushLyrics() {
        val lyrics = pendingLyrics ?: return
        val provider = lyriconProvider ?: return
        if (cachedDuration <= 0) return

        val song = Song(
            id = cachedMediaId ?: cachedDuration.toString(),
            name = cachedMediaId ?: "Unknown",
            artist = cachedArtist,
            duration = cachedDuration,
            lyrics = lyrics
        )
        provider.player.setSong(song)
        provider.player.setDisplayTranslation(true)
        pendingLyrics = null
        YLog.debug("$TAG: Lyrics sent, ${lyrics.size} lines")
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseLyricsLine(lineObj: Any): RichLyricLine? {
        var startTime = 0L
        var endTime = 0L
        var mainText: String? = null
        var subText: String? = null
        var cells: List<Any>? = null

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

    /**
     * Read string from MediaMetadata, with Bundle reflection fallback.
     */
    private fun readMetadataString(metadata: MediaMetadata, key: String): String? {
        metadata.getString(key)?.let { return it }

        return try {
            val bundleField = MediaMetadata::class.java.getDeclaredField("mBundle")
            bundleField.isAccessible = true
            val bundle = bundleField.get(metadata) as? Bundle
            when (val value = bundle?.get(key)) {
                is String -> value
                is CharSequence -> value.toString()
                else -> value?.toString()
            }
        } catch (e: Exception) {
            null
        }
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
                    val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

                    if (duration != lastDuration && duration > 0) {
                        lastDuration = duration

                        cachedArtist = readMetadataString(metadata, MediaMetadata.METADATA_KEY_ARTIST)
                            ?: readMetadataString(metadata, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                        cachedMediaId = readMetadataString(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID)
                        cachedDuration = duration

                        val oldGen = songGeneration
                        songGeneration++

                        // Clear old lyrics state on song change
                        if (pendingLyrics != null && oldGen > 0) {
                            pendingLyrics = null
                        }

                        YLog.info("$TAG: New song: duration=$duration, gen=$songGeneration")

                        // Clear old lyrics
                        lyriconProvider?.player?.setSong(null) ?: run { needsClear = true }

                        // Try to send new lyrics if already available
                        flushLyrics()
                    }
                }
            }
    }
}
