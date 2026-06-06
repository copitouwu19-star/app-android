package com.example.myapp

// ─────────────────────────────────────────────────────────────────────────────
// DEPTH CALIBRATION — Matemática pura de estimación de distancia por píxeles.
// Sin imports de Android → testeable con JVM sin dispositivo.
//
// maxDist = 6m calibrado para que DEPTH_* coincida con sus metros reales:
//   DEPTH_CRITICO (0.78) ≈ 1.3m  |  DEPTH_PELIGRO (0.62) ≈ 2.3m
//   DEPTH_CERCA   (0.48) ≈ 3.1m  |  DEPTH_AVISO   (0.32) ≈ 4.1m
//   DEPTH_LEJANO  (0.18) ≈ 4.9m
//
// Con maxDist=15 (versión anterior), una silla a 3m daba depth=0.80 (CRITICO).
// Con maxDist=6, esa misma silla da depth=0.50 (CERCA). Alarmas correctas.
// ─────────────────────────────────────────────────────────────────────────────
internal object DepthCalibration {

    const val MAX_DIST_M  = 6f
    private const val FOV_H = 1.275f  // 2 * tan(32.5°), FOV horizontal ≈ 65°
    private const val FOV_V = 1.134f  // FOV vertical ≈ 65°

    internal val TYPICAL_HEIGHT_M = mapOf(
        "person"       to 1.70f, "man"          to 1.75f, "woman"        to 1.65f,
        "boy"          to 1.30f, "girl"         to 1.25f,
        "bicycle"      to 1.10f, "car"          to 1.45f, "motorcycle"   to 1.20f,
        "bus"          to 3.20f, "truck"        to 3.00f, "van"          to 1.80f,
        "taxi"         to 1.45f, "ambulance"    to 2.20f, "train"        to 3.50f,
        "boat"         to 2.00f, "traffic light" to 1.20f, "stop sign"   to 0.60f,
        "bench"        to 0.90f, "fire hydrant" to 0.60f, "parking meter" to 1.20f,
        "dog"          to 0.50f, "cat"          to 0.30f,
        // Muebles COCO (sin cambio)
        "chair"        to 0.90f, "couch"        to 0.85f, "bed"         to 0.55f,
        "refrigerator" to 1.70f,
        // Muebles OIV7 nuevos
        "kitchen & dining room table" to 0.75f, "coffee table"  to 0.45f,
        "table"        to 0.75f, "desk"         to 0.75f,
        "chest of drawers" to 1.10f, "nightstand" to 0.65f,
        "wardrobe"     to 2.00f, "bookcase"     to 1.80f,
        "cabinetry"    to 1.80f, "bathroom cabinet" to 0.80f,
        "cupboard"     to 1.60f, "shelf"        to 1.50f,
        "filing cabinet" to 1.30f, "stool"      to 0.70f,
        "loveseat"     to 0.85f, "sofa bed"     to 0.85f,
        "studio couch" to 0.85f, "infant bed"   to 0.90f,
        "television"   to 0.60f, "microwave oven" to 0.35f,
        "door"         to 2.10f, "ladder"       to 1.80f,
        "lamp"         to 1.50f, "mirror"       to 1.20f,
        "washing machine" to 0.85f, "dishwasher" to 0.85f
    )

    internal val TYPICAL_WIDTH_M = mapOf(
        "person"       to 0.50f, "man"          to 0.55f, "woman"        to 0.45f,
        "boy"          to 0.35f, "girl"         to 0.35f,
        "bicycle"      to 0.60f, "car"          to 1.80f, "motorcycle"   to 0.75f,
        "bus"          to 2.50f, "truck"        to 2.50f, "van"          to 1.90f,
        "taxi"         to 1.80f, "ambulance"    to 2.10f,
        "traffic light" to 0.30f, "stop sign"  to 0.60f, "bench"        to 1.50f,
        "dog"          to 0.40f, "cat"          to 0.25f, "fire hydrant" to 0.25f,
        // Muebles COCO — anchuras reales + margen por looseness de YOLO (~30% más)
        "chair"        to 0.65f, "couch"        to 2.20f, "bed"         to 1.90f,
        "refrigerator" to 0.90f, "toilet"       to 0.50f, "sink"        to 0.65f,
        // Muebles OIV7 — ídem
        "kitchen & dining room table" to 1.20f, "coffee table" to 1.30f,
        "table"        to 1.20f, "desk"         to 1.55f,
        "chest of drawers" to 1.05f, "nightstand" to 0.65f,
        "wardrobe"     to 1.30f, "bookcase"     to 1.05f,
        "cabinetry"    to 1.15f, "bathroom cabinet" to 0.75f,
        "cupboard"     to 1.05f, "shelf"        to 1.15f,
        "filing cabinet" to 0.65f, "stool"      to 0.50f,
        "loveseat"     to 1.80f, "sofa bed"     to 2.20f,
        "studio couch" to 1.90f, "infant bed"   to 0.90f,
        "television"   to 1.00f, "microwave oven" to 0.60f,
        "door"         to 1.00f, "ladder"       to 0.60f,
        "washing machine" to 0.75f, "dishwasher" to 0.75f
    )

