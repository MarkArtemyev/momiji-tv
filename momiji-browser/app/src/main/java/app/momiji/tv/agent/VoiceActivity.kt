package app.momiji.tv.agent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent

class VoiceActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Что сделать?")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(intent).setPackage(KATNISS), REQUEST)
        } catch (e: ActivityNotFoundException) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST)
            } catch (e2: ActivityNotFoundException) {
                AgentService.instance?.finish("На телевизоре нет голосового ввода.")
                finish()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val text = if (resultCode == RESULT_OK) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() else null
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        AgentService.instance?.onSpeech(text)
    }

    companion object {
        const val KATNISS = "com.google.android.katniss"
        private const val REQUEST = 11
    }
}
