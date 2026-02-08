package com.example.handwritingtospeech

import android.annotation.SuppressLint
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
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
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
import android.widget.RadioButton
import androidx.core.graphics.toColorInt
import androidx.core.content.edit
import androidx.core.view.isVisible

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

    private var hasShownAndroid7Warning = false

    @SuppressLint("SetTextI18n")
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

        // Carrega estado persistente do modelo
        modelReady = prefs.getBoolean(KEY_MODEL_DOWNLOADED, false)

        btnSpeak.isEnabled = false
        btnSpeak.text = "AGUARDE"
        btnSpeak.setTextColor("#DDDD22".toColorInt())

        tts = TextToSpeech(this, this)

        // Detecta Android 7.x (onde o modelo quase nunca funciona)
        val isAndroid7 = Build.VERSION.SDK_INT in Build.VERSION_CODES.N..Build.VERSION_CODES.N_MR1

        if (isAndroid7) {
            // Esconde o RadioGroup inteiro
            inputModeRadioGroup.visibility = View.GONE

            // Área de escrita desabilitada e escondida desde o início
            handwritingView.isEnabled = false
            handwritingView.isClickable = false
            handwritingView.isFocusable = false
            handwritingView.isFocusableInTouchMode = false
            handwritingView.visibility = View.GONE

            // Botão FALAR habilitado
            btnSpeak.isEnabled = true
            btnSpeak.text = "FALAR"
            btnSpeak.setTextColor(Color.WHITE)

            // Tudo que precisa de layout pronto vai dentro do post
            rootLayout.post {
               // Garante modo teclado ativo
                inputModeRadioGroup.check(R.id.radioKeyboard)

                // **Força a caixa de aviso aparecer**
                modelStatusContainer.visibility = View.VISIBLE
                txtModelStatus.visibility = View.VISIBLE
                txtModelStatus.textAlignment = View.TEXT_ALIGNMENT_CENTER
                txtModelStatus.gravity = Gravity.CENTER
                txtModelStatus.setBackgroundColor("#FF9800".toColorInt())
                txtModelStatus.text = "Escrita à mão não disponível neste dispositivo (Android 7 ou " +
                                      "inferior). Use o TECLADO para digitar o texto e falar."

                progressModel.visibility = View.GONE

                // Mostra o campo de texto
                typedEditText.visibility = View.VISIBLE
                typedEditText.isFocusable = true
                typedEditText.isFocusableInTouchMode = true
                typedEditText.isEnabled = true

                typedEditText.requestFocusFromTouch()
                typedEditText.requestFocus()

                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(typedEditText, InputMethodManager.SHOW_IMPLICIT)

                // Reforço extra de foco/teclado
                typedEditText.postDelayed({
                    typedEditText.requestFocusFromTouch()
                    typedEditText.requestFocus()
                    imm.showSoftInput(typedEditText, InputMethodManager.SHOW_IMPLICIT)
                }, 10200)

                // Força redesenho completo do layout (essencial no Android 7)
                rootLayout.requestLayout()
                modelStatusContainer.requestLayout()
                txtModelStatus.requestLayout()
            }

            // Opcional: esconde a caixa após 10 segundos
            Handler(Looper.getMainLooper()).postDelayed({
                modelStatusContainer.visibility = View.GONE
            }, 10000)
        }
        else {
            // Android 8+ → verifica ou baixa o modelo normalmente
            if (modelReady) {
                // Já baixado antes → habilita direto, sem progress
                recognizer = DigitalInkRecognition.getClient(
                    DigitalInkRecognizerOptions.builder(
                        DigitalInkRecognitionModel.builder(
                            DigitalInkRecognitionModelIdentifier.fromLanguageTag("pt-BR")!!
                        ).build()
                    ).build()
                )
                btnSpeak.isEnabled = true
                btnSpeak.text = "FALAR"
                btnSpeak.setTextColor(Color.WHITE)
                modelStatusContainer.visibility = View.GONE
                progressModel.visibility = View.GONE
                inputModeRadioGroup.visibility = View.VISIBLE
            } else {
                val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("pt-BR")
                if (modelIdentifier == null) {
                    modelStatusContainer.visibility = View.VISIBLE
                    txtModelStatus.textAlignment = View.TEXT_ALIGNMENT_CENTER
                    txtModelStatus.gravity = Gravity.CENTER
                    txtModelStatus.text = "Idioma pt-BR não suportado"
                    txtModelStatus.setBackgroundColor(Color.parseColor("#D32F2F"))
                    progressModel.visibility = View.GONE
                    inputModeRadioGroup.visibility = View.GONE
                    return
                }

                val model = DigitalInkRecognitionModel.builder(modelIdentifier).build()
                val modelManager = RemoteModelManager.getInstance()

                if (!isNetworkAvailable()) {
                    // Sem conexão → erro, sem progress
                    modelStatusContainer.visibility = View.VISIBLE
                    txtModelStatus.textAlignment = View.TEXT_ALIGNMENT_CENTER
                    txtModelStatus.gravity = Gravity.CENTER
                    txtModelStatus.text = "Sem conexão com a internet.\nVerifique sua rede para baixar o modelo."
                    txtModelStatus.setBackgroundColor(Color.parseColor("#FF9800"))
                    progressModel.visibility = View.GONE
                    showNoInternetAndClose()
                } else {
                    // Tem conexão → verifica se já está baixado
                    modelManager.isModelDownloaded(model)
                        .addOnSuccessListener { alreadyDownloaded ->
                            if (alreadyDownloaded) {
                                modelReady = true
                                prefs.edit { putBoolean(KEY_MODEL_DOWNLOADED, true) }
                                recognizer = DigitalInkRecognition.getClient(
                                    DigitalInkRecognizerOptions.builder(model).build()
                                )
                                btnSpeak.isEnabled = true
                                btnSpeak.text = "FALAR"
                                btnSpeak.setTextColor(Color.WHITE)
                                modelStatusContainer.visibility = View.GONE
                                progressModel.visibility = View.GONE

                                // Reabilita o RadioGroup após o download
                                inputModeRadioGroup.visibility = View.VISIBLE
                                inputModeRadioGroup.alpha = 1.0f
                            } else {
                                // Vai baixar → mostra progress
                                // Desabilita o RadioGroup inteiro durante o download
                                inputModeRadioGroup.visibility = View.GONE
                                inputModeRadioGroup.alpha = 0.5f   // visualmente mais claro que está desativado
                                modelStatusContainer.visibility = View.VISIBLE
                                txtModelStatus.visibility = View.VISIBLE
                                txtModelStatus.textAlignment = View.TEXT_ALIGNMENT_CENTER
                                txtModelStatus.gravity = Gravity.CENTER
                                txtModelStatus.text = "Iniciando o download do modelo de reconhecimento de escrita à mão." +
                                                      "Ocorre somente na primeira execução e tem um tamanho de 20 MB aproximadamente."
                                txtModelStatus.setBackgroundColor(Color.parseColor("#1976D2"))
                                progressModel.isIndeterminate = true
                                progressModel.visibility = View.VISIBLE
                                btnSpeak.isEnabled = false
                                btnSpeak.text = "AGUARDE"
                                btnSpeak.setTextColor("#DDDD22".toColorInt())

                                val conditions = DownloadConditions.Builder().build()

                                modelManager.download(model, conditions)
                                    .addOnSuccessListener {
                                        modelReady = true
                                        prefs.edit { putBoolean(KEY_MODEL_DOWNLOADED, true) }
                                        recognizer = DigitalInkRecognition.getClient(
                                            DigitalInkRecognizerOptions.builder(model).build()
                                        )
                                        btnSpeak.isEnabled = true
                                        btnSpeak.text = "FALAR"
                                        btnSpeak.setTextColor(Color.WHITE)
                                        modelStatusContainer.visibility = View.GONE
                                        progressModel.visibility = View.GONE

                                        // Reabilita alternância de modo após download
                                        inputModeRadioGroup.visibility = View.VISIBLE
                                        inputModeRadioGroup.alpha = 1.0f

                                        Toast.makeText(this, "Modelo carregado!", Toast.LENGTH_SHORT).show()
                                        playSuccessBeep()
                                    }
                                    .addOnFailureListener { e ->
                                        modelReady = false
                                        btnSpeak.isEnabled = true
                                        btnSpeak.text = "FALAR"
                                        btnSpeak.setTextColor(Color.WHITE)
                                        txtModelStatus.text = "Falha ao baixar modelo.\nVerifique conexão."
                                        txtModelStatus.setBackgroundColor("#FF9800".toColorInt())
                                        progressModel.visibility = View.GONE

                                        // Reabilita alternância mesmo na falha
                                        inputModeRadioGroup.visibility = View.VISIBLE
                                        inputModeRadioGroup.alpha = 1.0f

                                        inputModeRadioGroup.check(R.id.radioKeyboard)
                                        handwritingView.visibility = View.GONE
                                        typedEditText.visibility = View.VISIBLE
                                        typedEditText.requestFocus()

                                        Toast.makeText(this, "Download falhou. Use o modo teclado.", Toast.LENGTH_LONG).show()
                                    }
                            }
                        }
                        .addOnFailureListener {
                            modelStatusContainer.visibility = View.VISIBLE
                            txtModelStatus.text = "Erro ao verificar modelo."
                            txtModelStatus.setBackgroundColor("#D32F2F".toColorInt())
                            progressModel.visibility = View.GONE

                            // Reabilita alternância em caso de erro na verificação
                            inputModeRadioGroup.visibility = View.VISIBLE
                            inputModeRadioGroup.alpha = 1.0f

                            btnSpeak.isEnabled = true
                            btnSpeak.text = "FALAR"
                            btnSpeak.setTextColor(Color.WHITE)

                            inputModeRadioGroup.check(R.id.radioKeyboard)
                            handwritingView.visibility = View.GONE
                            typedEditText.visibility = View.VISIBLE
                            typedEditText.requestFocus()
                        }
                }
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
            // Ignora mudança se o grupo estiver desabilitado (durante download)
            if (!inputModeRadioGroup.isEnabled) {
                return@setOnCheckedChangeListener
            }

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

                    // NÃO reexibir a caixa automaticamente ao voltar para manuscrito
                    // (só aparece durante download ou erro)
                    // modelStatusContainer.visibility = View.VISIBLE  ← removido
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

                    // Esconde caixa e progress no modo teclado
                    modelStatusContainer.visibility = View.GONE
                    progressModel.visibility = View.GONE
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
        btnSpeak.text = "FALAR"
//        setButtonTextWithSmallParenthesis(btnSpeak, "FALAR", "teclado")
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

        val isHandwriting = inputModeRadioGroup.checkedRadioButtonId == R.id.radioHandwriting

        if (isHandwriting) {
            // Modo manuscrito → exige modelo pronto
            if (!modelReady) {
                tts.speak("O modo Manuscrito não está disponível nesta versão do Android ou inferior. Use o modo Teclado.", TextToSpeech.QUEUE_FLUSH, null, null)
                return
            }

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
            // Modo TECLADO → fala direto o texto digitado (sem depender de modelReady)
            val text = typedEditText.text.toString().trim()
            recognizedTextView.text = text

            if (text.isNotBlank()) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            } else {
                tts.speak("Digite algum texto antes de falar", TextToSpeech.QUEUE_FLUSH, null, null)
            }
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
        txtModelStatus.setBackgroundColor("#FF9800".toColorInt())  // laranja para atenção

        btnSpeak.isEnabled = true
        btnSpeak.text = "FALAR"
//        setButtonTextWithSmallParenthesis(btnSpeak, "FALAR", "teclado")
        btnSpeak.setTextColor(Color.WHITE)

        // Força modo teclado
        inputModeRadioGroup.check(R.id.radioKeyboard)
        handwritingView.visibility = View.GONE
        typedEditText.visibility = View.VISIBLE
        typedEditText.requestFocus()

        Toast.makeText(this, "Modo manuscrito não disponível. Use o teclado.", Toast.LENGTH_LONG).show()
    }

    private fun setButtonTextWithSmallParenthesis(button: Button, mainText: String, smallText: String) {
        val fullText = "$mainText ($smallText)"
        val spannable = SpannableString(fullText)

        val start = fullText.indexOf("(")
        val end = fullText.length

        if (start >= 0) {
            // Tamanho menor para a parte "(teclado)"
            spannable.setSpan(
                AbsoluteSizeSpan(12, true),  // 12sp — ajuste para 10 ou 11 se quiser ainda menor
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            // Cor mais suave (cinza claro) para destacar menos
            spannable.setSpan(
                ForegroundColorSpan("#AAAAAA".toColorInt()),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            // Opcional: itálico para a parte pequena (fica mais discreto ainda)
            // spannable.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Define o texto com Spannable
        button.text = spannable

        // Força atualização visual (importante em alguns casos)
        button.invalidate()
        button.requestLayout()
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