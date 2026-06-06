package com.example.myapp

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

// ─────────────────────────────────────────────────────────────────────────────
// GEMINI ADVISOR — Consultor ocasional, NO cerebro principal
//
// Solo se activa en 3 momentos concretos:
//   1. Al arrancar → describirEntornoInicial()  (~1 llamada por sesión)
//   2. YOLO confuso → confirmarObjeto()          (raro, <5 por sesión)
//   3. Entorno cambió → actualizarEscena()       (máx 1 por minuto)
//
// Estimado: 15-40 llamadas por hora de uso intensivo.
// Sin dependencias externas — solo HttpURLConnection del SDK Android.
// Si falla o hay timeout → retorna null → MainActivity usa fallback local.
// ─────────────────────────────────────────────────────────────────────────────
class GeminiAdvisor(private val apiKey: String) {

    companion object {
        private const val TAG          = "GeminiAdvisor"
        private const val ENDPOINT     = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
        private const val JPEG_QUALITY = 55      // peso reducido sin perder info relevante
        private const val TIMEOUT_MS   = 6_000L
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MÉTODO 1 — Descripción inicial del entorno
    //
    // Llamada UNA SOLA VEZ al arrancar, cuando YOLO tiene 5+ frames estables.
    // Identifica el tipo de lugar específico (cocina, sala, salón de clases,
    // calle, supermercado, etc.) además de la instrucción inmediata.
    // Si retorna null → MainActivity ejecuta su describirEntornoInicial() local.
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun describirEntornoInicial(
        bitmap: Bitmap,
        yoloLabels: List<String>,
        labelMasCercano: String?
    ): String? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(TIMEOUT_MS) {
            val labelsCtx  = yoloLabels.distinct().joinToString(", ").ifEmpty { "ninguno" }
            val cercanoCtx = labelMasCercano ?: "ninguno"

            val prompt = """
Eres el asistente de un bastón inteligente para personas ciegas.
El teléfono está al pecho a 1.40 m de altura, la cámara apunta al frente.

YOLO detectó en escena: $labelsCtx
Objeto más cercano: $cercanoCtx

Responde con DOS frases en español:
1. Tipo de lugar ESPECÍFICO: cocina, sala, habitación, baño, oficina, salón de clases, pasillo, tienda, supermercado, calle, parque, estacionamiento, etc. (máx 6 palabras, incluye detalles si los ves).
2. Instrucción inmediata: si hay algo relevante di qué hacer, si el camino está libre di "Puedes avanzar con cuidado."

REGLAS:
- Máximo 25 palabras en total
- Sin saludos, sin "Veo" ni "Detecto", sin explicaciones
- Tono directo como un acompañante humano real
- Ejemplo bueno: "Estás en una cocina con mesa al frente. Desvíate a la izquierda."
- Si el lugar es irreconocible: "Analizando el entorno. Avanza despacio."
            """.trimIndent()

            callGemini(bitmap, prompt)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MÉTODO 2 — Confirmar objeto ambiguo
    //
    // Se activa cuando YOLO oscila entre 2 etiquetas para el mismo objeto
    // en menos de 2 segundos. Retorna nombre en español, o null si falla.
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun confirmarObjeto(
        bitmap: Bitmap,
        label1: String,
        label2: String
    ): String? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(4_000L) {
            val prompt = """
Sistema de visión para personas ciegas.
YOLO no puede decidir si el objeto es "$label1" o "$label2".
Responde SOLO con el nombre del objeto en español (1-3 palabras).
Si no puedes determinarlo: responde exactamente: desconocido
            """.trimIndent()

            callGemini(bitmap, prompt)
                ?.trim()
                ?.takeIf { it.lowercase() != "desconocido" }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MÉTODO 3 — Actualización periódica de escena
    //
    // Se activa máximo 1 vez cada 40s, solo cuando no hay peligro activo.
    // Describe el tipo de lugar actual y si cambió algo relevante.
    // Si retorna null → MainActivity usa buildSceneMessage() local.
    // Si Gemini responde "sin_cambio" → no se dice nada.
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun actualizarEscena(
        bitmap: Bitmap,
        yoloLabels: List<String>,
        ultimaDescripcion: String
    ): String? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(TIMEOUT_MS) {
            val labelsCtx = yoloLabels.distinct().joinToString(", ").ifEmpty { "ninguno" }

            val prompt = """
Asistente de navegación para personas ciegas.
El teléfono está al pecho a 1.40 m de altura.

YOLO detectó ahora: $labelsCtx
Última descripción dada: "$ultimaDescripcion"

Evalúa si el entorno cambió de forma relevante para la navegación:
- Si SÍ cambió: una frase que incluya el tipo de lugar actual y el cambio (máx 15 palabras).
  Menciona el tipo de lugar si cambió (cocina, calle, pasillo, salón de clases, etc.)
- Si NO cambió: responde exactamente: sin_cambio

Sin saludos. Sin "Veo". Solo información útil para caminar.
            """.trimIndent()

            callGemini(bitmap, prompt)
                ?.trim()
                ?.takeIf { it.lowercase() != "sin_cambio" }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MÉTODO 4 — Sugerir salida cuando el camino está completamente bloqueado
    //
    // Se activa SOLO después de que el escaneo lateral falla y todos los
    // lados siguen bloqueados. Analiza la imagen y sugiere una acción concreta.
    // Retorna null si falla → MainActivity usa mensaje genérico de fallback.
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun sugerirSalidaBloqueado(
        bitmap: Bitmap,
        yoloLabels: List<String>,
        zonasBloqueadas: String
    ): String? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(8_000L) {
            val labelsCtx = yoloLabels.distinct().joinToString(", ").ifEmpty { "obstáculos" }

            val prompt = """
Asistente de bastón inteligente para persona ciega.
El camino está completamente bloqueado.
Obstáculos detectados: $labelsCtx
Zonas bloqueadas: $zonasBloqueadas

La persona ya intentó escanear los lados y sigue bloqueada.
Analiza la imagen y sugiere UNA acción específica y segura para salir (máx 15 palabras).

Acciones válidas según lo que veas:
- "Da media vuelta y camina hacia atrás unos pasos."
- "Espera, hay personas pasando."
- "El pasillo está a tu derecha, gira 90 grados."
- "Retrocede dos pasos, hay espacio detrás."
- Si no puedes determinar una salida: "Detente y pide ayuda a alguien cerca."

Sin "Veo" ni "Detecto". Solo la instrucción directa.
            """.trimIndent()

            callGemini(bitmap, prompt)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP — llamada a la API de Gemini
    // ─────────────────────────────────────────────────────────────────────────
    private fun callGemini(bitmap: Bitmap, promptText: String): String? {
        return try {
            val base64 = bitmapToBase64(bitmap)

            val requestBody = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray()
                        .put(JSONObject().put("inline_data", JSONObject()
                            .put("mime_type", "image/jpeg")
                            .put("data", base64)))
                        .put(JSONObject().put("text", promptText))
                    )
                ))
                put("generationConfig", JSONObject()
                    .put("maxOutputTokens", 80)
                    .put("temperature", 0.3)
                )
            }

            val conn = (URL("$ENDPOINT?key=$apiKey").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout    = 5_000
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }

            OutputStreamWriter(conn.outputStream).use { it.write(requestBody.toString()) }

            if (conn.responseCode != 200) {
                Log.w(TAG, "HTTP error ${conn.responseCode}")
                return null
            }

            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()

        } catch (e: Exception) {
            Log.w(TAG, "Gemini fallo silencioso: ${e.message}")
            null   // siempre retorna null en error — MainActivity maneja el fallback
        }
    }

    // Comprime el bitmap a JPEG calidad reducida antes de enviar
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}