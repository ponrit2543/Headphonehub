package com.example.headphonehub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import kotlinx.coroutines.flow.update
import java.util.Locale

class HeadphoneService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_PLAY_INDEX = "com.example.headphonehub.PLAY_INDEX"
        const val ACTION_TOGGLE = "com.example.headphonehub.TOGGLE"
        const val ACTION_NEXT = "com.example.headphonehub.NEXT"
        const val ACTION_PREVIOUS = "com.example.headphonehub.PREVIOUS"
        const val EXTRA_INDEX = "index"

        private const val CHANNEL_ID = "headphone_hub"
        private const val NOTIFICATION_ID = 42
        private const val DOUBLE_CLICK_WINDOW_MS = 350L
        private const val ROUTE_SETTLE_DELAY_MS = 1500L
        private const val ANNOUNCE_DEBOUNCE_MS = 5000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, HeadphoneService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HeadphoneService::class.java))
        }

        fun send(context: Context, action: String, index: Int? = null) {
            val intent = Intent(context, HeadphoneService::class.java).setAction(action)
            if (index != null) intent.putExtra(EXTRA_INDEX, index)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private lateinit var audioManager: AudioManager
    private lateinit var session: MediaSessionCompat

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingAnnounce = false

    private var player: MediaPlayer? = null
    private var nowPlaying = false
    private var hasFocus = false

    private var lastAnnounceAt = 0L
    private var clickCount = 0

    private val connected = linkedMapOf<String, String>()

    private val mediaAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(mediaAttrs)
            .setOnAudioFocusChangeListener { onFocusChange(it) }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        audioManager = getSystemService(AudioManager::class.java)
        createChannel()
        setUpSession()
        startInForeground()
        tts = TextToSpeech(this, this)
        seedConnectedDevices()
        registerConnectionReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action) {
            Intent.ACTION_MEDIA_BUTTON -> if (intent != null) MediaButtonReceiver.handleIntent(session, intent)
            ACTION_PLAY_INDEX -> playIndex(intent?.getIntExtra(EXTRA_INDEX, 0) ?: 0)
            ACTION_TOGGLE -> togglePlayPause()
            ACTION_NEXT -> skip(1)
            ACTION_PREVIOUS -> skip(-1)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { unregisterReceiver(connectionReceiver) }
        main.removeCallbacksAndMessages(null)
        tts?.shutdown()
        releasePlayer()
        if (hasFocus) audioManager.abandonAudioFocusRequest(focusRequest)
        session.isActive = false
        session.release()
        PlayerBus.headphone.value = null
        PlayerBus.state.update { it.copy(isPlaying = false) }
        super.onDestroy()
    }

    private fun setUpSession() {
        session = MediaSessionCompat(this, "HeadphoneHub").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean =
                    handleMediaButton(mediaButtonEvent)

                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = skip(1)
                override fun onSkipToPrevious() = skip(-1)
            })
            setMediaButtonReceiver(
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this@HeadphoneService, PlaybackStateCompat.ACTION_PLAY_PAUSE
                )
            )
            isActive = true
        }
        publishState()
    }

    private fun handleMediaButton(intent: Intent): Boolean {
        val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        } ?: return false

        val isDown = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> if (isDown) registerClick()
            KeyEvent.KEYCODE_MEDIA_NEXT -> if (isDown) skip(1)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (isDown) skip(-1)
            else -> return false
        }
        return true
    }

    private val resolveClicks = Runnable {
        val type = if (clickCount >= 2) ClickType.DOUBLE else ClickType.SINGLE
        clickCount = 0
        perform(settings.actionFor(type))
    }

    private fun registerClick() {
        clickCount++
        main.removeCallbacks(resolveClicks)
        main.postDelayed(resolveClicks, DOUBLE_CLICK_WINDOW_MS)
    }

    private fun perform(action: HeadphoneAction) {
        when (action) {
            HeadphoneAction.PLAY_PAUSE -> togglePlayPause()
            HeadphoneAction.NEXT -> skip(1)
            HeadphoneAction.PREVIOUS -> skip(-1)
            HeadphoneAction.VOLUME_UP -> audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI
            )
            HeadphoneAction.VOLUME_DOWN -> audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI
            )
            HeadphoneAction.NONE -> Unit
        }
    }

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val announce = !isInitialStickyBroadcast
            when (intent.action) {
                Intent.ACTION_HEADSET_PLUG -> {
                    if (intent.getIntExtra("state", 0) == 1) {
                        deviceConnected("wired", "Wired headphones", announce)
                    } else {
                        deviceDisconnected("wired")
                    }
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
                "android.bluetooth.headset.action.STATE_CHANGED" -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    val dev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val key = "bt:${dev?.address ?: "unknown"}"
                    val name = dev?.displayName ?: "Bluetooth device"
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        deviceConnected(key, name, announce)
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        deviceDisconnected(key)
                    }
                }
            }
        }
    }

    private val BluetoothDevice.displayName: String
        get() = try {
            if (ContextCompat.checkSelfPermission(
                    this@HeadphoneService,
                    android.Manifest.permission.BLUETOOTH_CONNECT
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) name ?: "Bluetooth device" else "Bluetooth device"
        } catch (_: Exception) {
            "Bluetooth device"
        }

    private fun registerConnectionReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction("android.bluetooth.headset.action.STATE_CHANGED")
        }
        registerReceiver(connectionReceiver, filter)
    }

    private fun seedConnectedDevices() {
        var foundWired = false
        if (Build.VERSION.SDK_INT >= 23) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (d in devices) {
                if (d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) {
                    deviceConnected("wired", "Wired headphones", announce = false)
                    foundWired = true
                    break
                }
            }
        }
        if (!foundWired && audioManager.isWiredHeadsetOn) {
            deviceConnected("wired", "Wired headphones", announce = false)
        }
    }

    private fun deviceConnected(key: String, name: String, announce: Boolean) {
        connected[key] = name
        updateBusHeadphone()

        if (!settings.listenerEnabled || !announce) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastAnnounceAt < ANNOUNCE_DEBOUNCE_MS) return
        lastAnnounceAt = now

        main.postDelayed({ announceReady() }, ROUTE_SETTLE_DELAY_MS)
    }

    private fun deviceDisconnected(key: String) {
        connected.remove(key)
        updateBusHeadphone()
    }

    private fun updateBusHeadphone() {
        val summary = if (connected.isEmpty()) null else connected.values.joinToString(", ")
        PlayerBus.headphone.value = summary
        startInForeground()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady = true
            if (pendingAnnounce) {
                pendingAnnounce = false
                announceReady()
            }
        }
    }

    private fun announceReady() {
        if (!settings.voiceAlertEnabled) return
        if (!ttsReady) {
            pendingAnnounce = true
            return
        }

        val focusResult = audioManager.requestAudioFocus(focusRequest)
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return

        val speakAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        tts?.setAudioAttributes(speakAttrs)

        val params = android.os.Bundle()
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        tts?.speak("Ready to use", TextToSpeech.QUEUE_FLUSH, params, "ready_alert")

        main.postDelayed({
            if (!nowPlaying) {
                audioManager.abandonAudioFocusRequest(focusRequest)
            }
        }, 2000L)
    }

    private fun playIndex(index: Int) {
        val queue = PlayerBus.state.value.queue
        if (index !in queue.indices) return

        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        hasFocus = true

        val song = queue[index]
        releasePlayer()

        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(mediaAttrs)
                setDataSource(applicationContext, song.uri)
                setOnCompletionListener { skip(1) }
                prepare()
                start()
            }
            nowPlaying = true
            PlayerBus.state.update { it.copy(currentIndex = index, isPlaying = true) }
            publishState()
            startInForeground()
        }.onFailure {
            nowPlaying = false
            PlayerBus.state.update { it.copy(isPlaying = false) }
            publishState()
        }
    }

    private fun togglePlayPause() {
        val state = PlayerBus.state.value
        if (state.queue.isEmpty()) return
        if (nowPlaying) {
            pause()
        } else {
            if (state.currentIndex in state.queue.indices) {
                resume()
            } else {
                playIndex(0)
            }
        }
    }

    private fun resume() {
        val p = player
        if (p != null) {
            if (audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                hasFocus = true
                p.start()
                nowPlaying = true
                PlayerBus.state.update { it.copy(isPlaying = true) }
                publishState()
                startInForeground()
            }
        } else {
            val state = PlayerBus.state.value
            val target = if (state.currentIndex in state.queue.indices) state.currentIndex else 0
            playIndex(target)
        }
    }

    private fun pause() {
        player?.pause()
        nowPlaying = false
        PlayerBus.state.update { it.copy(isPlaying = false) }
        publishState()
        startInForeground()
    }

    private fun skip(delta: Int) {
        val state = PlayerBus.state.value
        if (state.queue.isEmpty()) return
        val next = (state.currentIndex + delta).rem(state.queue.size).let {
            if (it < 0) it + state.queue.size else it
        }
        playIndex(next)
    }

    private fun releasePlayer() {
        player?.run {
            stop()
            release()
        }
        player = null
        nowPlaying = false
    }

    private fun onFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(0.2f, 0.2f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.setVolume(1.0f, 1.0f)
                if (nowPlaying) resume()
            }
        }
    }

    private fun publishState() {
        val state = PlayerBus.state.value
        val currentSong = state.queue.getOrNull(state.currentIndex)

        val pbState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(
                if (nowPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                1.0f
            )
            .build()
        session.setPlaybackState(pbState)

        if (currentSong != null) {
            val meta = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentSong.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentSong.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentSong.durationMs)
                .build()
            session.setMetadata(meta)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Headphone Status & Controls",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val state = PlayerBus.state.value
        val currentSong = state.queue.getOrNull(state.currentIndex)
        val dev = PlayerBus.headphone.value ?: "No headphones connected"

        val title = currentSong?.title ?: "Headphone Hub Active"
        val text = if (currentSong != null) "${currentSong.artist} • $dev" else dev

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleIntent = PendingIntent.getService(
            this, 1,
            Intent(this, HeadphoneService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val nextIntent = PendingIntent.getService(
            this, 2,
            Intent(this, HeadphoneService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseIcon = if (nowPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(playPauseIcon, if (nowPlaying) "Pause" else "Play", toggleIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .build()
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
