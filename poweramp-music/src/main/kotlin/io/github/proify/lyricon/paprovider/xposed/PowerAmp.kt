/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.paprovider.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import com.kyant.taglib.TagLib
import io.github.proify.cloudlyric.ProviderLyrics
import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.paprovider.bridge.BridgeConstants
import io.github.proify.lyricon.paprovider.bridge.Configs
import io.github.proify.lyricon.paprovider.xposed.util.SafUriResolver
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object PowerAmp : YukiBaseHooker(), DownloadCallback {
    private const val TAG = "PowerAmpProvider"
    private const val ACTION_TRACK_CHANGED = "com.maxmpz.audioplayer.TRACK_CHANGED"

    // 匹配元数据key
    private val lyricTagRegex by lazy { Regex("(?i)\\b(LYRICS)\\b") }

    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var syncJob: Job? = null
    private var provider: LyriconProvider? = null
    private var trackReceiver: BroadcastReceiver? = null

    private var lastPlaybackState: PlaybackState? = null
    private var currentMetadata: TrackMetadata? = null
    private var isPlaying = false

    // --- Hook 入口 ---

    override fun onHook() {
        onAppLifecycle {
            onCreate {
                initDataChannel()
                setupLyriconProvider(this)
                setupBroadcastReceiver(this)
            }
            onTerminate { release() }
        }
        hookMediaSession()
    }

    // --- 初始化与核心配置 ---

    /**
     * 初始化与宿主 App 的数据通道。
     */
    private fun initDataChannel() {
        dataChannel.wait(key = BridgeConstants.ACTION_SETTING_CHANGED) {
            YLog.info(tag = TAG, msg = "Settings changed signal received")
            applyProviderSettings()
        }
    }

    /**
     * 配置 Lyricon 提供者实例。
     */
    private fun setupLyriconProvider(context: Context) {
        provider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromSvg(Constants.ICON)
        ).apply {
            register()
        }
        applyProviderSettings()
    }

    /**
     * 应用当前配置项。
     */
    private fun applyProviderSettings() {
        val isTranslationEnabled = prefs.get(Configs.ENABLE_TRANSLATION)
        provider?.player?.setDisplayTranslation(isTranslationEnabled)
        YLog.debug(tag = TAG, msg = "Settings applied: translationEnabled=$isTranslationEnabled")
    }

    /**
     * 注册轨道变化广播接收器。
     */
    private fun setupBroadcastReceiver(context: Context) {
        val filter = IntentFilter(ACTION_TRACK_CHANGED)
        trackReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_TRACK_CHANGED) {
                    handleTrackChange(intent)
                }
            }
        }.also {
            ContextCompat.registerReceiver(context, it, filter, ContextCompat.RECEIVER_EXPORTED)
        }
    }

    // --- 播放状态与进度同步 ---

    /*
     * Hook MediaSession 以获取实时播放状态。
     */
    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass()
            .resolve()
            .apply {
                firstMethod {
                    name = "setPlaybackState"
                    parameters(PlaybackState::class.java)
                }.hook {
                    after {
                        val state = args[0] as? PlaybackState ?: return@after
                        lastPlaybackState = state

                        when (state.state) {
                            PlaybackState.STATE_PLAYING -> updatePlaybackState(true)
                            PlaybackState.STATE_PAUSED,
                            PlaybackState.STATE_STOPPED -> updatePlaybackState(false)

                            else -> Unit
                        }
                    }
                }
            }
    }

    /**
     * 更新播放状态并管理进度同步任务。
     * @param playing 是否正在播放
     */
    private fun updatePlaybackState(playing: Boolean) {
        if (this.isPlaying == playing) return
        this.isPlaying = playing

        provider?.player?.setPlaybackState(playing)
        YLog.debug(tag = TAG, msg = "Playback state changed: isPlaying=$playing")

        if (playing) startSyncTask() else stopSyncTask()
    }

    private fun startSyncTask() {
        if (syncJob?.isActive == true) return
        syncJob = coroutineScope.launch {
            while (isActive) {
                val position = calculateCurrentPosition()
                provider?.player?.setPosition(position)
                delay(ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL)
            }
        }
    }

    private fun stopSyncTask() {
        syncJob?.cancel()
        syncJob = null
    }

    /**
     * 基于上次状态快照计算当前实时播放位置。
     */
    private fun calculateCurrentPosition(): Long {
        val state = lastPlaybackState ?: return 0L
        if (state.state != PlaybackState.STATE_PLAYING) return state.position

        val elapsedSinceUpdate = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        return state.position + (elapsedSinceUpdate * state.playbackSpeed).toLong()
    }

    // --- 轨道处理与歌词匹配 ---

    /**
     * 处理轨道变化逻辑。
     */
    private fun handleTrackChange(intent: Intent) {
        val bundle = intent.extras ?: return
        val metadata = TrackMetadataCache.save(bundle) ?: return

        if (currentMetadata == metadata) return
        currentMetadata = metadata

        val rawPath = metadata.path ?: return
        val formattedPath = formatSafPath(rawPath) ?: return
        val uri = SafUriResolver.resolveToUri(appContext!!, formattedPath) ?: return

        // 先发送基础歌曲信息（清除旧歌词）
        updateSong(Song(name = metadata.title, artist = metadata.artist))

        // 尝试加载本地歌词
        val hasLocalLyric = loadLyricsFromUri(metadata, uri)

        // 本地加载失败则根据配置尝试网络搜索
        if (!hasLocalLyric) {
            val isNetSearchEnabled = prefs.get(Configs.ENABLE_NET_SEARCH)
            if (isNetSearchEnabled) {
                YLog.debug(
                    tag = TAG,
                    msg = "Local lyric not found, triggering net search for: ${metadata.title}"
                )
                searchLyricsOnline(metadata)
            } else {
                YLog.debug(tag = TAG, msg = "Local lyric not found and net search is disabled")
            }
        }
    }

    /**
     * 从 URI 读取并解析本地歌词。
     */
    private fun loadLyricsFromUri(data: TrackMetadata, uri: Uri): Boolean {
        val rawLyric = fetchLyricFromTag(uri) ?: return false

        val parsedLrc = EnhanceLrcParser.parse(rawLyric, data.duration).lines.filter {
            !it.text.isNullOrBlank()
        }

        val song = Song(
            id = data.id,
            name = data.title,
            artist = data.artist,
            duration = data.duration,
            lyrics = parsedLrc
        )

        updateSong(song)
        YLog.info(tag = TAG, msg = "Local lyric loaded for: ${data.title}")
        return true
    }

    /**
     * 使用 TagLib 提取音频标签中的歌词字段。
     */
    private fun fetchLyricFromTag(uri: Uri): String? = try {
        appContext?.contentResolver?.openFileDescriptor(uri, "r")?.use { pfd ->
            TagLib.getMetadata(pfd.dup().detachFd())?.let { metadata ->
                metadata.propertyMap.entries.firstOrNull { (key, _) ->
                    lyricTagRegex.matches(key)
                }?.value?.firstOrNull()
            }
        }
    } catch (e: Exception) {
        YLog.error(tag = TAG, msg = "Failed to fetch lyric tag from $uri", e = e)
        null
    }

    private fun searchLyricsOnline(metadata: TrackMetadata) {
        Downloader.search(metadata, this) {
            trackName = metadata.title
            artistName = metadata.artist
            albumName = metadata.album
        }
    }

    /**
     * 将 PowerAmp 路径格式转换为 SAF 兼容路径。
     * 例：`primary/Music/test.mp3` -> `primary:Music/test.mp3`
     */
    private fun formatSafPath(path: String): String? {
        val input = path.trimStart()
        if (input.isEmpty() || input.startsWith("/")) return null

        val separatorIndex = input.indexOf('/')
        if (separatorIndex == -1) return null

        val volumeId = input.take(separatorIndex)
        val relativePath = input.substring(separatorIndex + 1)

        return if (volumeId.isNotEmpty()) "$volumeId:$relativePath" else null
    }

    /**
     * 向歌词提供者更新当前歌曲信息。
     */
    private fun updateSong(song: Song?) {
        YLog.debug(tag = TAG, msg = "Updating song: id=${song?.id}, title=${song?.name}")
        provider?.player?.setSong(song)
    }

    // --- 回调与生命周期释放 ---

    override fun onDownloadFinished(metadata: TrackMetadata, response: List<ProviderLyrics>) {
        if (metadata != currentMetadata) return

        val richLyrics = response.firstOrNull()?.lyrics?.rich
        val song = Song(
            id = metadata.id,
            name = metadata.title,
            artist = metadata.artist,
            duration = metadata.duration,
            lyrics = richLyrics
        )
        updateSong(song)
        YLog.info(tag = TAG, msg = "Online lyric applied for: ${metadata.title}")
    }

    override fun onDownloadFailed(metadata: TrackMetadata, e: Exception) {
        YLog.error(tag = TAG, msg = "Online lyric download failed for: ${metadata.title}", e = e)
    }

    private fun release() {
        stopSyncTask()
        coroutineScope.cancel()
        trackReceiver?.let { appContext?.unregisterReceiver(it) }
        trackReceiver = null
        YLog.info(tag = TAG, msg = "PowerAmp provider released")
    }
}