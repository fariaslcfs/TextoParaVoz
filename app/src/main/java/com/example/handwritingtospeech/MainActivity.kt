package com.example.handwritingtospeech

import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import java.util.Locale
import android.view.inputmethod.InputMethodManager
import androidx.core.graphics.toColorInt

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var handwritingView: HandwritingView
    private lateinit var typedEditText: EditText
    private lateinit var recognizedTextView: TextView
    private lateinit var tts: TextToSpeech
    private lateinit var recognizer: DigitalInkRecognizer
    private lateinit var inputModeRadioGroup: RadioGroup
    private lateinit var rootLayout: View
    private lateinit var txtModelStatus: TextView

    private var modelReady = false
    private val REQUIRED_VOICE = "pt-br-x-ptd-local"
    private val REQUIRED_LOCALE = Locale("pt", "BR")
    private lateinit var txtVoiceStatus: TextView
    private var voiceReady = false
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        handwritingView = findViewById(R.id.handwritingView)
        typedEditText = findViewById(R.id.editTextTyped)
        recognizedTextView = findViewById(R.id.txtRecognized)
        inputModeRadioGroup = findViewById(R.id.radioGroupInputMode)
        txtVoiceStatus = findViewById(R.id.txtVoiceStatus)
        rootLayout = findViewById(R.id.rootLayout)
        txtModelStatus = findViewById(R.id.txtModelStatus)

        val btnSpeak: Button = findViewById(R.id.btnSpeak)
        btnSpeak.isEnabled = false          // ← Desabilita inicialmente
        btnSpeak.text = "Aguarde" // ← Opcional: muda texto para dar feedback
        tts = TextToSpeech(this, this)
        val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("pt-BR")!!
        val model = DigitalInkRecognitionModel.builder(modelIdentifier).build()
        val modelManager = RemoteModelManager.getInstance()

        // Primeiro: checa se já está baixado
        modelManager.isModelDownloaded(model)
            .addOnSuccessListener { alreadyDownloaded ->
                if (alreadyDownloaded) {
                    // Já existe → pronto imediatamente!
                    modelReady = true
                    recognizer = DigitalInkRecognition.getClient(
                        DigitalInkRecognizerOptions.builder(model).build()
                    )
                    btnSpeak.isEnabled = true
                    btnSpeak.text = "FALAR"
                    txtModelStatus.visibility = View.GONE  // esconde qualquer status
                    // Opcional: Toast.makeText(this, "Modelo pt-BR já carregado", Toast.LENGTH_SHORT).show()
                } else {
                    // Não existe → inicia download e mostra status
                    txtModelStatus.text = "Baixando modelo - primeira vez ~20 MB"
                    txtModelStatus.visibility = View.VISIBLE
                    btnSpeak.isEnabled = false
                    btnSpeak.text = "Aguarde..."

                    val conditions = DownloadConditions.Builder()
                        .requireWifi()  // ou .build() para permitir dados móveis
                        .build()

                    modelManager.download(model, conditions)
                        .addOnSuccessListener {
                            modelReady = true
                            recognizer = DigitalInkRecognition.getClient(
                                DigitalInkRecognizerOptions.builder(model).build()
                            )
                            btnSpeak.isEnabled = true
                            btnSpeak.text = "FALAR"
                            txtModelStatus?.visibility = View.GONE
                            Toast.makeText(this, "Modelo carregado com sucesso!", Toast.LENGTH_SHORT).show()
                            playSuccessBeep()
                        }
                        .addOnFailureListener { e ->
                            modelReady = false
                            btnSpeak.isEnabled = false
                            btnSpeak.text = "Erro no modelo"
                            txtModelStatus.text = "Falha ao baixar modelo.\nVerifique conexão."
                            txtModelStatus.setBackgroundColor("#D32F2F".toColorInt())
                            Toast.makeText(this, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                }
            }
            .addOnFailureListener {
                // Raro, mas se falhar na checagem → tenta baixar anyway
                // ou trata como erro
                txtModelStatus.text = "Erro ao verificar modelo."
                txtModelStatus.setBackgroundColor("#D32F2F".toColorInt())
            }

        // Botão FALAR
        findViewById<Button>(R.id.btnSpeak).setOnClickListener {
            speakCurrentInput()
        }

        // Botão LIMPAR
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            clearCurrentInput()
        }

        // Alternar entre manuscrito e teclado
        inputModeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {

                R.id.radioHandwriting -> {

                    // Remove foco do EditText
                    typedEditText.clearFocus()
                    typedEditText.isFocusable = false
                    typedEditText.isFocusableInTouchMode = false

                    // Força foco no layout raiz
                    rootLayout.requestFocus()

                    // Esconde teclado com token correto
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(rootLayout.windowToken, 0)

                    handwritingView.visibility = View.VISIBLE
                    typedEditText.visibility = View.GONE
                    recognizedTextView.text = ""

                    enterFullScreen()
                }

                R.id.radioKeyboard -> {

                    exitFullScreen()

                    handwritingView.visibility = View.GONE
                    typedEditText.visibility = View.VISIBLE
                    recognizedTextView.text = ""

                    typedEditText.isFocusable = true
                    typedEditText.isFocusableInTouchMode = true
                    typedEditText.requestFocus()

                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(typedEditText, InputMethodManager.SHOW_IMPLICIT)
                }



            }
        }

        enterFullScreen()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            updateVoiceStatus(
                "Erro ao inicializar Text-to-Speech",
                "#D32F2F"
            )
            return
        }

        tts.language = Locale("pt", "BR")
        tts.setSpeechRate(0.95f)
        tts.setPitch(1.0f)

        when {
            selectPreferredVoice() -> {
                voiceReady = true
                updateVoiceStatus(
                    "Português Brasil",
                    "#388E3C"
                )
            }

            selectFallbackVoice() -> {
                voiceReady = true
                updateVoiceStatus(
                    "Usando voz padrão do sistema",
                    "#FBC02D"
                )
            }

            else -> {
                voiceReady = false
                updateVoiceStatus(
                    "Nenhuma voz em Português instalada",
                    "#D32F2F"
                )

                startActivity(
                    Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                )
            }
        }
    }

    private fun updateVoiceStatus(text: String, colorHex: String) {
        txtVoiceStatus.text = text
        txtVoiceStatus.setBackgroundColor(Color.parseColor(colorHex))
    }

    private fun selectPreferredVoice(): Boolean {
        val voices = tts.voices ?: return false

        val preferred = voices.firstOrNull {
            it.name == "pt-br-x-ptd-local" &&
                    it.locale.language == "pt" &&
                    !it.isNetworkConnectionRequired
        }

        return if (preferred != null) {
            tts.voice = preferred
            true
        } else {
            false
        }
    }

    private fun selectFallbackVoice(): Boolean {
        val voices = tts.voices ?: return false

        val fallback = voices.firstOrNull {
            it.locale.language == "pt"
        }

        return if (fallback != null) {
            tts.voice = fallback
            true
        } else {
            false
        }
    }

    private fun speakCurrentInput() {

        if (!voiceReady) {
            Toast.makeText(
                this,
                "Voz não está pronta para uso",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (!modelReady) {
            tts.speak("Aguarde, preparando reconhecimento de escrita", TextToSpeech.QUEUE_FLUSH, null, null)
            return
        }

        val isHandwriting = inputModeRadioGroup.checkedRadioButtonId == R.id.radioHandwriting

        if (isHandwriting) {
            val ink = handwritingView.getInk()
            if (ink.strokes.isEmpty()) {
                tts.speak("Escreva algo antes de falar", TextToSpeech.QUEUE_FLUSH, null, null)
                return
            }
            recognizer.recognize(ink)
                .addOnSuccessListener { result ->
                    val text = result.candidates.firstOrNull()?.text ?: ""
                    recognizedTextView.text = text
                    if (text.isNotBlank()) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
                    else tts.speak("Não consegui entender a escrita", TextToSpeech.QUEUE_FLUSH, null, null)
                }
        } else {
            val text = typedEditText.text.toString()
            recognizedTextView.text = text
            if (text.isNotBlank()) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            else tts.speak("Digite algum texto antes de falar", TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    private fun enterFullScreen() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(
                    android.view.WindowInsets.Type.statusBars()
                            or android.view.WindowInsets.Type.navigationBars()
                )
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun exitFullScreen() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.show(
                android.view.WindowInsets.Type.statusBars()
                        or android.view.WindowInsets.Type.navigationBars()
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val view = currentFocus ?: View(this)
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun showKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(typedEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun playSuccessBeep() {
        try {
            mediaPlayer = MediaPlayer.create(this, R.raw.beep_success)
            mediaPlayer?.setOnCompletionListener { it.release() }
            mediaPlayer?.start()
        } catch (e: Exception) {
            // Silencioso se falhar (ex: arquivo não encontrado)
        }
    }

    private fun clearCurrentInput() {
        val isHandwriting = inputModeRadioGroup.checkedRadioButtonId == R.id.radioHandwriting
        if (isHandwriting) handwritingView.clear()
        else typedEditText.text.clear()
        recognizedTextView.text = ""
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)

        // Força modo manuscrito real na inicialização
        inputModeRadioGroup.check(R.id.radioHandwriting)

        handwritingView.visibility = View.VISIBLE
        typedEditText.visibility = View.GONE

        hideKeyboard()
        enterFullScreen()
    }

    override fun onPause() {
        super.onPause()
        exitFullScreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
        mediaPlayer?.release()
        mediaPlayer = null
        exitFullScreen()
    }

}
