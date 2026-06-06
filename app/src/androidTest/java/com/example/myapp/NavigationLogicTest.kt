package com.example.myapp

import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests instrumentados de la lógica de decisión de navegación.
 * Requieren dispositivo o emulador Android.
 * Ejecutar con: ./gradlew connectedAndroidTest
 *
 * Casos que verifican los 3 cambios principales:
 *   1. Warn-once-far: objetos lejanos se anuncian UNA sola vez por escena
 *   2. REPEAT_IF_NO_MOVE no dispara en danger=0 (objetos lejanos)
 *   3. Objetos críticos se anuncian repetidamente tras cooldown
 */
@RunWith(AndroidJUnit4::class)
class NavigationLogicTest {

    private lateinit var engine: DecisionEngine

    @Before
    fun setup() {
        engine = DecisionEngine()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeTrack(
        id: Int = 1,
        label: String,
        depthScore: Float,
        zone: String = "centro",
        framesTracked: Int = 5,
        vDepth: Float = 0f
    ): ObjectTrack {
        val cx = when (zone) { "izquierda" -> 0.15f; "derecha" -> 0.85f; else -> 0.50f }
        val box = RectF(cx - 0.10f, 0.30f, cx + 0.10f, 0.60f)
        val dangerLevel = when {
            depthScore >= DEPTH_CRITICO -> 4
            depthScore >= DEPTH_PELIGRO -> 3
            depthScore >= DEPTH_CERCA   -> 2
            depthScore >= DEPTH_AVISO   -> 1
            depthScore >= DEPTH_LEJANO  -> 0
            else                        -> -1
        }
        return ObjectTrack(
            id = id, label = label, box = box,
            depthScore = depthScore, cx = cx, cy = 0.45f,
            vx = 0f, vy = 0f, vDepth = vDepth,
            framesTracked = framesTracked, dangerFrames = framesTracked,
            lastSeen = System.currentTimeMillis(), score = 0.9f,
            dangerLevel = dangerLevel
        )
    }

    // ── TEST 1: Warn-once-far ─────────────────────────────────────────────────

    @Test
    fun objetoLejanoSoloSeAnunciaUnaVez() {
        val now = System.currentTimeMillis()
        val track = makeTrack(label = "person", depthScore = DEPTH_LEJANO + 0.01f)

        val d1 = engine.process(listOf(track), now - 10_000, now)
        assertNotNull("Primera vez: debe hablar", d1)

        // Misma posición, mismo peligro, 200ms después
        val d2 = engine.process(listOf(track), now - 10_000, now + 200)
        assertNull("Segunda vez: debe suprimir (warn-once-far)", d2)

        // Incluso después de 5 segundos quieto, no debe repetir objeto lejano
        val d3 = engine.process(listOf(track), now - 10_000, now + 5_500)
        assertNull("Objeto lejano no repite aunque usuario esté quieto", d3)
    }

    // ── TEST 2: Escalado limpia warn-once ─────────────────────────────────────

    @Test
    fun objetoQueEscalaVuelveAAnunciarse() {
        val now = System.currentTimeMillis()

        // Primero lejos
        val trackLejano = makeTrack(id = 1, label = "chair", depthScore = DEPTH_LEJANO + 0.01f)
        engine.process(listOf(trackLejano), now - 10_000, now)  // se anuncia

        // Luego el mismo objeto (mismo id) se acerca a CERCA
        val trackCerca = makeTrack(id = 1, label = "chair", depthScore = DEPTH_CERCA + 0.01f)
        val d = engine.process(listOf(trackCerca), now - 10_000, now + 300)
        assertNotNull("Objeto escalado debe volver a anunciarse", d)
        assertTrue("Nivel de prioridad debe ser al menos NAVEGACION_URGENTE",
            d!!.priority.level >= EventPriority.NAVEGACION_URGENTE.level)
    }

    // ── TEST 3: Objeto crítico repite tras cooldown ───────────────────────────

    @Test
    fun objetoCriticoRepiteTrasElCooldown() {
        val now = System.currentTimeMillis()
        val track = makeTrack(label = "person", depthScore = DEPTH_CRITICO + 0.01f)

        val d1 = engine.process(listOf(track), now - 10_000, now)
        assertNotNull("Primera alerta crítica debe emitirse", d1)
        assertEquals("Debe ser prioridad CRITICO", EventPriority.CRITICO, d1!!.priority)

        // Dentro del cooldown → silencio
        val d2 = engine.process(listOf(track), now - 10_000, now + 500)
        assertNull("Dentro del cooldown crítico no debe repetir", d2)

        // Tras cooldown (COOLDOWN_CRITICO = 1500ms) → habla de nuevo
        val d3 = engine.process(listOf(track), now - 10_000, now + COOLDOWN_CRITICO + 100)
        assertNotNull("Tras cooldown debe repetir la alerta crítica", d3)
    }

    // ── TEST 4: REPEAT_IF_NO_MOVE no aplica a objetos lejanos ────────────────

    @Test
    fun repeatIfNoMoveNoAplicaEnDangerCero() {
        val now = System.currentTimeMillis()
        val track = makeTrack(label = "bench", depthScore = DEPTH_LEJANO + 0.01f)

        // Primera vez: se anuncia
        engine.process(listOf(track), now - 10_000, now)

        // Simular usuario completamente quieto durante mucho tiempo
        val quietNow = now + REPEAT_IF_NO_MOVE_MS + 5_000
        val d = engine.process(listOf(track), now - 30_000, quietNow)  // lastMotionTime muy atrás
        assertNull("Objeto lejano no debe repetir por inactividad del usuario", d)
    }

    // ── TEST 5: REPEAT_IF_NO_MOVE SÍ aplica en danger >= 1 ───────────────────

    @Test
    fun repeatIfNoMoveAplicaEnDangerUno() {
        val now = System.currentTimeMillis()
        val track = makeTrack(label = "person", depthScore = DEPTH_AVISO + 0.01f)

        // Primera vez: se anuncia
        engine.process(listOf(track), now - 10_000, now)

        // Usuario quieto por más de REPEAT_IF_NO_MOVE_MS → debe repetir (danger=1)
        val quietNow = now + REPEAT_IF_NO_MOVE_MS + 500
        val d = engine.process(listOf(track), now - 30_000, quietNow)
        assertNotNull("Objeto en danger=1 debe repetir cuando el usuario no se mueve", d)
    }

    // ── TEST 6: reset() limpia el estado ─────────────────────────────────────

    @Test
    fun resetLimpiaEstadoCompleto() {
        val now = System.currentTimeMillis()
        val track = makeTrack(label = "person", depthScore = DEPTH_LEJANO + 0.01f)

        // Anunciar y verificar que se suprime
        engine.process(listOf(track), now - 10_000, now)
        assertNull(engine.process(listOf(track), now - 10_000, now + 100))

        // Después de reset, debe anunciar de nuevo
        engine.reset()
        val d = engine.process(listOf(track), now - 10_000, now + 200)
        assertNotNull("Después de reset() debe volver a anunciar", d)
    }
}