    /** Profundidad normalizada (0=lejos, 1=muy cerca) estimada por la ALTURA del bbox */
    fun fromHeight(imgH: Float, label: String): Float? {
        val realH = TYPICAL_HEIGHT_M[label] ?: return null
        if (imgH <= 0.005f) return null
        val distM = realH / (imgH * FOV_V)
        return (1f - distM / MAX_DIST_M).coerceIn(0f, 1f)
    }

    /** Profundidad normalizada estimada por el ANCHO del bbox */
    fun fromWidth(imgW: Float, label: String): Float? {
        val realW = TYPICAL_WIDTH_M[label] ?: return null
        if (imgW <= 0.005f) return null
        val distM = realW / (imgW * FOV_H)
        return (1f - distM / MAX_DIST_M).coerceIn(0f, 1f)
    }

    /** Convierte profundidad normalizada a metros (útil para logs y tests) */
    fun toMeters(depth: Float): Float = (1f - depth.coerceIn(0f, 1f)) * MAX_DIST_M

    /**
     * Umbral duro por porcentaje de pantalla.
     * Si el bbox ocupa ese porcentaje → el objeto ESTÁ a menos de esa distancia, garantizado.
     * Actúa como piso: si la métrica da menos, este valor prevalece.
     *
     * Referencia: silla (0.50m) al 60% del ancho → dist = 0.50/(0.60×1.275) ≈ 0.65m → muy cerca
     */
    fun pixelThreshold(boxW: Float, boxH: Float, label: String): Float? = when (label) {
        "chair","couch","loveseat","sofa bed","studio couch",
        "kitchen & dining room table","coffee table","table","desk",
        "toilet","sink","bed","chest of drawers","nightstand",
        "wardrobe","bookcase","cabinetry","stool","infant bed" -> when {
            boxW >= 0.78f || boxH >= 0.70f -> DEPTH_PELIGRO + 0.05f  // < ~1.2m, realmente muy cerca
            boxW >= 0.48f || boxH >= 0.45f -> DEPTH_CERCA             // < ~3m
            else -> null
        }
        "person" -> when {
            boxW >= 0.40f -> DEPTH_PELIGRO + 0.05f   // persona a < ~1.2m
            boxW >= 0.22f -> DEPTH_CERCA
            else -> null
        }
        "car", "truck", "bus" -> when {
            boxW >= 0.70f -> DEPTH_CRITICO            // vehículo a < ~1m
            boxW >= 0.40f -> DEPTH_PELIGRO
            else -> null
        }
        "motorcycle", "bicycle" -> when {
            boxW >= 0.45f -> DEPTH_PELIGRO
            boxW >= 0.25f -> DEPTH_CERCA
            else -> null
        }
        else -> null
    }

    /**
     * Ancla métrica final: combina altura + ancho, aplica piso de píxeles.
     * Si ambas medidas coinciden en "cerca" → más confiable que solo una.
     * Si los píxeles indican "cerca" pero la métrica dice "lejos" → los píxeles ganan.
     */
    fun bestAnchor(boxW: Float, boxH: Float, label: String): Float? {
        val hD   = fromHeight(boxH, label)
        val wD   = fromWidth(boxW, label)
        val pxCap = pixelThreshold(boxW, boxH, label)

        val combined = when {
            hD != null && wD != null -> (hD + wD) / 2f
            hD != null               -> hD
            wD != null               -> wD
            else                     -> null
        }

        return when {
            combined != null && pxCap != null -> maxOf(combined, pxCap)
            combined != null                  -> combined
            pxCap    != null                  -> pxCap
            else                              -> null
        }
    }
}
