package com.example.handwritingtospeech

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.text.LineBreaker
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
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
    private lateinit var progressModel: ProgressBar
    private lateinit var modelStatusContainer: View
    private lateinit var btnSpeak: Button

    private var modelReady = false
    private val REQUIRED_VOICE = "pt-br-x-ptd-local"
    private val REQUIRED_LOCALE = Locale("pt", "BR")
    private lateinit var txtVoiceStatus: TextView
    private var voiceReady = false
    private var mediaPlayer: MediaPlayer? = null

    private val prefs by lazy { getSharedPreferences("app_state", Context.MODE_PRIVATE) }
    private val KEY_MODEL_DOWNLOADED = "model_downloaded_success"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inicialização das views
        handwritingView = findViewById(R.id.handwritingView)
        typedEditText = findViewById(R.id.editTextTyped)
        recognizedTextView = findViewById(R.id.txtRecognized)
        inputModeRadioGroup = findViewById(R.id.radioGroupInputMode)
        txtVoiceStatus = findViewById(R.id.txtVoiceStatus)
        rootLayout = findViewById(R.id.rootLayout)
        txtModelStatus = findViewById(R.id.txtModelStatus)
        progressModel = findViewById(R.id.progressModel)
        modelStatusContainer = findViewById(R.id.modelStatusContainer)
        btnSpeak = findViewById(R.id.btnSpeak)

        btnSpeak.isEnabled = false
        btnSpeak.text = "AGUARDE"
        btnSpeak.setTextColor("#DDDD22".toColorInt())

        tts = TextToSpeech(this, this)

        // 1. Checagem rápida via flag (muito útil no Android 7)
        if (prefs.getBoolean(KEY_MODEL_DOWNLOADED, false)) {
            Log.d("MODEL_CHECK", "Flag indica modelo baixado anteriormente → assumindo pronto")
            modelReady = true
            tryInitializeRecognizer()
            btnSpeak.isEnabled = true
            btnSpeak.text = "FALAR"
            btnSpeak.setTextColor(Color.WHITE)
            modelStatusContainer.visibility = View.GONE
        } else {
            // 2. Verificação real (assíncrona)
            val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("pt-BR")
            if (modelIdentifier == null) {
                Log.e("MODEL_CHECK", "Idioma pt-BR não suportado")
                showErrorAndForceKeyboard("Idioma pt-BR não suportado neste dispositivo")
                return
            }

            val model = DigitalInkRecognitionModel.builder(modelIdentifier).build()
            val modelManager = RemoteModelManager.getInstance()

            modelManager.isModelDownloaded(model)
                .addOnSuccessListener { alreadyDownloaded ->
                    if (alreadyDownloaded) {
                        Log.d("MODEL_CHECK", "Modelo já baixado (verificação confirmada)")
                        prefs.edit().putBoolean(KEY_MODEL_DOWNLOADED, true).apply()
                        modelReady = true
                        recognizer = DigitalInkRecognition.getClient(
                            DigitalInkRecognizerOptions.builder(model).build()
                        )
                        btnSpeak.isEnabled = true
                        btnSpeak.text = "FALAR"
                        btnSpeak.setTextColor(Color.WHITE)
                        modelStatusContainer.visibility = View.GONE
                    } else {
                        Log.d("MODEL_CHECK", "Modelo não baixado ainda")
                        if (!isNetworkAvailable()) {
                            Log.w("NETWORK", "Sem internet → mostrando alerta e fechando")
                            showNoInternetAndClose()
                            return@addOnSuccessListener
                        }

                        Log.d("DOWNLOAD", "Iniciando download do modelo")
                        modelStatusContainer.visibility = View.VISIBLE
                        txtModelStatus.visibility = View.VISIBLE
                        txtModelStatus.text = "Baixando modelo - primeira vez ~20 MB"
                        progressModel.isIndeterminate = true
                        progressModel.visibility = View.VISIBLE
                        btnSpeak.isEnabled = false
                        btnSpeak.text = "AGUARDE"
                        btnSpeak.setTextColor("#DDDD22".toColorInt())

                        val conditions = DownloadConditions.Builder()
                            .requireWifi()
                            .build()

                        modelManager.download(model, conditions)
                            .addOnSuccessListener {
                                Log.d("DOWNLOAD", "Download concluído com sucesso")
                                modelReady = true
                                prefs.edit().putBoolean(KEY_MODEL_DOWNLOADED, true).apply()
                                recognizer = DigitalInkRecognition.getClient(
                                    DigitalInkRecognizerOptions.builder(model).build()
                                )
                                btnSpeak.isEnabled = true
                                btnSpeak.text = "FALAR"
                                btnSpeak.setTextColor(Color.WHITE)
                                modelStatusContainer.visibility = View.GONE
                                Toast.makeText(this, "Modelo carregado!", Toast.LENGTH_SHORT).show()
                                playSuccessBeep()
                            }
                            .addOnFailureListener { e ->
                                Log.e("DOWNLOAD", "Falha no download", e)
                                forceKeyboardMode()
                                txtModelStatus.text = "Falha ao baixar modelo.\nUsando apenas teclado."
                                txtModelStatus.setBackgroundColor(Color.parseColor("#FF9800"))
                                progressModel.visibility = View.GONE
                                Toast.makeText(this, "Download falhou. Use o teclado.", Toast.LENGTH_LONG).show()
                            }

                        // Timeout de segurança para Android 7 (90 segundos)
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (!modelReady) {
                                Log.w("DOWNLOAD", "Timeout de 90s atingido → liberando modo teclado")
                                forceKeyboardMode()
                                txtModelStatus.text = "Tempo de download excedido.\nUsando apenas teclado."
                                txtModelStatus.setBackgroundColor(Color.parseColor("#FF9800"))
                                progressModel.visibility = View.GONE
                                Toast.makeText(this, "Tempo esgotado. Use o teclado.", Toast.LENGTH_LONG).show()
                            }
                        }, 90_000)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("MODEL_CHECK", "Falha ao verificar modelo", e)
                    forceKeyboardMode()
                    txtModelStatus.text = "Erro ao verificar modelo.\nUsando apenas teclado."
                    txtModelStatus.setBackgroundColor(Color.parseColor("#FF9800"))
                }
        }

        // Botão FALAR
        btnSpeak.setOnClickListener {
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
                    typedEditText.clearFocus()
                    typedEditText.isFocusable = false
                    typedEditText.isFocusableInTouchMode = false
                    rootLayout.requestFocus()

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

    private fun tryInitializeRecognizer() {
        try {
            val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("pt-BR")
            if (modelIdentifier != null) {
                val model = DigitalInkRecognitionModel.builder(modelIdentifier).build()
                recognizer = DigitalInkRecognition.getClient(
                    DigitalInkRecognizerOptions.builder(model).build()
                )
            }
        } catch (e: Exception) {
            Log.e("RECOGNIZER", "Falha ao inicializar recognizer", e)
        }
    }

    private fun forceKeyboardMode() {
        btnSpeak.isEnabled = true
        btnSpeak.text = "FALAR (teclado)"
        btnSpeak.setTextColor(Color.WHITE)
        inputModeRadioGroup.check(R.id.radioKeyboard)
        handwritingView.visibility = View.GONE
        typedEditText.visibility = View.VISIBLE
        typedEditText.requestFocus()
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork: NetworkInfo? = connectivityManager.activeNetworkInfo
        return activeNetwork?.isConnectedOrConnecting == true
    }

    private fun showNoInternetAndClose() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("SEM CONEXÃO")
            .setMessage(
                "\nO aplicativo precisa baixar o modelo de reconhecimento de escrita na primeira vez.\n\n" +
                        "Conecte-se à internet e abra novamente o aplicativo.\n\n" +
                        "Fechando automaticamente em alguns segundos..."
            )
            .setCancelable(false)
            .create()

        dialog.setOnShowListener {
            // Título centralizado
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.apply {
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }
            // Mensagem justificada
            dialog.findViewById<TextView>(android.R.id.message)?.apply {
                gravity = Gravity.FILL_HORIZONTAL
                textAlignment = View.TEXT_ALIGNMENT_GRAVITY
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    justificationMode = LineBreaker.JUSTIFICATION_MODE_INTER_WORD
                }
                setLineSpacing(0f, 1.3f)
            }
        }

        dialog.show()

        Handler(Looper.getMainLooper()).postDelayed({
            if (dialog.isShowing) {
                dialog.dismiss()
            }
            finish()
        }, 8000)  // 8 segundos
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            updateVoiceStatus("Erro ao inicializar Text-to-Speech", "#D32F2F")
            return
        }

        tts.language = Locale("pt", "BR")
        tts.setSpeechRate(0.95f)
        tts.setPitch(1.0f)

        when {
            selectPreferredVoice() -> {
                voiceReady = true
                updateVoiceStatus("Português Brasil", "#388E3C")
            }
            selectFallbackVoice() -> {
                voiceReady = true
                updateVoiceStatus("Usando voz padrão do sistema", "#FBC02D")
            }
            else -> {
                voiceReady = false
                updateVoiceStatus("Nenhuma voz em Português instalada", "#D32F2F")
                AlertDialog.Builder(this)
                    .setTitle("Voz não instalada")
                    .setMessage("Este aplicativo precisa de uma voz em Português Brasil para falar. Deseja instalar agora?")
                    .setPositiveButton("Sim, instalar") { _, _ ->
                        startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                    }
                    .setNegativeButton("Não agora") { dialog, _ -> dialog.dismiss() }
                    .setCancelable(false)
                    .show()
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
            it.name == REQUIRED_VOICE &&
                    it.locale.language == "pt" &&
                    !it.isNetworkConnectionRequired
        }

        return if (preferred != null) {
            tts.voice = preferred
            true
        } else false
    }

    private fun selectFallbackVoice(): Boolean {
        val voices = tts.voices ?: return false

        val fallback = voices.firstOrNull {
            it.locale.language == "pt"
        }

        return if (fallback != null) {
            tts.voice = fallback
            true
        } else false
    }

    private fun speakCurrentInput() {
        if (!voiceReady) {
            Toast.makeText(this, "Voz não está pronta para uso", Toast.LENGTH_SHORT).show()
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

            Log.d("RECOGNIZE", "Iniciando reconhecimento - ${ink.strokes.size} strokes")

            recognizer.recognize(ink)
                .addOnSuccessListener { result ->
                    Log.d("RECOGNIZE", "Sucesso: ${result.candidates.size} candidatos")
                    val text = result.candidates.firstOrNull()?.text ?: ""
                    recognizedTextView.text = text
                    if (text.isNotBlank()) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
                    else tts.speak("Não consegui entender a escrita", TextToSpeech.QUEUE_FLUSH, null, null)
                }
                .addOnFailureListener { e ->
                    Log.e("RECOGNIZE", "Falha: ${e.message}", e)
                    tts.speak("Erro ao reconhecer a escrita", TextToSpeech.QUEUE_FLUSH, null, null)
                }
        } else {
            val text = typedEditText.text.toString()
            recognizedTextView.text = text
            if (text.isNotBlank()) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            else tts.speak("Digite algum texto antes de falar", TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    private fun clearCurrentInput() {
        val isHandwriting = inputModeRadioGroup.checkedRadioButtonId == R.id.radioHandwriting
        if (isHandwriting) handwritingView.clear()
        else typedEditText.text.clear()
        recognizedTextView.text = ""
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
            // Silencioso se falhar
        }
    }

    private fun showErrorAndForceKeyboard(message: String) {
        modelStatusContainer.visibility = View.VISIBLE
        txtModelStatus.text = message
        txtModelStatus.setBackgroundColor(Color.parseColor("#FF9800"))  // laranja para atenção

        btnSpeak.isEnabled = true
        btnSpeak.text = "FALAR (teclado)"
        btnSpeak.setTextColor(Color.WHITE)

        // Força modo teclado
        inputModeRadioGroup.check(R.id.radioKeyboard)
        handwritingView.visibility = View.GONE
        typedEditText.visibility = View.VISIBLE
        typedEditText.requestFocus()

        Toast.makeText(this, "Modo manuscrito não disponível. Use o teclado.", Toast.LENGTH_LONG).show()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
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