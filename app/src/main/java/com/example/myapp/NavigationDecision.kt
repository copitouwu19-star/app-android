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
    "fireplace","waste container","infant bed","pillow","laptop","computer monitor","box","barrel"
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
                    NavDecision("Detente. $obj al frente.",
                        EventPriority.PELIGRO_INMEDIATO, 800L)
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
        val requestScan: Boolean = false,
        val interruptSpeech: Boolean = false
    )

    private var lastSig: String?    = null
    private var lastLevel: Int      = -1
    private var lastSpeakTime: Long = 0L
    // Reloj propio del anuncio de puerta/landmark — inmune a cualquier otro reset.
    private var lastDoorAnnounce: Long = 0L
    // Máximo nivel de peligro ANUNCIADO recientemente. Da histéresis: si la distancia
    // jitterea y el peligro rebota 4↔3↔4, solo el primer 4 es "escalón"; los rebotes
    // posteriores NO re-disparan. Se re-arma tras un rato de calma. (Fase E)
    private var recentMaxLevel: Int = -1

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

        // Punto de referencia (ej. puerta): se informa su presencia, no se esquiva.
        val isLandmark = mainTrack.label in LANDMARK_OBJECTS

        // Vehículo estacionado: sin movimiento lateral ni de acercamiento → baja prioridad
        val isParkedVehicle = mainTrack.label in VEHICLES &&
            kotlin.math.abs(mainTrack.vx) < 0.015f &&
            kotlin.math.abs(mainTrack.vy) < 0.015f &&
            !mainTrack.isApproaching && mainTrack.framesTracked >= 4

        val danger = when {
            isParkedVehicle -> minOf(mainTrack.dangerLevel.coerceAtLeast(0), 1)
            isLandmark      -> minOf(mainTrack.dangerLevel.coerceAtLeast(0), 1) // nunca "detente"
            else            -> mainTrack.dangerLevel.coerceAtLeast(0)
        }

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
        // Para peligro crítico/inmediato (danger >= 3): solo sugerir una dirección específica
        // si hay tracks visibles en ese lado que confirmen que el espacio es real.
        // Sin tracks al costado = la cámara no vio ese lado = no sabemos si está libre.
        val lHasTrackData = left.isNotEmpty()
        val rHasTrackData = right.isNotEmpty()

        // Política conservadora (decisión del usuario): NUNCA mandar hacia un lado
        // adivinado. Solo sugerir dirección con EVIDENCIA FUERTE = un lado observado
        // libre (tiene tracks que confirman espacio) Y el otro observado bloqueado.
        // Sin esa certeza → "Detente" a secas. Parar nunca te hace chocar; mandarte a
        // un lado que la cámara no vio, sí (fue el bug de la prueba 1: "izquierda" hacia
        // lo bloqueado). "Escanea/mueve el teléfono" queda eliminado: un ciego no puede
        // escanear como reflejo y la frase larga retrasaba el "Detente" crítico.
        val dir = when {
            isLandmark       -> "centro"   // sin instrucción de evasión para puntos de referencia
            !centerBlocked   -> "centro"
            lClear && lHasTrackData && !rClear -> "izquierda"
            rClear && rHasTrackData && !lClear -> "derecha"
            else             -> "ninguno"  // sin certeza → solo "Detente"
        }
        val dirPhrase = when (dir) {
            "izquierda" -> " Muévete a la izquierda."
            "derecha"   -> " Muévete a la derecha."
            else        -> ""
        }
        val requestScan = false

        // ── 6. Mensaje CORTO según nivel de peligro ──
        val msg: String
        val priority: EventPriority
        var vibrateMs = 0L

        when {
            isLandmark -> {
                // Puerta u otro punto de referencia: solo "Puerta enfrente / a la izquierda".
                // Sin "detente", sin dirección de evasión, sin vibración. Conciencia, no orden.
                val loc = if (mainTrack.zone == "centro") "enfrente" else "a la ${mainTrack.zone}"
                msg = "${groupLabel.replaceFirstChar { it.uppercaseChar() }} $loc."
                priority = EventPriority.NAVEGACION_NORMAL
            }
            danger >= 4 -> {
                msg = "¡Detente! $groupLabel muy cerca.$dirPhrase".trim()
                priority = EventPriority.CRITICO; vibrateMs = 1000L
            }
            danger >= 3 -> {
                // ~2-2.5m: aviso CLARO pero SIN "Detente". El "Detente" se reserva para
                // peligro inminente (danger 4, ~1.3m), como pidió la usuaria: no alarmar
                // cuando todavía hay margen. Si hay lado libre se sugiere; si no, solo el aviso.
                val loc3 = if (mainTrack.zone == "centro") "al frente" else "a la ${mainTrack.zone}"
                msg = "$groupLabel $loc3.$dirPhrase".trim()
                priority = EventPriority.PELIGRO_INMEDIATO; vibrateMs = 400L
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

        // ── 7. Anti-repetición: throttle por NIVEL DE PELIGRO, no por objeto ──
        // CAUSA RAÍZ del "detente detente detente" y del grupo que no termina la frase:
        // antes cada guardia estaba atada al NOMBRE del objeto. En un cuarto con fondo
        // (silla + persona + comedor) el "principal" parpadea frame a frame, y cada
        // cambio de nombre reseteaba contadores y saltaba los cooldowns → volvía a
        // hablar. Ahora hablamos por PELIGRO SOSTENIDO, no por cada objeto que aparece:
        // un solo reloj global que NO depende de qué objeto sea el principal.
        val timeSince = now - lastSpeakTime

        // Histéresis (Fase E): tras 6s de calma, olvidamos el peligro reciente y volvemos
        // a permitir un salto limpio. Confirmado por video: la distancia de la silla
        // saltaba 1.4m↔2.8m → peligro 4↔3 → antes cada re-subida era "escalón" → ráfaga.
        if (timeSince > 6_000L) recentMaxLevel = -1

        // Escalón REAL = supera el MÁXIMO reciente (no un rebote a un nivel ya visto) y
        // se sostuvo ≥2 frames. Primer salto a crítico = peligro inminente nuevo.
        val escalated     = danger > recentMaxLevel && mainTrack.dangerFrames >= 2
        val firstCritical = danger >= 4 && recentMaxLevel < 4 && mainTrack.dangerFrames >= 2

        // Punto de referencia (puerta): UNA vez por aproximación, con reloj propio que
        // ningún otro reset puede tocar. Se re-arma tras 8s (el usuario se alejó y volvió).
        if (isLandmark) {
            if (now - lastDoorAnnounce < 8_000L) return null
            lastDoorAnnounce = now
            lastLevel = danger; lastSpeakTime = now
            return SpeakDecision(msg, priority, 0L, false, interruptSpeech = false)
        }

        // Objetos lejanos (danger 0): una sola vez por objeto y por escena.
        if (danger == 0) {
            val sig = "$groupLabel|0"
            if (sig in warnedFarObjects && !escalated) return null
            warnedFarObjects.add(sig)
            lastSig = sig; lastLevel = danger; lastSpeakTime = now
            return SpeakDecision(msg, priority, 0L, false, interruptSpeech = false)
        }

        // Cooldown único por nivel — global, NO atado al nombre del objeto.
        val cooldown = when {
            danger >= 4 -> 2_500L   // crítico: recordatorio cada 2.5s mientras siga el peligro
            danger >= 3 -> 3_500L   // "detente": cada 3.5s
            danger >= 2 -> 6_000L   // "desvíate": más espaciado
            else        -> 9_000L   // aviso suave
        }

        // ¿Hablar? Brecha mínima de 1.5s entre CUALQUIER par de avisos → nunca dos
        // pegados (mata las ráfagas y los cortes a media palabra), salvo el primer
        // salto a crítico real (peligro inminente nuevo), que siempre pasa.
        //
        // Repetir un aviso de peligro SOLO si el objeto se está ACERCANDO de verdad
        // (velocidad de profundidad suavizada). YA NO usamos el sensor de movimiento del
        // teléfono: el simple temblor de la mano superaba su umbral y hacía creer que el
        // usuario caminaba → repetía "detente, cama muy cerca" estando PARADO (caso Sofia).
        // Parado frente a un mueble que no se acerca → se dice UNA vez y se calla; al
        // caminar hacia él, vDepth sube y el aviso se reanuda. Umbral 0.02 (> jitter típico).
        val gettingCloser = mainTrack.vDepth > 0.02f
        val speak = when {
            firstCritical      -> true
            timeSince < 1_500L -> false
            escalated          -> true
            else               -> gettingCloser && timeSince >= cooldown
        }
        if (!speak) return null

        // Vibración solo en el primer aviso de cada escalón real, no en cada repetición.
        val finalVibrateMs = if (escalated || firstCritical) vibrateMs else 0L

        lastSig        = "$groupLabel|$danger"
        lastLevel      = danger
        lastSpeakTime  = now
        recentMaxLevel = maxOf(recentMaxLevel, danger)

        android.util.Log.d(TAG, "SPEAK danger=$danger dir=$dir obj=$groupLabel esc=$escalated fc=$firstCritical msg='$msg'")
        // Interrumpir el habla en curso SOLO en el salto real a crítico acercándose —
        // una vez, no en cada rebote 3↔4 (eso cortaba la frase a media palabra).
        val doInterrupt = firstCritical && mainTrack.isApproaching
        return SpeakDecision(msg, priority, finalVibrateMs, requestScan, interruptSpeech = doInterrupt)
    }

    fun reset() {
        lastSig = null
        lastLevel = -1
        lastSpeakTime = 0L
        lastDoorAnnounce = 0L
        recentMaxLevel = -1
        warnedFarObjects.clear()
    }
}
