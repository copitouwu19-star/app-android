package com.example.myapp

// ─────────────────────────────────────────────────────────────────────────────
// VOICE SETTINGS ASSISTANT — Intérprete de comandos de voz para ajustes.
//
// Recibe el texto reconocido por SpeechRecognizer (ya transcrito a String) y
// busca coincidencias por palabras clave en español. Si encuentra un comando
// válido, aplica el cambio directo sobre UserPreferences y devuelve una frase
// de confirmación para que MainActivity la hable por TTS.
//
// Sin dependencias de red ni de Gemini → respuesta inmediata, sin latencia.
// ─────────────────────────────────────────────────────────────────────────────
internal object VoiceSettingsAssistant {

    /**
     * Procesa el comando y aplica el cambio en [prefs].
     * @return frase de confirmación, o null si no se reconoció ningún comando.
     */
    fun procesarComando(textoCrudo: String, prefs: UserPreferences): String? {
        val t = textoCrudo.lowercase().trim()

        // ── Vibración ────────────────────────────────────────────────────────
        if (t.contains("vibrac")) {
            return when {
                contieneActivar(t) -> { prefs.vibracionActivada = true;  "Vibración activada." }
                contieneDesactivar(t) -> { prefs.vibracionActivada = false; "Vibración desactivada." }
                else -> null
            }
        }

        // ── Modo silencioso ──────────────────────────────────────────────────
        if (t.contains("silencio")) {
            return when {
                contieneActivar(t) -> { prefs.modoSilencioso = true;  "Modo silencioso activado. Solo vibraré." }
                contieneDesactivar(t) -> { prefs.modoSilencioso = false; "Modo silencioso desactivado." }
                else -> null
            }
        }

        // ── Velocidad de voz ─────────────────────────────────────────────────
        if (t.contains("rapid") || t.contains("veloz") || t.contains("acelera")) {
            prefs.velocidadVoz = "rapido"
            return "Velocidad de voz ajustada a rápida."
        }
        if (t.contains("lent") || t.contains("despacio")) {
            prefs.velocidadVoz = "lento"
            return "Velocidad de voz ajustada a lenta."
        }
        if (t.contains("velocidad") && t.contains("normal")) {
            prefs.velocidadVoz = "normal"
            return "Velocidad de voz ajustada a normal."
        }

        // ── Distancia de alerta (ajuste de SEGURIDAD → sin ambigüedad) ─────────
        // Es un ajuste delicado: "0.5m" avisa solo cuando algo está MUY cerca (más tarde);
        // "2m" avisa con ANTICIPACIÓN (desde más lejos, con más tiempo); "1m" intermedio.
        // Antes la palabra suelta "cerca" mandaba a 0.5m: si el usuario decía "avísame antes,
        // no tan cerca" hacía lo OPUESTO. Ahora exige valor o intención clara; si es ambiguo, pide aclarar.
        if (t.contains("distancia") || t.contains("alerta") || t.contains("aviso")
            || t.contains("metro") || t.contains("cerca") || t.contains("lejos") || t.contains("anticipa")) {
            return when {
                // 1) Valor explícito en metros — lo más claro, gana primero.
                t.contains("medio metro") || t.contains("0.5") -> {
                    prefs.distanciaAlerta = "0.5m"; "Alertas a medio metro: avisaré solo cuando algo esté muy cerca."
                }
                t.contains("dos metro") || t.contains("2 metro") -> {
                    prefs.distanciaAlerta = "2m"; "Alertas a dos metros: avisaré con anticipación."
                }
                t.contains("un metro") || t.contains("1 metro") -> {
                    prefs.distanciaAlerta = "1m"; "Alertas a un metro, distancia normal."
                }
                // 2) Intención CLARA hacia anticipado (más tiempo / desde más lejos).
                t.contains("anticipa") || t.contains("con tiempo") || t.contains("antes") || t.contains("lejos") -> {
                    prefs.distanciaAlerta = "2m"; "Alertas con más anticipación, a dos metros."
                }
                // 3) Intención CLARA hacia avisar solo muy cerca (más tarde).
                t.contains("muy cerca") || t.contains("más tarde") || t.contains("mas tarde") || t.contains("solo cuando") -> {
                    prefs.distanciaAlerta = "0.5m"; "Alertas a medio metro: avisaré solo cuando algo esté muy cerca."
                }
                t.contains("normal") || t.contains("intermedi") -> {
                    prefs.distanciaAlerta = "1m"; "Alertas a un metro, distancia normal."
                }
                // 4) "cerca"/"lejos" sueltos son ambiguos para un ajuste de seguridad → pedir aclaración.
                else -> "Para la distancia de alerta decime un valor claro: medio metro, un metro o dos metros. " +
                        "Dos metros avisa con más anticipación; medio metro solo cuando algo está muy cerca."
            }
        }

        // ── Perfil de tono de voz ────────────────────────────────────────────
        if (t.contains("tono") || t.contains("voz") || t.contains("habla")) {
            return when {
                t.contains("calid") || t.contains("cálid") -> { prefs.perfilTono = "calida";  "Tono de voz ajustado a cálido." }
                t.contains("grave") || t.contains("seri")  -> { prefs.perfilTono = "grave";   "Tono de voz ajustado a grave." }
                t.contains("animad") || t.contains("alegr") -> { prefs.perfilTono = "animada"; "Tono de voz ajustado a animado." }
                t.contains("suave")                         -> { prefs.perfilTono = "suave";   "Tono de voz ajustado a suave." }
                t.contains("natural")                       -> { prefs.perfilTono = "natural"; "Tono de voz ajustado a natural." }
                // Pidió cambiar "la voz" sin precisar → guiar en vez de "no reconocí".
                else -> "Puedo cambiar el tono de la voz a cálido, grave, animado, suave o natural, " +
                        "o la velocidad a más lenta o más rápida. ¿Cuál querés?"
            }
        }

        return null
    }

    // OJO: "desactivar" CONTIENE "activ" → sin el guard, "desactiva el modo silencioso" se
    // interpretaba como ACTIVAR (bug reportado por Sofia: no se podía apagar). El guard
    // !contieneDesactivar hace que la intención de apagar gane siempre. Cubre vibración y silencio.
    private fun contieneActivar(t: String): Boolean =
        !contieneDesactivar(t) &&
        (t.contains("activ") || t.contains("enciende") || t.contains("prende") || t.contains("encender"))

    private fun contieneDesactivar(t: String): Boolean =
        t.contains("desactiv") || t.contains("apaga") || t.contains("quita") || t.contains("apagar")
}
