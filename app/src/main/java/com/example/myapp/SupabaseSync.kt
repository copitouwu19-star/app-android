package com.example.myapp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ─────────────────────────────────────────────────────────────────────────────
// SUPABASE SYNC — espejo de los datos a PostgreSQL en la nube, en tiempo real.
//
// SIN login: usa la clave pública (publishable). Mismo patrón HttpURLConnection
// que GeminiAdvisor → sin dependencias extra. Escrituras "fire-and-forget": si no
// hay internet, fallan en silencio y el SQLite local sigue guardando todo de
// respaldo. Permite ver los datos de cada usuario desde la PC (panel de Supabase)
// sin cable y sin exportar nada a mano.
// ─────────────────────────────────────────────────────────────────────────────
object SupabaseSync {
    private const val TAG     = "SupabaseSync"
    private const val BASE    = "https://dsydcspidkevelhbswmn.supabase.co/rest/v1"
    private const val API_KEY = "sb_publishable_njELMM2b4Qrtd7W9U912Bw_SG_iPVS4"
    private const val TIMEOUT = 8_000

    /** Fecha-hora ISO-8601 UTC (lo que acepta timestamptz de Postgres). */
    fun isoNow(): String = iso(System.currentTimeMillis())
    fun iso(millis: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(millis))
    }

    private fun send(method: String, path: String, body: JSONObject, prefer: String): Boolean {
        return try {
            val conn = (URL("$BASE/$path").openConnection() as HttpURLConnection).apply {
                requestMethod  = method
                connectTimeout = TIMEOUT
                readTimeout    = TIMEOUT
                doOutput       = true
                setRequestProperty("apikey", API_KEY)
                setRequestProperty("Authorization", "Bearer $API_KEY")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Prefer", prefer)
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code !in 200..299)
                Log.w(TAG, "$method $path -> $code: ${conn.errorStream?.bufferedReader()?.readText()}")
            else
                Log.d(TAG, "$method $path -> $code OK")
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "$method $path falló (¿sin internet?): ${e.message}")
            false
        }
    }

    /** Registra el dispositivo/usuario (idempotente: no falla si ya existe). */
    suspend fun upsertUsuario(idUsuario: String) = withContext(Dispatchers.IO) {
        send("POST", "usuario",
            JSONObject().put("id_usuario", idUsuario),
            "resolution=merge-duplicates,return=minimal")
    }

    suspend fun insertPreferencia(
        id: String, idUsuario: String, distancia: String, velocidad: String, vibracion: Boolean
    ) = withContext(Dispatchers.IO) {
        send("POST", "preferencia",
            JSONObject()
                .put("id_preferencia", id)
                .put("id_usuario", idUsuario)
                .put("distancia_alerta", distancia)
                .put("velocidad_voz", velocidad)
                .put("vibracion_activada", vibracion),
            "return=minimal")
    }

    suspend fun insertSesion(
        id: String, idUsuario: String, idPreferencia: String, inicioIso: String
    ) = withContext(Dispatchers.IO) {
        send("POST", "sesion",
            JSONObject()
                .put("id_sesion", id)
                .put("id_usuario", idUsuario)
                .put("id_preferencia", idPreferencia)
                .put("inicio", inicioIso),
            "return=minimal")
    }

    /** Marca la hora de fin de la sesión (para calcular duración). */
    suspend fun cerrarSesion(idSesion: String, finIso: String) = withContext(Dispatchers.IO) {
        send("PATCH", "sesion?id_sesion=eq.$idSesion",
            JSONObject().put("fin", finIso),
            "return=minimal")
    }

    suspend fun insertUbicacion(
        idSesion: String, latitud: Double, longitud: Double, momentoIso: String
    ) = withContext(Dispatchers.IO) {
        send("POST", "ubicacion",
            JSONObject()
                .put("id_sesion", idSesion)
                .put("latitud", latitud)
                .put("longitud", longitud)
                .put("momento", momentoIso),
            "return=minimal")
    }
}
