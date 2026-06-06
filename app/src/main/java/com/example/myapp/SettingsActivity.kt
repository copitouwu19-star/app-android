package com.example.myapp

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: UserPreferences
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private lateinit var btnEscuchar: Button
    private lateinit var btnDetener: Button
    private lateinit var btnProbarVoz: Button
    private lateinit var spinnerVoz: Spinner

    // Lista de voces disponibles en el dispositivo (solo español)
    private val voicesDisponibles = mutableListOf<Voice>()
    private val voicesLabels      = mutableListOf<String>()

    private val textoAyuda = """
        Esta aplicación usa la cámara del teléfono para detectar objetos y guiar a personas con discapacidad visual.
        Al iniciar, el sistema describe el entorno. Luego, mientras caminas, avisa por voz y vibración cuando hay obstáculos cerca.
        Niveles de alerta. Aviso: objeto a más de 4 metros. Cerca: objeto a unos 3 metros, desvíate. Peligro: objeto a 2 metros, detente. Crítico: objeto muy cerca, para de inmediato.
        Cuando hay un grupo de muebles como sillas o mesas, el sistema lo anuncia una sola vez y vibra solo si sigues acercándote.
        Si el camino está bloqueado por todos lados, el sistema te pedirá que muevas el teléfono hacia los lados para buscar una salida.
        La linterna se enciende automáticamente si hay poca luz.
        Si el teléfono no detecta movimiento por 12 segundos, entra en pausa para ahorrar batería. Muévete para reactivarlo.
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = UserPreferences(this)

        val swVib    = findViewById<Switch>(R.id.switchVibracion)
        val rgDist   = findViewById<RadioGroup>(R.id.rgDistancia)
        val rgVoz    = findViewById<RadioGroup>(R.id.rgVelocidad)
        val rgTono   = findViewById<RadioGroup>(R.id.rgTono)
        val btnClose = findViewById<Button>(R.id.btnCerrar)
        btnEscuchar  = findViewById(R.id.btnEscucharAyuda)
        btnDetener   = findViewById(R.id.btnDetenerAyuda)
        btnProbarVoz = findViewById(R.id.btnProbarVoz)
        spinnerVoz   = findViewById(R.id.spinnerVoz)

        // ── Cargar valores guardados ──────────────────────────────────────────
        swVib.isChecked = prefs.vibracionActivada
        when (prefs.distanciaAlerta) {
            "0.5m" -> rgDist.check(R.id.rb05m)
            "2m"   -> rgDist.check(R.id.rb2m)
            else   -> rgDist.check(R.id.rb1m)
        }
        when (prefs.velocidadVoz) {
            "lento"  -> rgVoz.check(R.id.rbLento)
            "rapido" -> rgVoz.check(R.id.rbRapido)
            else     -> rgVoz.check(R.id.rbNormal)
        }
        when (prefs.perfilTono) {
            "calida"  -> rgTono.check(R.id.rbCalida)
            "grave"   -> rgTono.check(R.id.rbGrave)
            "animada" -> rgTono.check(R.id.rbAnimada)
            "suave"   -> rgTono.check(R.id.rbSuave)
            else      -> rgTono.check(R.id.rbNatural)
        }

        // ── Guardar cambios al instante ───────────────────────────────────────
        swVib.setOnCheckedChangeListener { _, checked -> prefs.vibracionActivada = checked }
        rgDist.setOnCheckedChangeListener { _, id ->
            prefs.distanciaAlerta = when (id) {
                R.id.rb05m -> "0.5m"
                R.id.rb2m  -> "2m"
                else       -> "1m"
            }
        }
        rgVoz.setOnCheckedChangeListener { _, id ->
            prefs.velocidadVoz = when (id) {
                R.id.rbLento  -> "lento"
                R.id.rbRapido -> "rapido"
                else          -> "normal"
            }
            tts?.setSpeechRate(prefs.getSpeechRate())
        }
        rgTono.setOnCheckedChangeListener { _, id ->
            prefs.perfilTono = when (id) {
                R.id.rbCalida  -> "calida"
                R.id.rbGrave   -> "grave"
                R.id.rbAnimada -> "animada"
                R.id.rbSuave   -> "suave"
                else           -> "natural"
            }
            tts?.setPitch(prefs.getPitch())
            tts?.setSpeechRate(prefs.getSpeechRate())
        }

        btnClose.setOnClickListener { finish() }

        // ── TTS ───────────────────────────────────────────────────────────────
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            val r = tts?.setLanguage(java.util.Locale("es", "MX"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
                tts?.setLanguage(java.util.Locale("es"))
            tts?.setPitch(prefs.getPitch())
            tts?.setSpeechRate(prefs.getSpeechRate())
            ttsReady = true

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = runOnUiThread {
                    btnEscuchar.visibility = View.GONE
                    btnDetener.visibility  = View.VISIBLE
                }
                override fun onDone(id: String?) = runOnUiThread {
                    btnEscuchar.visibility = View.VISIBLE
                    btnDetener.visibility  = View.GONE
                }
                override fun onError(id: String?) = runOnUiThread {
                    btnEscuchar.visibility = View.VISIBLE
                    btnDetener.visibility  = View.GONE
                }
            })

            cargarVoces()
        }

        // ── Botones de ayuda ──────────────────────────────────────────────────
        btnEscuchar.setOnClickListener {
            if (ttsReady) {
                tts?.setSpeechRate(prefs.getSpeechRate())
                tts?.speak(textoAyuda, TextToSpeech.QUEUE_FLUSH, null, "ayuda")
            }
        }
        btnDetener.setOnClickListener {
            tts?.stop()
            btnEscuchar.visibility = View.VISIBLE
            btnDetener.visibility  = View.GONE
        }

        // ── Botón probar voz ──────────────────────────────────────────────────
        btnProbarVoz.setOnClickListener {
            if (!ttsReady) return@setOnClickListener
            aplicarVozSeleccionada()
            tts?.setPitch(prefs.getPitch())
            tts?.setSpeechRate(prefs.getSpeechRate())
            tts?.speak("Esta es la voz seleccionada. Silla al frente, ve a la izquierda.",
                TextToSpeech.QUEUE_FLUSH, null, "prueba_voz")
        }
    }

    // ── Cargar voces disponibles en español ───────────────────────────────────
    private fun cargarVoces() {
        val allVoices = tts?.voices ?: return
        val espanolas = allVoices
            .filter { v ->
                v.locale.language == "es" &&
                !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.latency })

        if (espanolas.isEmpty()) {
            runOnUiThread {
                spinnerVoz.adapter = ArrayAdapter(this,
                    android.R.layout.simple_spinner_item,
                    listOf("Sin voces instaladas"))
            }
            return
        }

        voicesDisponibles.clear()
        voicesLabels.clear()

        // Opción "Automática" como primera entrada
        voicesDisponibles.add(0, espanolas.first())  // placeholder, no se usa
        voicesLabels.add("Automática (mejor disponible)")

        espanolas.forEachIndexed { index, voice ->
            voicesDisponibles.add(voice)
            voicesLabels.add(etiquetaVoz(voice, index + 1))
        }

        runOnUiThread {
            val adapter = ArrayAdapter(this,
                android.R.layout.simple_spinner_item, voicesLabels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerVoz.adapter = adapter

            // Seleccionar la voz guardada actualmente
            val savedName = prefs.vozNombre
            val savedIndex = if (savedName.isEmpty()) 0
                else voicesDisponibles.indexOfFirst { it.name == savedName }.takeIf { it > 0 } ?: 0
            spinnerVoz.setSelection(savedIndex)

            spinnerVoz.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    prefs.vozNombre = if (pos == 0) "" else voicesDisponibles[pos].name
                    aplicarVozSeleccionada()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    private fun aplicarVozSeleccionada() {
        val savedName = prefs.vozNombre
        if (savedName.isEmpty()) return   // automática → MainActivity la elige
        val voice = voicesDisponibles.firstOrNull { it.name == savedName } ?: return
        tts?.voice = voice
    }

    // ── Etiqueta legible para cada voz ───────────────────────────────────────
    private fun etiquetaVoz(voice: Voice, numero: Int): String {
        val pais = when (voice.locale.country.uppercase()) {
            "MX" -> "México"
            "ES" -> "España"
            "US" -> "Estados Unidos"
            "AR" -> "Argentina"
            "CO" -> "Colombia"
            "CL" -> "Chile"
            "PE" -> "Perú"
            "VE" -> "Venezuela"
            else -> voice.locale.displayCountry.ifEmpty { "Español" }
        }
        val calidad = when {
            voice.quality >= Voice.QUALITY_VERY_HIGH -> "★★★ Alta calidad"
            voice.quality >= Voice.QUALITY_HIGH      -> "★★  Buena calidad"
            else                                     -> "★   Estándar"
        }
        val conexion = if (voice.isNetworkConnectionRequired) " · requiere internet" else ""
        return "Voz $numero — $pais  $calidad$conexion"
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
