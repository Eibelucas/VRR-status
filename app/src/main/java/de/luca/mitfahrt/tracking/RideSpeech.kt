package de.luca.mitfahrt.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import de.luca.mitfahrt.data.*
import java.time.Instant
import java.util.Locale

/** Where speech may be played: never, only on headphones, or on any active output. */
enum class SpeechRoute(val key: String) {
    OFF("off"), HEADPHONES("headphones"), ALWAYS("always");
    companion object { fun of(value: String) = entries.firstOrNull { it.key == value } ?: OFF }
}

data class AudioSituation(val headphones: Boolean, val inCall: Boolean, val doNotDisturb: Boolean)

object SpeechPolicy {
    /** Headphone mode stays silent without headphones; loud speaker output respects calls and Do not disturb. */
    fun allowed(route: SpeechRoute, situation: AudioSituation): Boolean = when (route) {
        SpeechRoute.OFF -> false
        SpeechRoute.HEADPHONES -> situation.headphones && !situation.inCall
        SpeechRoute.ALWAYS -> !situation.inCall && (situation.headphones || !situation.doNotDisturb)
    }

    /**
     * The next stop to announce, once per real progress. Only live forecasts or GPS-confirmed
     * progress count; a timetable guess or an uncertain position announces nothing.
     */
    fun announcement(state: TrackingState, now: Instant, lastIndex: Int?, gpsEnabled: Boolean): Pair<Int, String>? {
        val ride = state.ride?.takeIf { it.boarded && !it.demo && !it.departure.cancelled && state.running } ?: return null
        if (state.announcementsPaused) return null
        val info = NextStopResolver.resolve(state, now, gpsEnabled) ?: return null
        if (info.source != NextStopSource.LIVE && info.source != NextStopSource.GPS) return null
        val index = info.index ?: return null
        val stop = info.stop ?: return null
        // The boarding stop itself is not a next stop to announce.
        if (index <= ride.boardingIndex) return null
        // The first stop after boarding is the starting point; later only forward steps are announced.
        if (lastIndex != null && index <= lastIndex) return null
        val exit = ride.exitTargetIndex()
        val text = "Nächster Halt: ${shortName(stop.name)}" + if (index == exit) ". Hier aussteigen." else "."
        return index to text
    }
}

/**
 * Text to speech for the running ride. Created only when a speech option is on, released with
 * the service. Uses transient audio focus with ducking so music resumes afterwards.
 */
class RideSpeech(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var tts: TextToSpeech? = null
    private var ready = false
    private data class Request(val text: String, val route: SpeechRoute, val stillRelevant: () -> Boolean)

    private val main = Handler(Looper.getMainLooper())
    private var pending: Request? = null
    private var engineGeneration = 0
    private var utterance = 0L
    private var activeUtterance: String? = null
    private var focus: AudioFocusRequest? = null
    private var noisyRegistered = false
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    /** Headphones unplugged mid-sentence: stop at once instead of continuing on the speaker. */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            pending = null
            tts?.stop()
            release()
        }
    }

    fun situation(): AudioSituation {
        val outputs = runCatching { audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.map { it.type } }.getOrNull().orEmpty()
        val headphoneTypes = buildSet {
            addAll(listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET))
            if (Build.VERSION.SDK_INT >= 31) { add(AudioDeviceInfo.TYPE_BLE_HEADSET) }
        }
        val mode = audio?.mode ?: AudioManager.MODE_NORMAL
        val filter = runCatching { context.getSystemService(android.app.NotificationManager::class.java)?.currentInterruptionFilter }.getOrNull()
        return AudioSituation(outputs.any { it in headphoneTypes },
            mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION,
            filter != null && filter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    fun speak(text: String, route: SpeechRoute, stillRelevant: () -> Boolean = { true }) {
        if (!SpeechPolicy.allowed(route, situation())) return
        val request = Request(text, route, stillRelevant)
        if (tts == null) startEngine()
        if (ready) say(request) else pending = request
    }

    private fun startEngine() {
        val generation = ++engineGeneration
        val engine = TextToSpeech(context.applicationContext) { status ->
            main.post {
                if (generation != engineGeneration) return@post
                ready = status == TextToSpeech.SUCCESS
                val request = pending
                pending = null
                if (ready) {
                    tts?.language = Locale.GERMANY
                    tts?.setAudioAttributes(attributes)
                    request?.let(::say)
                } else {
                    tts?.shutdown()
                    tts = null
                }
            }
        }
        tts = engine
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) = finished(utteranceId)

            private fun finished(id: String?) {
                main.post { if (id != null && id == activeUtterance) release() }
            }
        })
    }

    private fun say(speech: Request) {
        val engine = tts ?: return
        // The output route can change while Android initializes its speech engine.
        if (!speech.stillRelevant() || !SpeechPolicy.allowed(speech.route, situation())) return
        release()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).build()
        if (audio?.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        focus = request
        if (!noisyRegistered) noisyRegistered = runCatching {
            context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        }.isSuccess
        val id = "mitfahrt-${++utterance}"
        activeUtterance = id
        if (engine.speak(speech.text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) release()
    }

    private fun release() {
        activeUtterance = null
        focus?.let { request -> runCatching { audio?.abandonAudioFocusRequest(request) } }
        focus = null
    }

    fun shutdown() {
        engineGeneration++
        release()
        if (noisyRegistered) runCatching { context.unregisterReceiver(noisy) }
        noisyRegistered = false
        tts?.shutdown(); tts = null; ready = false; pending = null
    }
}
