package com.example.handwritingtospeech

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.text.LineBreaker
import android.graphics.text.LineBreaker.*
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.os.postDelayed
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import java.util.Locale
import java.util.logging.Handler

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

    @RequiresApi(Build.VERSION_CODES.O)
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

        // Estado inicial do botão FALAR
        btnSpeak.isEnabled = false
        btnSpeak.text = "AGUARDE"
        btnSpeak.setTextColor("#DDDD22".toColorInt())  // amarelo claro enquanto aguarda

        tts = TextToSpeech(this, this)

        val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("pt-BR")
        if (modelIdentifier == null) {
            txtModelStatus.text = "Idioma pt-BR não suportado"
            txtModelStatus.setBackgroundColor(Color.parseColor("#D32F2F"))
            modelStatusContainer.visibility = View.VISIBLE
            return
        }

        val model = DigitalInkRecognitionModel.builder(modelIdentifier).build()
        val modelManager = RemoteModelManager.getInstance()

        modelManager.isModelDownloaded(model)
            .addOnSuccessListener { alreadyDownloaded ->
                if (alreadyDownloaded) {
                    modelReady = true
                    recognizer = DigitalInkRecognition.getClient(
                        DigitalInkRecognizerOptions.builder(model).build()
                    )
                    btnSpeak.isEnabled = true
                    btnSpeak.text = "FALAR"
                    btnSpeak.setTextColor(Color.WHITE)
                    modelStatusContainer.visibility = View.GONE
                } else {
                    if (!isNetworkAvailable() && !modelReady) {
                        showNoInternetAndClose()
                    }
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
                            modelReady = true
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
                            modelReady = false
                            btnSpeak.isEnabled = false
                            btnSpeak.text = "Erro no modelo"
                            btnSpeak.setTextColor(Color.parseColor("#D32F2F"))
                            txtModelStatus.text = "Falha ao baixar modelo.\nVerifique conexão."
                            txtModelStatus.setBackgroundColor("#D32F2F".toColorInt())
                            progressModel.visibility = View.GONE
                            Toast.makeText(this, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                }
            }
            .addOnFailureListener {
                modelStatusContainer.visibility = View.VISIBLE
                txtModelStatus.text = "Erro ao verificar modelo."
                txtModelStatus.setBackgroundColor("#D32F2F".toColorInt())
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

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork: NetworkInfo? = connectivityManager.activeNetworkInfo
        return activeNetwork?.isConnectedOrConnecting == true
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

    @RequiresApi(Build.VERSION_CODES.O)
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
                    justificationMode = JUSTIFICATION_MODE_INTER_WORD
                }
                setLineSpacing(0f, 1.3f)
            }
        }

        dialog.show()

        android.os.Handler(Looper.getMainLooper()).postDelayed({
            if (dialog.isShowing) {
                dialog.dismiss()
            }
            finish()
        }, 8000)
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