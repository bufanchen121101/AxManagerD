package frb.axeron.manager.ai

import android.speech.tts.TextToSpeech
import android.util.Log
import frb.axeron.manager.AxeronApplication.Companion.axeronApp
import java.util.Locale

/**
 * AI 回复朗读（1.4.0 新增，走系统 TTS，不需要任何云端语音服务）。
 *
 * 只在用户打开「自动朗读」时才初始化引擎；语速/音调跟随设置项。
 */
object AiSpeaker {

    private const val TAG = "AiSpeaker"
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    private fun ensure() {
        if (tts != null) return
        tts = TextToSpeech(axeronApp) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                try {
                    tts?.setLanguage(Locale.CHINA)
                    tts?.setSpeechRate(AIConfigStore.ttsRate)
                } catch (t: Throwable) {
                    Log.w(TAG, "TTS 初始化参数失败: " + (t.message ?: ""))
                }
                pending?.let { text ->
                    pending = null
                    speak(text)
                }
            } else {
                Log.w(TAG, "TTS 初始化失败 status=" + status)
            }
        }
    }

    fun speak(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        ensure()
        if (!ready) {
            pending = body
            return
        }
        try {
            tts?.setSpeechRate(AIConfigStore.ttsRate)
            // 系统 TTS 单次建议 <= 4000 字，超长截断，避免静默失败
            tts?.speak(body.take(4000), TextToSpeech.QUEUE_FLUSH, null, "axm-ai")
        } catch (t: Throwable) {
            Log.w(TAG, "朗读失败: " + (t.message ?: ""))
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (t: Throwable) {
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (t: Throwable) {
        }
        tts = null
        ready = false
        pending = null
    }
}
