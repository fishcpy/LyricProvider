/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Application
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.SystemClock
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import de.robv.android.xposed.XposedHelpers
import io.github.proify.extensions.android.ScreenStateMonitor
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object Apple : YukiBaseHooker() {

    // --- 核心组件 ---
    private lateinit var application: Application
    private lateinit var classLoader: ClassLoader
    private var provider: LyriconProvider? = null

    // --- 状态追踪 ---
    private var isMusicPlaying = false
    private var lastPlaybackState: PlaybackState? = null

    // --- 协程调度 ---
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var progressSyncJob: Job? = null

    // --- 生命周期 ---

    override fun onHook() {
        onAppLifecycle {
            onCreate { setupModule() }
        }
    }

    private fun setupModule() {
        application = appContext ?: return
        classLoader = appClassLoader ?: return

        // 1. 初始化外部组件
        DiskSongManager.initialize(application)
        initPreferences()
        initScreenMonitor()

        // 2. 初始化核心 Provider
        setupLyriconProvider()

        // 3. 执行 Hook
        registerMediaHooks()
        registerLyricHooks()
    }

    // --- 初始化逻辑 ---

    private fun initPreferences() {
        PreferencesMonitor.apply {
            initialize(application)
            listener = object : PreferencesMonitor.Listener {
                override fun onTranslationSelectedChanged(selected: Boolean) {
                    provider?.player?.setDisplayTranslation(selected)
                }
            }
        }
    }

    private fun setupLyriconProvider() {
        provider = LyriconFactory.createProvider(
            context = application,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = application.packageName,
            logo = ProviderLogo.fromBase64(Constants.ICON)
        ).apply {
            player.setDisplayTranslation(PreferencesMonitor.isTranslationSelected())
            register()
        }

        PlaybackManager.init(
            remotePlayer = provider!!.player,
            requester = LyricRequester(classLoader, application)
        )
    }

    private fun initScreenMonitor() {
        ScreenStateMonitor.initialize(application)
        ScreenStateMonitor.addListener(object : ScreenStateMonitor.ScreenStateListener {
            override fun onScreenOn() {
                if (isMusicPlaying) startProgressSync()
            }

            override fun onScreenOff() {
                stopProgressSync()
            }

            override fun onScreenUnlocked() {
                if (isMusicPlaying && progressSyncJob == null) startProgressSync()
            }
        })
    }

    // --- Hook 注册 ---

    private fun registerMediaHooks() {
        "android.media.session.MediaSession".toClass().resolve().apply {
            // 监听播放状态
            firstMethod {
                name = "setPlaybackState"
                parameters(PlaybackState::class.java)
            }.hook {
                after {
                    val state = args[0] as? PlaybackState ?: return@after
                    lastPlaybackState = state

                    when (state.state) {
                        PlaybackState.STATE_PLAYING -> handlePlaybackStart()
                        PlaybackState.STATE_PAUSED,
                        PlaybackState.STATE_STOPPED -> handlePlaybackStop()

                        else -> Unit
                    }
                }
            }

            // 监听切歌元数据
            firstMethod {
                name = "setMetadata"
                parameters("android.media.MediaMetadata")
            }.hook {
                after {
                    val metadata = args[0] as? MediaMetadata ?: return@after
                    val cached = MediaMetadataCache.putAndGet(metadata) ?: return@after
                    PlaybackManager.onSongChanged(cached.id)
                }
            }
        }
    }

    private fun registerLyricHooks() {
        classLoader.loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
            .resolve()
            .firstMethod { name = "buildTimeRangeToLyricsMap" }
            .hook {
                after {
                    val songNative = XposedHelpers.callMethod(args[0] ?: return@after, "get")
                    PlaybackManager.onLyricsBuilt(songNative)
                }
            }
    }

    // --- 进度同步控制 ---

    private fun handlePlaybackStart() {
        if (isMusicPlaying) return
        isMusicPlaying = true
        provider?.player?.setPlaybackState(true)
        startProgressSync()
    }

    private fun handlePlaybackStop() {
        isMusicPlaying = false
        provider?.player?.setPlaybackState(false)
        stopProgressSync()
    }

    private fun startProgressSync() {
        if (progressSyncJob?.isActive == true) return
        progressSyncJob = scope.launch {
            while (isActive && isMusicPlaying) {
                val currentPos = calculateRealtimePosition()
                provider?.player?.setPosition(currentPos)
                delay(ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL)
            }
        }
    }

    private fun stopProgressSync() {
        progressSyncJob?.cancel()
        progressSyncJob = null
    }

    private fun calculateRealtimePosition(): Long {
        val state = lastPlaybackState ?: return 0L
        if (state.state != PlaybackState.STATE_PLAYING) return state.position

        val timeDiff = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        return state.position + (timeDiff * state.playbackSpeed).toLong()
    }
}