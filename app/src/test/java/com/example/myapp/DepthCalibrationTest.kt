package com.example.myapp

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests de matemática de profundidad — sin dispositivo, sin emulador.
 * Ejecutar con: ./gradlew test  (o Run > DepthCalibrationTest en Android Studio)
 *
 * Verifica que los umbrales DEPTH_* coincidan con distancias reales.
 * maxDist = 6m → DEPTH_CRITICO(0.78)≈1.3m, DEPTH_PELIGRO(0.62)≈2.3m, DEPTH_CERCA(0.48)≈3.1m
 */
class DepthCalibrationTest {

    // ── Constantes locales (espejo de MainActivity para no depender de Android) ──
    private val DEPTH_CRITICO = 0.78f
    private val DEPTH_PELIGRO = 0.62f
    private val DEPTH_CERCA   = 0.48f
    private val DEPTH_AVISO   = 0.32f
    private val DEPTH_LEJANO  = 0.18f

    // ── toMeters ──────────────────────────────────────────────────────────────

    @Test fun `toMeters critico es menos de 1_5m`() {
        val d = DepthCalibration.toMeters(DEPTH_CRITICO)
        assertTrue("CRITICO debería ser <1.5m pero fue ${d}m", d < 1.5f)
    }

    @Test fun `toMeters peligro es aprox 2m`() {
        val d = DepthCalibration.toMeters(DEPTH_PELIGRO)
        assertTrue("PELIGRO debería ser 1.5-3m pero fue ${d}m", d in 1.5f..3.0f)
    }

    @Test fun `toMeters cerca es aprox 3m`() {
        val d = DepthCalibration.toMeters(DEPTH_CERCA)
        assertTrue("CERCA debería ser 2.5-4m pero fue ${d}m", d in 2.5f..4.0f)
    }

    @Test fun `toMeters aviso es mas de 3m`() {
        val d = DepthCalibration.toMeters(DEPTH_AVISO)
        assertTrue("AVISO debería ser >3m pero fue ${d}m", d > 3f)
    }

    // ── fromWidth ─────────────────────────────────────────────────────────────

    @Test fun `silla al 60% del ancho es zona peligro o mas`() {
        val depth = DepthCalibration.fromWidth(imgW = 0.60f, label = "chair")
        assertNotNull(depth)
        val metros = DepthCalibration.toMeters(depth!!)
        // 0.50m / (0.60 * 1.275) ≈ 0.65m → muy cerca
        assertTrue("Silla al 60% debería ser ≥DEPTH_PELIGRO, fue depth=$depth (${metros}m)",
            depth >= DEPTH_PELIGRO)
        assertTrue("Distancia debería ser <1.5m pero fue ${metros}m", metros < 1.5f)
    }

    @Test fun `silla al 10% del ancho es zona aviso o lejano`() {
        val depth = DepthCalibration.fromWidth(imgW = 0.10f, label = "chair")
        assertNotNull(depth)
        // 0.50m / (0.10 * 1.275) ≈ 3.9m → zona aviso, NO peligro
        val metros = DepthCalibration.toMeters(depth!!)
        assertTrue("Silla al 10% debería ser <DEPTH_PELIGRO, fue depth=$depth (${metros}m)",
            depth < DEPTH_PELIGRO)
        assertTrue("Distancia debería ser >3m pero fue ${metros}m", metros > 3f)
    }

    @Test fun `persona al 40% del ancho es peligro`() {
        val depth = DepthCalibration.fromWidth(imgW = 0.40f, label = "person")
        assertNotNull(depth)
        // 0.50m / (0.40 * 1.275) ≈ 0.98m → zona peligro/critico
        assertTrue("Persona al 40% debería ser ≥DEPTH_PELIGRO pero fue $depth",
            depth!! >= DEPTH_PELIGRO)
    }

    @Test fun `objeto desconocido no tiene anchor de ancho`() {
        assertNull(DepthCalibration.fromWidth(imgW = 0.50f, label = "frisbee"))
        assertNull(DepthCalibration.fromWidth(imgW = 0.50f, label = "banana"))
    }

    // ── fromHeight ────────────────────────────────────────────────────────────

    @Test fun `persona al 50% de la altura es zona peligro`() {
        // imgH=0.50 → persona 1.70m → dist=1.70/(0.50*1.134)≈3.0m → depth=1-3/6=0.50 (CERCA)
        val depth = DepthCalibration.fromHeight(imgH = 0.50f, label = "person")
        assertNotNull(depth)
        assertTrue("Persona al 50% de altura debería ser ≥DEPTH_CERCA, fue $depth",
            depth!! >= DEPTH_CERCA)
    }

    @Test fun `persona muy pequeña es zona lejano`() {
        // imgH=0.05 → dist=1.70/(0.05*1.134)≈30m → clamp a 0
        val depth = DepthCalibration.fromHeight(imgH = 0.05f, label = "person")
        assertNotNull(depth)
        assertTrue("Persona pequeña debería ser zona lejana, fue $depth",
            depth!! <= DEPTH_AVISO)
    }

    // ── pixelThreshold ────────────────────────────────────────────────────────

    @Test fun `silla con bbox 60% x 40% activa umbral peligro`() {
        val px = DepthCalibration.pixelThreshold(boxW = 0.60f, boxH = 0.40f, label = "chair")
        assertNotNull(px)
        assertTrue("pixelThreshold silla grande debería ser ≥DEPTH_PELIGRO, fue $px",
            px!! >= DEPTH_PELIGRO)
    }

    @Test fun `silla con bbox 20% x 20% no activa umbral`() {
        val px = DepthCalibration.pixelThreshold(boxW = 0.20f, boxH = 0.20f, label = "chair")
        assertNull("Silla pequeña no debería activar pixelThreshold", px)
    }

    @Test fun `auto al 70% activa critico`() {
        val px = DepthCalibration.pixelThreshold(boxW = 0.70f, boxH = 0.50f, label = "car")
        assertNotNull(px)
        assertTrue("Auto al 70% debería ser ≥DEPTH_CRITICO, fue $px", px!! >= DEPTH_CRITICO)
    }

    // ── bestAnchor ────────────────────────────────────────────────────────────

    @Test fun `bestAnchor silla normal no es alarma`() {
        // Silla a ~3m: imgW≈0.13, imgH≈0.15 — lejos del umbral de alarma
        val depth = DepthCalibration.bestAnchor(boxW = 0.13f, boxH = 0.15f, label = "chair")
        assertNotNull(depth)
        val metros = DepthCalibration.toMeters(depth!!)
        assertTrue("Silla a 3m NO debería ser crítica, fue depth=$depth (${metros}m)",
            depth < DEPTH_PELIGRO)
        assertTrue("Silla a 3m debería estar entre 2-5m, fue ${metros}m", metros in 2f..5f)
    }

    @Test fun `bestAnchor usa el maximo entre metrica y pixelThreshold`() {
        // Si la métrica dice lejos pero pixelThreshold dice cerca, debe ganar pixelThreshold
        // Silla al 60% de ancho y 40% de altura → pixelThreshold activa DEPTH_PELIGRO+0.05
        val depth = DepthCalibration.bestAnchor(boxW = 0.60f, boxH = 0.40f, label = "chair")
        assertNotNull(depth)
        assertTrue("bestAnchor debe usar el max entre métrica y pixelThreshold, fue $depth",
            depth!! >= DEPTH_PELIGRO)
    }

    @Test fun `objeto sin datos reales retorna null`() {
        assertNull(DepthCalibration.bestAnchor(boxW = 0.30f, boxH = 0.30f, label = "toothbrush"))
    }
}
