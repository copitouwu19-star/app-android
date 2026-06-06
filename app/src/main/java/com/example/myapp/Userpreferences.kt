package com.example.myapp

import android.content.Context
import android.content.SharedPreferences

// ─────────────────────────────────────────────────────────────────────────────
// USER PREFERENCES — Persistencia entre sesiones
//
// Las 4 preferencias guardadas:
//   · distanciaAlerta  : "cercano" | "normal" | "anticipado"
//   · modoSilencioso   : Boolean
//   · velocidadVoz     : "lento" | "normal" | "rapido"
//   · vibracionActivada: Boolean
//
// Métodos de conversión a valores float para uso interno:
//   · getDepthThreshold() → umbral de depthScore para alerta YOLO
//   · getSpeechRate()     → multiplicador de velocidad para TTS
// ─────────────────────────────────────────────────────────────────────────────
class UserPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME           = "visual_nav_prefs"
        private const val KEY_DISTANCIA_ALERTA = "distanciaAlerta"
        private const val KEY_MODO_SILENCIOSO  = "modoSilencioso"
        private const val KEY_VELOCIDAD_VOZ    = "velocidadVoz"
        private const val KEY_VIBRACION        = "vibracionActivada"
        private const val KEY_VOZ_NOMBRE       = "vozNombre"
        private const val KEY_PERFIL_TONO      = "perfilTono"

        // Valores por defecto
        private const val DEFAULT_DISTANCIA   = "1m"
        private const val DEFAULT_VELOCIDAD   = "normal"
        private const val DEFAULT_SILENCIOSO  = false
        private const val DEFAULT_VIBRACION   = true
        private const val DEFAULT_VOZ         = ""
        private const val DEFAULT_TONO        = "natural"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Getters ───────────────────────────────────────────────────────────────

    /** Sensibilidad de la alerta YOLO: "cercano" | "normal" | "anticipado" */
    var distanciaAlerta: String
        get() = prefs.getString(KEY_DISTANCIA_ALERTA, DEFAULT_DISTANCIA) ?: DEFAULT_DISTANCIA
        set(value) = prefs.edit().putString(KEY_DISTANCIA_ALERTA, value).apply()

    /** Si true, solo vibra — nunca habla */
    var modoSilencioso: Boolean
        get() = prefs.getBoolean(KEY_MODO_SILENCIOSO, DEFAULT_SILENCIOSO)
        set(value) = prefs.edit().putBoolean(KEY_MODO_SILENCIOSO, value).apply()

    /** Velocidad de voz: "lento" | "normal" | "rapido" */
    var velocidadVoz: String
        get() = prefs.getString(KEY_VELOCIDAD_VOZ, DEFAULT_VELOCIDAD) ?: DEFAULT_VELOCIDAD
        set(value) = prefs.edit().putString(KEY_VELOCIDAD_VOZ, value).apply()

    /** Si true, se activa la vibración en alertas */
    var vibracionActivada: Boolean
        get() = prefs.getBoolean(KEY_VIBRACION, DEFAULT_VIBRACION)
        set(value) = prefs.edit().putBoolean(KEY_VIBRACION, value).apply()

    /** Nombre técnico de la voz TTS seleccionada. Vacío = auto-selección por calidad */
    var vozNombre: String
        get() = prefs.getString(KEY_VOZ_NOMBRE, DEFAULT_VOZ) ?: DEFAULT_VOZ
        set(value) = prefs.edit().putString(KEY_VOZ_NOMBRE, value).apply()

    /** Perfil de tono: "natural" | "calida" | "grave" | "animada" | "suave" */
    var perfilTono: String
        get() = prefs.getString(KEY_PERFIL_TONO, DEFAULT_TONO) ?: DEFAULT_TONO
        set(value) = prefs.edit().putString(KEY_PERFIL_TONO, value).apply()

    /**
     * Convierte el perfil de tono al pitch de TTS.
     * El pitch por defecto de Android (1.0) suena robótico en muchos motores.
     * Valores ligeramente más bajos (0.88-0.95) suenan más naturales y cálidos.
     */
    fun getPitch(): Float = when (perfilTono) {
        "calida"  -> 0.90f   // cálida y natural, menos robótica
        "grave"   -> 0.78f   // tono bajo y sereno
        "animada" -> 1.20f   // tono alto y energético
        "suave"   -> 0.95f   // suave, ligeramente más baja que estándar
        else      -> 1.00f   // "natural" — estándar del sistema
    }

    /**
     * Velocidad ajustada: el perfil de tono puede influir levemente en la velocidad
     * para que el resultado final suene más coherente.
     */
    fun getSpeechRate(): Float {
        val baseRate = when (velocidadVoz) {
            "lento"  -> 0.85f
            "rapido" -> 1.20f
            else     -> 1.00f
        }
        // Voces graves y suaves suenan mejor un poco más lentas
        val tonoMult = when (perfilTono) {
            "grave" -> 0.93f
            "suave" -> 0.90f
            else    -> 1.00f
        }
        return baseRate * tonoMult
    }

    // ── Conversores a float ───────────────────────────────────────────────────

    /**
     * Convierte distanciaAlerta al umbral mínimo de depthScore para alertar sobre muebles.
     * Las personas y vehículos siempre se reportan sin importar esta configuración.
     *
     *   "0.5m" → 0.62  (DEPTH_PELIGRO — alerta solo cuando el objeto está muy cerca)
     *   "1m"   → 0.48  (DEPTH_CERCA   — alerta a ~3-4m, balance bueno)
     *   "2m"   → 0.32  (DEPTH_AVISO   — alerta anticipada a ~5m)
     */
    fun getDepthThreshold(): Float = when (distanciaAlerta) {
        "0.5m" -> 0.62f
        "2m"   -> 0.32f
        else   -> 0.48f   // "1m" es el default
    }

}