package com.example.myapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SessionManager(private val context: Context, private val scope: CoroutineScope) {

    private val db = AppDatabase.get(context)
    @Volatile private var sessionId: Long = 0L        // id local (SQLite, respaldo offline)
    @Volatile private var cloudSesionId: String = ""  // id en la nube (Supabase)
    private var locationManager: LocationManager? = null

    fun startSession(prefs: UserPreferences) {
        val deviceId    = prefs.idDispositivo                       // usuario anónimo (sin login)
        val cloudPrefId = java.util.UUID.randomUUID().toString()
        val inicio      = System.currentTimeMillis()
        cloudSesionId   = java.util.UUID.randomUUID().toString()

        scope.launch(Dispatchers.IO) {
            // ── Local (SQLite): respaldo offline + modelo E-R para el informe ──
            val prefEntity = PreferenciaEntity(
                distanciaAlerta    = prefs.distanciaAlerta,
                velocidadVoz       = prefs.velocidadVoz,
                vibracionActivada  = prefs.vibracionActivada
            )
            val prefId = db.preferenciaDao().insert(prefEntity)
            sessionId  = db.sesionDao().insert(
                SesionEntity(fechaHoraInicio = inicio, idPreferencia = prefId)
            )
            Log.d(TAG, "SESSION local id=$sessionId prefId=$prefId")

            // ── Nube (Supabase): tiempo real, visible desde la PC sin cable ──
            SupabaseSync.upsertUsuario(deviceId)
            SupabaseSync.insertPreferencia(
                cloudPrefId, deviceId,
                prefs.distanciaAlerta, prefs.velocidadVoz, prefs.vibracionActivada
            )
            SupabaseSync.insertSesion(cloudSesionId, deviceId, cloudPrefId, SupabaseSync.iso(inicio))
            Log.d(TAG, "SESSION nube id=$cloudSesionId user=$deviceId")
        }
        startGps()
    }

    fun stopSession() {
        locationManager?.removeUpdates(locationListener)
        val sid = cloudSesionId
        if (sid.isNotEmpty()) {
            scope.launch(Dispatchers.IO) { SupabaseSync.cerrarSesion(sid, SupabaseSync.isoNow()) }
        }
        Log.d(TAG, "SESSION finalizada local=$sessionId nube=$sid")
    }

    private fun startGps() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "GPS: permiso no concedido, no se registrará ubicación")
            return
        }
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                30_000L,  // mínimo 30 segundos entre actualizaciones
                10f,      // o 10 metros de desplazamiento
                locationListener
            )
            Log.d(TAG, "GPS: escuchando ubicaciones")
        } catch (e: Exception) {
            Log.w(TAG, "GPS no disponible (normal en interiores): ${e.message}")
        }
    }

    private val locationListener = LocationListener { location ->
        val sidLocal = sessionId
        val sidCloud = cloudSesionId
        val ahora    = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            // Local
            if (sidLocal != 0L) {
                db.ubicacionDao().insert(
                    UbicacionEntity(
                        latitud   = location.latitude,
                        longitud  = location.longitude,
                        fechaHora = ahora,
                        idSesion  = sidLocal
                    )
                )
            }
            // Nube
            if (sidCloud.isNotEmpty()) {
                SupabaseSync.insertUbicacion(
                    sidCloud, location.latitude, location.longitude, SupabaseSync.iso(ahora)
                )
            }
            Log.d(TAG, "GPS lat=${location.latitude} lng=${location.longitude} local=$sidLocal nube=$sidCloud")
        }
    }
}
