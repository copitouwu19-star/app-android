package com.example.myapp

// ─────────────────────────────────────────────────────────────────────────────
// NAVIGATION DECISION — Lógica de escena y decisión de guiado
// Extraído de MainActivity para mantener el Activity limpio.
// ─────────────────────────────────────────────────────────────────────────────

// ── Tipos de escena ───────────────────────────────────────────────────────────
enum class SceneType {
    COCINA, HABITACION, SALA, BANO, OFICINA,
    INTERIOR_DESPEJADO, INTERIOR_CONCURRIDO,
    EXTERIOR_TRANQUILO, EXTERIOR_CONCURRIDO,
    CRUCE_PELIGROSO, DESCONOCIDO
}

private val COCINA_HINTS     = setOf("microwave oven","oven","toaster","refrigerator","sink","bowl","coffee cup","mug",
    "bottle","fork","knife","spoon","dishwasher","coffeemaker","cutting board","kitchen & dining room table","kitchen appliance")
private val HABITACION_HINTS = setOf("bed","clock","teddy bear","hair dryer","nightstand","wardrobe",
    "chest of drawers","pillow","infant bed","digital clock","wall clock")
private val SALA_HINTS       = setOf("couch","television","remote control","vase","houseplant","book",
    "coffee table","loveseat","sofa bed","fireplace")
private val BANO_HINTS       = setOf("toilet","sink","toothbrush","hair dryer","bathroom cabinet",
    "bathtub","shower","soap dispenser","towel","mirror")
private val OFICINA_HINTS    = setOf("laptop","computer keyboard","computer mouse","chair","clock","book",
    "mobile phone","desk","bookcase","filing cabinet","printer","whiteboard","computer monitor")

// Conjuntos para la lógica de decisión (anti-falsos-positivos y clústering de muebles)
private val PERSON_VEHICLE = setOf("person","man","woman","boy","girl","car","truck","bus","motorcycle","bicycle","van","taxi","ambulance")
private val FURNITURE_OBJS = setOf(
    "chair","couch","loveseat","sofa bed","studio couch","kitchen & dining room table","coffee table","table",
    "bed","bench","toilet","houseplant","plant","television","refrigerator","microwave oven","oven","sink",
    "chest of drawers","nightstand","wardrobe","bookcase","desk","cabinetry","bathroom cabinet",
    "cupboard","shelf","filing cabinet","stool","lamp","mirror","dishwasher","washing machine",
    "fireplace","waste container","infant bed"
)

