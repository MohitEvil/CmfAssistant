package com.demon.cmfassistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The assistant's own sounds: tiny soft synth "dots" instead of Google's default listening beep.
 * Generated in code, so there is nothing to download and nothing to license.
 */
object Cue {
    private const val RATE = 44_100
    private val main = Handler(Looper.getMainLooper())

    /** Two quick rising dots: the mic is open. */
    private val listen: ShortArray by lazy { dots(listOf(Dot(1318.5, 0.040), Dot(1975.5, 0.055)), gap = 0.028, level = 0.30) }

    /** One soft low dot: the conversation is over. */
    private val close: ShortArray by lazy { dots(listOf(Dot(987.8, 0.070)), gap = 0.0, level = 0.24) }

    private class Dot(val hz: Double, val seconds: Double)

    private fun dots(list: List<Dot>, gap: Double, level: Double): ShortArray {
        val total = list.sumOf { it.seconds + gap } + 0.03
        val out = ShortArray((total * RATE).toInt())
        var offset = 0
        for (d in list) {
            val n = (d.seconds * RATE).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                val attack = (t / 0.004).coerceAtMost(1.0)            // 4 ms fade in, no click
                val decay = exp(-t * (5.5 / d.seconds))                 // quick, soft tail
                val tone = sin(2 * PI * d.hz * t) + 0.18 * sin(2 * PI * d.hz * 2 * t) // a touch of brightness
                val v = (tone * attack * decay * level * Short.MAX_VALUE).toInt()
                if (offset + i < out.size) out[offset + i] = v.coerceIn(-32767, 32767).toShort()
            }
            offset += n + (gap * RATE).toInt()
        }
        return out
    }

    fun listening() = play(listen)
    fun closing() = play(close)

    private fun play(pcm: ShortArray) {
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY) // its own stream, so muting the system beep doesn't mute this
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            main.postDelayed({ runCatching { track.release() } }, (pcm.size * 1000L / RATE) + 250)
        }
    }

    /**
     * Android's speech recogniser plays its own "ding" when it starts. There is no setting to change it, so the
     * streams it may use are muted for a moment around each start. Anything already muted is left alone.
     */
    fun hushSystemBeep(context: Context, ms: Long = 650) {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val streams = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)
            val muted = ArrayList<Int>()
            for (s in streams) {
                if (am.isStreamMute(s)) continue
                try {
                    am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                    muted.add(s)
                } catch (_: SecurityException) {
                }
            }
            main.postDelayed({
                for (s in muted) runCatching { am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) }
            }, ms)
        }
    }
}
