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
    @Volatile private var sessionId: Long = 0L
    private var locationManager: LocationManager? = null

    fun startSession(prefs: UserPreferences) {
        scope.launch(Dispatchers.IO) {
            val prefEntity = PreferenciaEntity(
                distanciaAlerta    = prefs.distanciaAlerta,
                velocidadVoz       = prefs.velocidadVoz,
                vibracionActivada  = prefs.vibracionActivada
            )
            val prefId = db.preferenciaDao().insert(prefEntity)
            val sesion = SesionEntity(
                fechaHoraInicio = System.currentTimeMillis(),
                idPreferencia   = prefId
            )
            sessionId = db.sesionDao().insert(sesion)
            Log.d(TAG, "SESSION iniciada id=$sessionId prefId=$prefId")
        }
        startGps()
    }

    fun stopSession() {
        locationManager?.removeUpdates(locationListener)
        Log.d(TAG, "SESSION finalizada id=$sessionId")
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
        val sid = sessionId
        if (sid == 0L) return@LocationListener
        scope.launch(Dispatchers.IO) {
            db.ubicacionDao().insert(
                UbicacionEntity(
                    latitud   = location.latitude,
                    longitud  = location.longitude,
                    fechaHora = System.currentTimeMillis(),
                    idSesion  = sid
                )
            )
            Log.d(TAG, "GPS registrado: lat=${location.latitude} lng=${location.longitude} sesion=$sid")
        }
    }
}
