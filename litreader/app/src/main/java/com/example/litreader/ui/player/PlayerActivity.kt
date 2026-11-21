package com.example.litreader.ui.player

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.AudioAttributes
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.example.litreader.App
import com.example.litreader.R
import com.example.litreader.data.repo.BookRepository
import com.example.litreader.data.remote.HttpClient
import com.example.litreader.data.source.SourceRegistry
import com.example.litreader.data.source.VideoLink
import com.example.litreader.data.source.VideoSource
import com.example.litreader.databinding.ActivityPlayerBinding
import com.example.litreader.util.Haptics
import com.example.litreader.util.NetError
import com.example.litreader.util.ReadProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 影院区播放器：进页即解析直链并起播（m3u8 HLS 含 AES-128 分段 / mp4）。
 * 解析链路 read.php OP → 播放器页 → playerurl API，任一环失败进错误态可重试；
 * 播放位置按 tid+分段落 prefs，重开自动续播；多段视频用顶部 chips 切换。
 * 生命期：onStop 释放播放器（不后台占音频），位置先存；onStart 重建并续播。
 */
class PlayerActivity : AppCompatActivity() {
    private lateinit var bind: ActivityPlayerBinding
    private lateinit var tid: String
    private lateinit var sourceId: String
    private var threadTitle = ""

    private var player: ExoPlayer? = null
    private var dsFactory: OkHttpDataSource.Factory? = null
    private var videos: List<VideoLink> = emptyList()
    private var currentPart = 0
    /** 已解析完成、待 attach 的直链（onStart 重建播放器后消费） */
    private var pendingStream: String? = null
    /** 当前正在播的直链：onStop 释放播放器后，回前台据此重建并续播。 */
    private var currentStream: String? = null
    private var resolveJob: Job? = null
    private var startedOnce = false
    private var resumeMs = C.TIME_UNSET
    private var resumeHintShown = false
    /** 播放中报错是否已自动重解过一次（k/t 签名短时效，重解拿新令牌；每次起播重置）。 */
    private var autoRetried = false