fun inferScene(labels: List<String>, areas: List<Float>): SceneType {
    if (labels.isEmpty()) return SceneType.DESCONOCIDO
    val indoor  = labels.count { it in INDOOR_OBJS }
    val outdoor = labels.count { it in OUTDOOR_OBJS }
    val persons = labels.count { it == "person" }
    val cross   = labels.count { it in CROSSING_HINTS }

    if (cross >= 2 && (labels.contains("traffic light") || labels.contains("stop sign")))
        return SceneType.CRUCE_PELIGROSO

    val isIndoor  = indoor  > outdoor || (indoor  > 0 && outdoor == 0)
    val isOutdoor = outdoor > indoor  || (outdoor > 0 && indoor  == 0)
    val crowded   = labels.size >= 5 || persons >= 3 || areas.sum() > 0.35f

    if (isIndoor) {
        val cocinaScore = labels.count { it in COCINA_HINTS }
        val habScore    = labels.count { it in HABITACION_HINTS }
        val salaScore   = labels.count { it in SALA_HINTS }
        val banoScore   = labels.count { it in BANO_HINTS }
        val oficScore   = labels.count { it in OFICINA_HINTS }
        val maxScore = maxOf(cocinaScore, habScore, salaScore, banoScore, oficScore)
        if (maxScore >= 2) return when (maxScore) {
            cocinaScore -> SceneType.COCINA
            habScore    -> SceneType.HABITACION
            salaScore   -> SceneType.SALA
            banoScore   -> SceneType.BANO
            else        -> SceneType.OFICINA
        }
        return if (crowded) SceneType.INTERIOR_CONCURRIDO else SceneType.INTERIOR_DESPEJADO
    }

    return when {
        isOutdoor && crowded  -> SceneType.EXTERIOR_CONCURRIDO
        isOutdoor && !crowded -> SceneType.EXTERIOR_TRANQUILO
        crowded               -> SceneType.INTERIOR_CONCURRIDO
        else                  -> SceneType.DESCONOCIDO
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NAVIGATION ENGINE — Reglas de evasión (usado como fallback cuando
//                     DecisionEngine no tiene datos suficientes)
// ─────────────────────────────────────────────────────────────────────────────
object NavigationEngine {
    data class NavDecision(
        val instruction: String,
        val priority: EventPriority,
        val vibrateMs: Long = 0L,
        val requestScan: Boolean = false
    )

    private val SIN_CONFIRMACION = setOf("car","truck","bus","motorcycle","bicycle","person","man","woman","boy","girl","dog","van","taxi","ambulance")

    fun decide(tracks: List<ObjectTrack>): NavDecision? {
        if (tracks.isEmpty()) return NavDecision("Camino libre. Avanza.", EventPriority.CONTEXTO)

        val FURNITURE_OBSTACLES = setOf("chair","couch","loveseat","kitchen & dining room table","coffee table","table",
            "bed","bench","toilet","chest of drawers","nightstand","desk","wardrobe","bookcase","stool","infant bed")
        val reliable = tracks.filter { t ->
            t.framesTracked >= 1 ||
                    (t.label in SIN_CONFIRMACION && t.depthScore >= DEPTH_PELIGRO) ||
                    (t.label in FURNITURE_OBSTACLES && t.depthScore >= DEPTH_CERCA)
        }
        if (reliable.isEmpty()) return null

        val center = reliable.filter { it.zone == "centro" }.maxByOrNull { it.depthScore }
        val left   = reliable.filter { it.zone == "izquierda" }
        val right  = reliable.filter { it.zone == "derecha" }
        val lClear = left.none  { it.depthScore >= DEPTH_CERCA }
        val rClear = right.none { it.depthScore >= DEPTH_CERCA }

        // REGLA 1: CRÍTICO — acercándose en trayectoria central, colisión inminente
        reliable.filter { it.zone == "centro" && it.isApproaching }
            .mapNotNull { t ->
                val (pd, _) = t.predict(COLLISION_FRAMES)
                if (pd >= DEPTH_CRITICO) t to pd else null
            }
            .maxByOrNull { it.second }
            ?.let { (t, _) ->
                val obj = shortName(t.label)
                val dir = when {
                    lClear -> " Esquiva a la izquierda."
                    rClear -> " Esquiva a la derecha."
                    else   -> " Detente ahora."
                }
                return NavDecision("¡Precaución! $obj al frente.$dir",
                    EventPriority.CRITICO, 1000L)
            }

        // REGLA 2: PELIGRO INMEDIATO — objeto muy cercano bloqueando el paso
        if (center != null && center.depthScore >= DEPTH_PELIGRO) {
            val obj = shortName(center.label)
            return when {
                lClear && !rClear ->
                    NavDecision("Detente. $obj al frente. Gira a la izquierda.",
                        EventPriority.PELIGRO_INMEDIATO, 800L)
                rClear && !lClear ->
                    NavDecision("Detente. $obj al frente. Gira a la derecha.",
                        EventPriority.PELIGRO_INMEDIATO, 800L)
                else ->
                    NavDecision("Detente. $obj bloqueando. Mueve el teléfono a los lados.",
                        EventPriority.PELIGRO_INMEDIATO, 800L, requestScan = true)
            }
        }

        // REGLA 3: VEHÍCULO LATERAL ACERCÁNDOSE
        val vehiculoLateral = (left + right)
            .filter { it.label in VEHICLES && it.isApproaching && it.depthScore >= DEPTH_CERCA }
            .maxByOrNull { it.depthScore }
        if (vehiculoLateral != null) {
            val obj  = shortName(vehiculoLateral.label)
            val away = if (vehiculoLateral.zone == "izquierda") "derecha" else "izquierda"
            return NavDecision("¡Precaución! $obj por ${vehiculoLateral.zone}. Muévete a la $away.",
                EventPriority.PELIGRO_INMEDIATO, 600L)
        }

        // REGLA 4: DESVÍO — 3-4m en centro, tiempo para esquivar suavemente
        if (center != null && center.depthScore >= DEPTH_CERCA) {
            val obj    = shortName(center.label)
            val dir    = if (lClear) "izquierda" else if (rClear) "derecha" else "un lado"
            val accion = if (center.isApproaching) "Esquiva" else "Desvíate"
            return NavDecision("$obj adelante. $accion hacia la $dir.",
                EventPriority.NAVEGACION_URGENTE, 300L)
        }

        // REGLA 5: AVISO ANTICIPADO — 5-7m
        if (center != null && center.depthScore >= DEPTH_AVISO) {
            val obj = shortName(center.label)
            return if (center.isApproaching)
                NavDecision("Precaución. $obj al frente y acercándose. Prepárate para desviar.",
                    EventPriority.NAVEGACION_NORMAL)
            else
                NavDecision("Precaución. $obj al frente. Avanza con cuidado.",
                    EventPriority.NAVEGACION_NORMAL)
        }

        // REGLA 6: AMENAZA LATERAL — objeto acercándose desde un lado
        val amenazaLateral = (left + right)
            .filter { it.isApproaching && it.depthScore >= DEPTH_CERCA && it.isConfirmed }
            .maxByOrNull { it.depthScore }
        if (amenazaLateral != null) {
            val obj  = shortName(amenazaLateral.label)
            val away = if (amenazaLateral.zone == "izquierda") "derecha" else "izquierda"
            return NavDecision("$obj por ${amenazaLateral.zone}. Desvíate a la $away.",
                EventPriority.NAVEGACION_URGENTE, 300L)
        }

        // REGLA 7: CONTEXTO LEJANO — solo objetos de alta prioridad >7m
        val lejano = reliable
            .filter { it.depthScore >= DEPTH_LEJANO && it.label in HIGH_PRIORITY_OBJS && !it.isApproaching }
            .maxByOrNull { it.depthScore }
        if (lejano != null) {
            val obj = shortName(lejano.label)
            val pos = if (lejano.zone == "centro") "al frente" else "a la ${lejano.zone}"
            return NavDecision("$obj $pos. Continúa con precaución.", EventPriority.CONTEXTO)
        }

        return NavDecision("Camino libre. Avanza.", EventPriority.CONTEXTO)
    }

    private fun shortName(label: String): String = LABEL_ES[label]?.short ?: label

    /** Describe el movimiento lateral/de aproximación de objetos animados */
    fun movimientoDesc(track: ObjectTrack): String? {
        val MOVING_LABELS = setOf("person","bicycle","motorcycle","dog","car","truck","bus")
        if (track.label !in MOVING_LABELS) return null
        val rapid = track.vDepth > 0.025f
        val lateral = when {
            track.vx < -0.020f -> "izquierda"
            track.vx >  0.020f -> "derecha"
            else               -> null
        }
        return when {
            rapid && lateral != null -> "moviéndose hacia ti y hacia la $lateral"
            rapid                   -> "moviéndose rápido hacia ti"
            lateral != null         -> "cruzando hacia la $lateral"
            else                    -> null
        }
    }

    fun hayPeligroActivo(tracks: List<ObjectTrack>): Boolean {
        val danger = tracks.filter { it.label !in SAFE_OBJECTS }
        return danger.any { it.zone == "centro" && it.depthScore >= DEPTH_AVISO } ||
                danger.any { it.isApproaching   && it.depthScore >= DEPTH_PELIGRO }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DECISION ENGINE — Decide CUÁNDO, QUÉ y CUÁNTAS VECES hablar
//
// Cambios respecto a versión anterior:
//   · "Warn once far": objetos en CONTEXTO (danger=0) se anuncian UNA sola vez.
//     No se repiten mientras la distancia no cambie de nivel.
//   · REPEAT_IF_NO_MOVE solo aplica a danger >= 1 (antes se disparaba en danger 0,
//     causando que objetos lejanos se repitieran cada 4.5 s sin que el usuario se moviera).
//   · resetWarnedFar(): llamar cuando la escena cambia significativamente.
// ─────────────────────────────────────────────────────────────────────────────
class DecisionEngine {

    data class SpeakDecision(
        val message: String,
        val priority: EventPriority,
        val vibrateMs: Long = 0L,
        val requestScan: Boolean = false
    )

    private var lastSig: String?    = null
    private var lastLevel: Int      = -1
    private var lastSpeakTime: Long = 0L
    private var speakCount: Int     = 0
    // Contador separado para grupos (persiste aunque cambie la dirección)
    private var groupSpeakCount: Int  = 0
    private var lastGroupLabel: String = ""

    private val warnedFarObjects = mutableSetOf<String>()

    /** Llamar cuando la escena cambia de forma significativa (ej. Gemini detecta cambio). */
    fun resetWarnedFar() = warnedFarObjects.clear()

    fun process(
        tracks: List<ObjectTrack>,
        lastMotionTime: Long,
        now: Long,
        minAlertDepth: Float = DEPTH_AVISO
    ): SpeakDecision? {

        // ── 1. Confirmación por tipo. Muebles y objetos fijos respetan minAlertDepth
        //       (distancia configurada por el usuario). Personas y vehículos siempre. ──
        val confirmed = tracks.filter { t ->
            if (t.depthScore < DEPTH_LEJANO) return@filter false
            when {
                t.label in SAFE_OBJECTS   -> false
                t.label in PERSON_VEHICLE -> t.framesTracked >= 3
                t.label in ANIMALS        -> t.framesTracked >= 2
                t.label in FURNITURE_OBJS ->
                    (t.framesTracked >= 1 || t.depthScore >= DEPTH_AVISO) && t.depthScore >= minAlertDepth
                else ->
                    (t.framesTracked >= 2 || t.depthScore >= DEPTH_PELIGRO) && t.depthScore >= minAlertDepth
            }
        }

        android.util.Log.d(TAG,
            "DECISION tracks=${tracks.size} confirmed=${confirmed.size} " +
            "labels=${confirmed.map{"${it.label}(d=${"%.2f".format(it.depthScore)},z=${it.zone})"}}")

        if (confirmed.isEmpty()) { lastSig = null; lastLevel = -1; return null }

        // ── 2. Zonas y despeje lateral ──
        val center = confirmed.filter { it.zone == "centro" }
        val left   = confirmed.filter { it.zone == "izquierda" }
        val right  = confirmed.filter { it.zone == "derecha" }
        val lClear = left.none  { it.depthScore >= DEPTH_CERCA }
        val rClear = right.none { it.depthScore >= DEPTH_CERCA }

        // ── 3. Objeto principal: el más cercano del centro; si el centro está
        //       libre, el más cercano en general (para mención lateral). ──
        val centerMain = center.maxByOrNull { it.depthScore }
        val mainTrack  = centerMain ?: confirmed.maxByOrNull { it.depthScore } ?: return null
        val centerBlocked = centerMain != null && centerMain.depthScore >= DEPTH_CERCA

        // Vehículo estacionado: sin movimiento lateral ni de acercamiento → baja prioridad
        val isParkedVehicle = mainTrack.label in VEHICLES &&
            kotlin.math.abs(mainTrack.vx) < 0.015f &&
            kotlin.math.abs(mainTrack.vy) < 0.015f &&
            !mainTrack.isApproaching && mainTrack.framesTracked >= 4

        val danger = if (isParkedVehicle) minOf(mainTrack.dangerLevel.coerceAtLeast(0), 1)
                     else mainTrack.dangerLevel.coerceAtLeast(0)

        // ── 4. Clústering: 3+ muebles juntos → se anuncian como grupo con nombre específico.
        //       "grupo de sillas" en vez de "silla, silla, silla...". ──
        val furnitureNear = confirmed.count { it.depthScore >= DEPTH_AVISO && it.label in FURNITURE_OBJS }
        val isGroup = mainTrack.label in FURNITURE_OBJS && furnitureNear >= 3
        val objName = when {
            isGroup -> {
                val topLabel = confirmed
                    .filter { it.depthScore >= DEPTH_AVISO && it.label in FURNITURE_OBJS }
                    .groupingBy { it.label }
                    .eachCount()
                    .maxByOrNull { it.value }?.key
                val topShort = topLabel?.let { LABEL_ES[it]?.short } ?: "muebles"
                val topCount = confirmed.count { it.label == topLabel && it.depthScore >= DEPTH_AVISO }
                if (topLabel != null && topCount.toFloat() / furnitureNear >= 0.6f) "${topShort}s"
                else "muebles"
            }
            else -> LABEL_ES[mainTrack.label]?.short ?: mainTrack.label
        }
        val groupLabel = if (isGroup) "grupo de $objName" else objName

        // ── 5. Dirección recomendada (corta y accionable) ──
        val dir = when {
            !centerBlocked   -> "centro"
            lClear && rClear -> "ambos"
            lClear           -> "izquierda"
            rClear           -> "derecha"
            else             -> "bloqueado"
        }
        // "ambos" = ambos lados libres → elegir el que tenga menos objetos (o izquierda por defecto).
        val dirPhrase = when (dir) {
            "izquierda" -> " Muévete a la izquierda."
            "derecha"   -> " Muévete a la derecha."
            "ambos"     -> if (left.size <= right.size) " Muévete a la izquierda."
                           else " Muévete a la derecha."
            "bloqueado" -> " Detente. Mueve el teléfono para encontrar una salida."
            else        -> ""
        }
        val requestScan = dir == "bloqueado"

        // ── 6. Mensaje CORTO según nivel de peligro ──
        val msg: String
        val priority: EventPriority
        var vibrateMs = 0L

        when {
            danger >= 4 -> {
                msg = "¡Detente! $groupLabel muy cerca.$dirPhrase".trim()
                priority = EventPriority.CRITICO; vibrateMs = 1000L
            }
            danger >= 3 -> {
                val loc3 = if (mainTrack.zone == "centro") "al frente" else "a la ${mainTrack.zone}"
                msg = "Detente. $groupLabel $loc3.$dirPhrase".trim()
                priority = EventPriority.PELIGRO_INMEDIATO; vibrateMs = 700L
            }
            danger >= 2 -> {
                if (centerBlocked) {
                    msg = if (isGroup) "Veo un $groupLabel al frente.$dirPhrase".trim()
                          else "$groupLabel al frente.$dirPhrase".trim()
                    priority = EventPriority.NAVEGACION_URGENTE; vibrateMs = 300L
                } else {
                    val lado = confirmed.filter { it.zone != "centro" && it.depthScore >= DEPTH_CERCA }
                        .maxByOrNull { it.depthScore }
                    if (lado == null) { lastSig = null; return null }
                    val nm = LABEL_ES[lado.label]?.short ?: lado.label
                    msg = "$nm a la ${lado.zone}. Sigue de frente."
                    priority = EventPriority.NAVEGACION_NORMAL
                }
            }
            danger >= 1 -> {
                // Sin acercamiento → informar pero aclarar que no hay peligro inmediato
                val sinPeligro = !mainTrack.isApproaching && danger == 1
                msg = when {
                    isParkedVehicle -> {
                        val vNombre = LABEL_ES[mainTrack.label]?.short ?: mainTrack.label
                        "$vNombre al frente, sin peligro inmediato."
                    }
                    isGroup -> "Veo un $groupLabel al frente.$dirPhrase".trim()
                    sinPeligro && dir == "centro" -> "$groupLabel al frente, sin peligro."
                    sinPeligro && dir != "centro" -> "$groupLabel a la ${mainTrack.zone}, sin peligro."
                    dir != "centro" -> "$groupLabel al frente.$dirPhrase".trim()
                    else -> "$groupLabel al frente."
                }
                priority = if (isParkedVehicle || sinPeligro) EventPriority.CONTEXTO
                           else EventPriority.NAVEGACION_NORMAL
            }
            else -> {
                // Objetos lejanos (>4m): mensaje muy corto, solo ubicación
                val loc = if (mainTrack.zone != "centro") " a la ${mainTrack.zone}" else " al frente"
                msg = "$groupLabel$loc."
                priority = EventPriority.CONTEXTO
            }
        }

        // ── 7. Anti-repetición por firma + warn-once para lejanos ──
        val sig          = "$groupLabel|$dir|$danger"
        val escalated    = danger > lastLevel
        val newSituation = sig != lastSig
        val userMoved    = (now - lastMotionTime) < 3_000L
        val timeSince    = now - lastSpeakTime

        // Gestión del contador de grupo: persiste aunque cambie la dirección
        if (isGroup) {
            if (groupLabel != lastGroupLabel) { groupSpeakCount = 0; lastGroupLabel = groupLabel }
        } else {
            groupSpeakCount = 0; lastGroupLabel = ""
        }

        // Al cambiar situación o escalar → reiniciar contador de repeticiones individuales
        if (newSituation || escalated) speakCount = 0

        // Objetos lejanos (danger 0): se anuncian una sola vez por escena.
        if (danger == 0 && sig in warnedFarObjects && !escalated) return null

        // Piso anti-spam general: si solo cambió el nombre (no escaló), espera 2s.
        if (danger in 1..2 && newSituation && !escalated && timeSince < 2_000L) return null

        // Anti-spam GRUPOS: cooldown por nivel que aplica INCLUSO al escalar.
        // Esto corrige "detente detente detente" cuando la persona se acerca a un grupo:
        // cada escalación (1→2→3→4) ya no dispara el aviso instantáneamente.
        if (isGroup) {
            val groupCooldown = when {
                danger >= 4 -> 3_000L    // crítico: mínimo 3s entre avisos
                danger >= 3 -> 5_000L    // peligro: mínimo 5s
                else        -> 10_000L   // aviso/cerca: mínimo 10s
            }
            if (timeSince < groupCooldown) return null
        }

        // Límite de repeticiones: danger 4 (crítico) → máx 3; danger 1-3 → máx 2.
        val effectiveCount = if (isGroup) groupSpeakCount else speakCount
        if (danger >= 4 && effectiveCount >= 3 && !escalated) return null
        if (danger in 1..3 && effectiveCount >= 2 && !escalated) return null

        val speak = when {
            danger >= 4 -> timeSince > COOLDOWN_CRITICO
            danger >= 3 -> newSituation || escalated || (mainTrack.isApproaching && timeSince > 4_000L)
            danger >= 1 -> newSituation || escalated || (!userMoved && timeSince > REPEAT_IF_NO_MOVE_MS)
            else        -> newSituation
        }
        if (!speak) return null

        // Vibración: 1.° aviso = solo voz, 2.° = vibración, 3.° = solo voz (todos los niveles).
        val finalVibrateMs = if (effectiveCount == 1) vibrateMs else 0L

        if (danger == 0) warnedFarObjects.add(sig)
        lastSig       = sig
        lastLevel     = danger
        lastSpeakTime = now
        if (isGroup) groupSpeakCount++ else speakCount++

        android.util.Log.d(TAG, "SPEAK danger=$danger dir=$dir obj=$groupLabel count=$effectiveCount isGroup=$isGroup msg='$msg'")
        return SpeakDecision(msg, priority, finalVibrateMs, requestScan)
    }

    fun reset() {
        lastSig = null
        lastLevel = -1
        lastSpeakTime = 0L
        speakCount = 0
        groupSpeakCount = 0
        lastGroupLabel = ""
        warnedFarObjects.clear()
    }
}
