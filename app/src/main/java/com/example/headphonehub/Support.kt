package com.example.headphonehub

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

// ---------------------------------------------------------------- models

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val uri: Uri,
)

// เพิ่ม TRIPLE เข้าไปตรงนี้
enum class ClickType { SINGLE, DOUBLE, TRIPLE }

enum class HeadphoneAction(val label: String) {
    PLAY_PAUSE("Play / Pause"),
    NEXT("Next track"),
    PREVIOUS("Previous track"),
    VOLUME_UP("Volume up"),
    VOLUME_DOWN("Volume down"),
    NONE("Do nothing"),
}

// ---------------------------------------------------------------- shared state (UI <-> service)

data class PlayerUiState(
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
)

object PlayerBus {
    val state = MutableStateFlow(PlayerUiState())

    /** e.g. "Wired headphones, Sony WH-1000XM4"; null when nothing is connected. */
    val headphone = MutableStateFlow<String?>(null)
}

// ---------------------------------------------------------------- settings

/** Tiny SharedPreferences wrapper. The service reads it fresh on every event, so UI changes apply instantly. */
class Settings(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("headphone_hub", Context.MODE_PRIVATE)

    fun actionFor(type: ClickType): HeadphoneAction {
        val default = when (type) {
            ClickType.SINGLE -> HeadphoneAction.PLAY_PAUSE
            ClickType.DOUBLE -> HeadphoneAction.NEXT
            ClickType.TRIPLE -> HeadphoneAction.PREVIOUS // กำหนดค่าเริ่มต้นสำหรับการคลิก 3 ครั้ง
        }
        val stored = prefs.getString(type.name, null) ?: return default
        return runCatching { HeadphoneAction.valueOf(stored) }.getOrDefault(default)
    }

    fun setAction(type: ClickType, action: HeadphoneAction) {
        prefs.edit().putString(type.name, action.name).apply()
    }

    var voiceAlertEnabled: Boolean
        get() = prefs.getBoolean("voice_alert", true)
        set(value) = prefs.edit().putBoolean("voice_alert", value).apply()

    var listenerEnabled: Boolean
        get() = prefs.getBoolean("listener_enabled", true)
        set(value) = prefs.edit().putBoolean("listener_enabled", value).apply()
}

// ---------------------------------------------------------------- MediaStore scan

object MediaScanner {

    val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED

    /** Queries MediaStore for music files. Call off the main thread. */
    fun scan(context: Context): List<Song> {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
        )
        val songs = mutableListOf<Song>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val artist = c.getString(artistCol)
                songs += Song(
                    id = id,
                    title = c.getString(titleCol) ?: "Unknown title",
                    artist = if (artist.isNullOrBlank() || artist == "<unknown>") "Unknown artist" else artist,
                    durationMs = c.getLong(durCol),
                    uri = ContentUris.withAppendedId(collection, id),
                )
            }
        }
        return songs
    }
}