    private val repo by lazy { BookRepository((application as App).database) }
    private val posPrefs by lazy { getSharedPreferences("reader_pos", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(bind.root)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        tid = intent.getStringExtra("tid") ?: ""
        threadTitle = intent.getStringExtra("title") ?: ""
        sourceId = intent.getStringExtra("sourceId") ?: ""
        bind.tvTitle.text = threadTitle
        bind.tvTitle.isSelected = true // 长标题跑马灯

        bind.btnBack.setOnClickListener { finish() }
        bind.btnRetry.setOnClickListener {
            Haptics.tick(it)
            resolvePart(currentPart, needVideos = videos.isEmpty())
        }
        bind.btnReader.setOnClickListener { openInReader() }
        bind.playerView.setControllerVisibilityListener(
            object : PlayerView.ControllerVisibilityListener {
                override fun onVisibilityChanged(visibility: Int) {
                    bind.topBar.visibility = visibility
                    bind.partsBar.visibility =
                        if (visibility == View.VISIBLE && videos.size > 1) View.VISIBLE else View.GONE
                }
            }
        )
        showLoading()
        resolvePart(0, needVideos = true)
    }

    override fun onStart() {
        super.onStart()
        val rebuilt = player == null
        ensurePlayer()
        if (rebuilt) {
            // 从后台/锁屏回来：播放器已重建，按已解析的直链恢复（位置从 prefs 续）
            (pendingStream ?: currentStream)?.let { attach(it) }
            pendingStream = null
        }
    }

    override fun onStop() {
        savePosition()
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        savePosition()
        releasePlayer()
        super.onDestroy()
    }

    /** 拉取视频列表（首次）并解析目标分段的直链。 */
    private fun resolvePart(part: Int, needVideos: Boolean) {
        resolveJob?.cancel()
        showLoading()
        resolveJob = lifecycleScope.launch {
            try {
                val src = SourceRegistry.get(sourceId) as? VideoSource
                    ?: throw IllegalStateException(getString(R.string.player_error))
                if (needVideos || videos.isEmpty()) {
                    videos = src.getVideos(tid)
                    if (videos.isEmpty()) {
                        showNone()
                        return@launch
                    }
                }
                val target = part.coerceIn(0, videos.size - 1)
                currentPart = target
                renderParts()
                val stream = src.resolveStream(videos[target])
                pendingStream = null
                if (isFinishing || isDestroyed) return@launch
                attach(stream)
            } catch (e: Exception) {
                if (isFinishing || isDestroyed) return@launch
                // 解析/播放失败：网络类异常翻译成中文，其余用业务文案（站点/接口异常）
                val msg = if (e is java.io.IOException) {
                    NetError.friendly(e)
                } else {
                    e.message?.takeIf { it.isNotBlank() } ?: getString(R.string.player_error)
                }
                showError(msg)
            }
        }
    }

    private fun ensurePlayer() {
        if (player != null) return
        // 数据源用媒体客户端：DoH 解析绕开 CDN 域名污染 + 忽略证书校验兜底，
        // 第三方 CDN 证书不匹配/被劫持时仍可取流（分段与 AES key 无凭据）；
        // UA 一致，Referer 在 attach 时按当前视频页补齐
        val ds = OkHttpDataSource.Factory(HttpClient.mediaOkHttp()).apply {
            setUserAgent(HttpClient.userAgent())
        }
        dsFactory = ds
        // 移动端播放策略：限制到 720p/2.5Mbps，省流量并避开高码率片源在低端机上的解码卡顿
        val selector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoSize(1280, 720)
                    .setMaxVideoBitrate(2_500_000)
            )
        }
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .setTrackSelector(selector)
            .build()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        // 拔耳机自动暂停（避免外放）
        p.setHandleAudioBecomingNoisy(true)
        p.addListener(listener)
        bind.playerView.player = p
        player = p
    }

    private fun releasePlayer() {
        player?.let {
            it.removeListener(listener)
            it.release()
            bind.playerView.player = null
        }
        player = null
    }

    private fun attach(stream: String) {
        val p = player ?: run { pendingStream = stream; return }
        hideStates()
        currentStream = stream
        // CDN 防盗链：Referer 用该视频所在播放器页的来源站
        val referer = videos.getOrNull(currentPart)?.let { link ->
            runCatching { java.net.URI(link.pageUrl).let { "${it.scheme}://${it.host}/" } }.getOrNull()
        }
        dsFactory?.setDefaultRequestProperties(
            if (referer.isNullOrEmpty()) emptyMap() else mapOf("Referer" to referer)
        )
        resumeMs = posPrefs.getString(posKey(), null)?.toLongOrNull() ?: C.TIME_UNSET
        resumeHintShown = false
        val item = MediaItem.Builder()
            .setUri(stream)
            .apply {
                if (stream.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
                else if (stream.contains(".mp4")) setMimeType(MimeTypes.VIDEO_MP4)
            }
            .build()
        p.setMediaItem(item, resumeMs)
        p.prepare()
        p.playWhenReady = true
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    hideStates()
                    autoRetried = false
                    if (!startedOnce) {
                        startedOnce = true
                        markThreadOpened()
                        maybeHintResume()
                    }
                }
                Player.STATE_ENDED -> posPrefs.edit().remove(posKey()).apply()
                else -> {}
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // 播放期 403/超时多为签名令牌过期或网络抖动：自动用新参数重解一次再报错
            if (!autoRetried) {
                autoRetried = true
                showLoading()
                resolvePart(currentPart, needVideos = videos.isEmpty())
                return
            }
            showError(NetError.friendly(error))
        }
    }

    /** 起播即记「已打开/已读」，返回列表时该条刷新为已读态。 */
    private fun markThreadOpened() {
        lifecycleScope.launch {
            runCatching {
                repo.markOpened(tid)
                repo.markRead(tid)
            }
        }
        ReadProgress.markDirty(this, sourceId, tid)
    }

    private fun maybeHintResume() {
        if (resumeHintShown || resumeMs == C.TIME_UNSET) return
        resumeHintShown = true
        val dur = player?.duration ?: 0L
        if (resumeMs > 30_000 && (dur <= 0 || resumeMs < dur - 60_000)) {
            val m = resumeMs / 60000
            val s = (resumeMs % 60000) / 1000
            Toast.makeText(
                this, getString(R.string.player_resume_hint, "%d:%02d".format(m, s)), Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 多段视频：顶部 chips（随控制层显隐），点击切段并按分段续播。 */
    private fun renderParts() {
        if (videos.size <= 1) return
        bind.partsRow.removeAllViews()
        videos.forEachIndexed { i, v ->
            val tv = TextView(this, null, 0, R.style.LitChip).apply {
                text = v.label.ifEmpty { getString(R.string.player_part_n, i + 1) }
                isSelected = i == currentPart
                minHeight = (40 * resources.displayMetrics.density).toInt()
                gravity = Gravity.CENTER
                setOnClickListener {
                    Haptics.tick(this)
                    if (i != currentPart) {
                        savePosition()
                        resolvePart(i, needVideos = false)
                        syncPartsBar()
                    }
                }
            }
            tv.layoutParams = chipParams()
            bind.partsRow.addView(tv)
        }
        syncPartsBar()
    }

    private fun syncPartsBar() {
        bind.partsBar.visibility =
            if (videos.size > 1 && bind.playerView.isControllerFullyVisible) View.VISIBLE else View.GONE
    }

    private fun chipParams(): ViewGroup.LayoutParams = ViewGroup.MarginLayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        (40 * resources.displayMetrics.density).toInt()
    ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }

    private fun savePosition() {
        val p = player ?: return
        val pos = p.currentPosition
        if (pos > 3000 && !p.isPlayingAd) {
            posPrefs.edit().putString(posKey(), pos.toString()).apply()
        }
    }

    private fun posKey(): String = if (videos.size > 1) "vpos_${tid}_$currentPart" else "vpos_$tid"

    private fun showLoading() {
        bind.errorBox.visibility = View.GONE
        bind.loadingBox.visibility = View.VISIBLE
    }

    private fun showError(msg: String) {
        bind.loadingBox.visibility = View.GONE
        bind.tvError.text = msg
        bind.errorBox.visibility = View.VISIBLE
    }

    /** 帖内没有视频（影院区的版规/公告类帖）：给阅读模式兜底入口。 */
    private fun showNone() {
        if (isFinishing || isDestroyed) return
        showError(getString(R.string.player_none))
    }

    private fun hideStates() {
        bind.loadingBox.visibility = View.GONE
        bind.errorBox.visibility = View.GONE
    }

    /** 非视频帖（版规等）兜底：走普通阅读器看楼层。 */
    private fun openInReader() {
        Haptics.tick(bind.btnReader)
        startActivity(
            Intent(this, com.example.litreader.ui.reader.ReaderActivity::class.java).apply {
                putExtra("tid", tid)
                putExtra("title", threadTitle)
                putExtra("sourceId", sourceId)
            }
        )
    }
}
