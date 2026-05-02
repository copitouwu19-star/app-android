package com.example.myapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.speech.tts.TextToSpeech
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.PriorityQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

// ─────────────────────────────────────────────────────────────────────────────
// CONSTANTES
// ─────────────────────────────────────────────────────────────────────────────
internal const val TAG = "VisualNav"

internal const val MODELO_YOLO  = "yolov8n_320.tflite"
internal const val MODELO_DEPTH = "midas_v21_small_256.tflite"

internal const val YOLO_INPUT_SIZE = 320
internal const val SCORE_MINIMO    = 0.35f  // más sensible para detectar desde lejos
internal const val NMS_IOU_THRESH  = 0.45f
internal const val MAX_DETECCIONES = 15     // más detecciones para contexto completo

internal const val ZONA_IZQ = 0.30f
internal const val ZONA_DER = 0.70f

// Umbrales de profundidad — 5 niveles de distancia
// 0 = muy lejos, 1 = muy cerca
internal const val DEPTH_CRITICO  = 0.78f  // nivel 4: choque inminente
internal const val DEPTH_PELIGRO  = 0.62f  // nivel 3: detente/gira
internal const val DEPTH_CERCA    = 0.48f  // nivel 2: desvíate (≈3-4m)
internal const val DEPTH_AVISO    = 0.32f  // nivel 1: prepárate (≈5m)
internal const val DEPTH_LEJANO   = 0.18f  // nivel 0: mención contextual (≈7m)

// Tracking
internal const val IOU_MIN_MATCH           = 0.25f
internal const val MAX_FRAMES_PERDIDO      = 6
internal const val KALMAN_SMOOTH           = 0.55f
internal const val MIN_VELOCITY_WARN       = 0.012f
internal const val COLLISION_FRAMES        = 10
internal const val MIN_FRAMES_CONFIRMACION = 2  // reducido para respuesta más rápida

// Flash
internal const val DARK_THRESHOLD   = 55
internal const val TORCH_OFF_THRESH = 115
internal const val TORCH_DEBOUNCE   = 5_000L
internal const val BRIGHT_SAMPLES   = 8

// Cooldowns TTS
internal const val COOLDOWN_CRITICO    = 1_500L  // peligro máximo: 1.5s
internal const val COOLDOWN_PELIGRO    = 2_500L
internal const val COOLDOWN_NAVEGACION = 3_500L
internal const val COOLDOWN_QUIETO     = 25_000L
internal const val COOLDOWN_POST_SPEAK = 5_000L
internal const val COOLDOWN_ESCENA     = 40_000L
internal const val COOLDOWN_CRUCE      = 8_000L
internal const val STILLNESS_MS        = 7_000L

// Escaneo con giroscopio
internal const val SCAN_TRIGGER_DEPTH   = 0.70f   // si hay peligro y no se ve bien → pedir escaneo
internal const val SCAN_ROTATION_DEG    = 15f     // grados de rotación para considerar que escaneó
internal const val SCAN_COOLDOWN        = 15_000L // cada 15s puede pedir escaneo
internal const val SCAN_TIMEOUT_MS      = 8_000L  // 8s para completar el escaneo
internal const val REPEAT_IF_NO_MOVE_MS = 4_500L  // repetir instrucción si usuario no se movió en 4.5s

// ─────────────────────────────────────────────────────────────────────────────
// ETIQUETAS COCO — 80 clases
// ─────────────────────────────────────────────────────────────────────────────
internal val COCO_LABELS = listOf(
    "person","bicycle","car","motorcycle","airplane","bus","train","truck","boat",
    "traffic light","fire hydrant","stop sign","parking meter","bench",
    "bird","cat","dog","horse","sheep","cow","elephant","bear","zebra","giraffe",
    "backpack","umbrella","handbag","tie","suitcase","frisbee","skis","snowboard",
    "sports ball","kite","baseball bat","baseball glove","skateboard","surfboard",
    "tennis racket","bottle","wine glass","cup","fork","knife","spoon","bowl",
    "banana","apple","sandwich","orange","broccoli","carrot","hot dog","pizza",
    "donut","cake","chair","couch","potted plant","bed","dining table","toilet",
    "tv","laptop","mouse","remote","keyboard","cell phone","microwave","oven",
    "toaster","sink","refrigerator","book","clock","vase","scissors","teddy bear",
    "hair drier","toothbrush"
)

internal val VEHICLES       = setOf("bicycle","car","motorcycle","bus","train","truck","boat")
internal val ANIMALS        = setOf("bird","cat","dog","horse","sheep","cow","elephant","bear","zebra","giraffe")
internal val INDOOR_OBJS    = setOf("chair","couch","bed","dining table","toilet","tv","laptop",
    "sink","refrigerator","potted plant","clock","cup","bottle","cell phone","microwave","oven","toaster","book")
internal val OUTDOOR_OBJS   = setOf("car","truck","bus","motorcycle","bicycle",
    "traffic light","stop sign","fire hydrant","bench","train","boat","parking meter")
internal val CROSSING_HINTS = setOf("traffic light","stop sign","car","truck","bus","bicycle","motorcycle")

// Objetos peligrosos a cualquier distancia (avisar aunque estén lejos)
internal val HIGH_PRIORITY_OBJS = setOf("car","truck","bus","motorcycle","bicycle","person","dog","stairs")

// Objetos que NUNCA deben generar instrucciones de evasión o peligro.
// Solo se mencionan como contexto (muy lejos, o si se pregunta). Nunca activan alarma.
internal val SAFE_OBJECTS = setOf(
    "couch", "chair", "dining table", "bed", "tv", "laptop",
    "potted plant", "clock", "vase", "bottle", "cup", "sink",
    "refrigerator", "microwave", "oven", "toaster", "cell phone",
    "backpack", "umbrella", "handbag", "suitcase", "book",
    "wine glass", "fork", "knife", "spoon", "bowl", "banana",
    "apple", "sandwich", "orange", "donut", "cake", "remote",
    "keyboard", "mouse", "scissors", "teddy bear", "hair drier", "toothbrush"
)

internal data class LabelEs(val art: String, val noun: String, val short: String)
internal val LABEL_ES = mapOf(
    "person"        to LabelEs("una","persona","persona"),
    "bicycle"       to LabelEs("una","bicicleta","bici"),
    "car"           to LabelEs("un","automóvil","auto"),
    "motorcycle"    to LabelEs("una","motocicleta","moto"),
    "airplane"      to LabelEs("un","avión","avión"),
    "bus"           to LabelEs("un","autobús","autobús"),
    "train"         to LabelEs("un","tren","tren"),
    "truck"         to LabelEs("un","camión","camión"),
    "boat"          to LabelEs("un","bote","bote"),
    "traffic light" to LabelEs("un","semáforo","semáforo"),
    "fire hydrant"  to LabelEs("un","hidrante","hidrante"),
    "stop sign"     to LabelEs("una","señal de alto","señal"),
    "bench"         to LabelEs("una","banca","banca"),
    "bird"          to LabelEs("un","pájaro","pájaro"),
    "cat"           to LabelEs("un","gato","gato"),
    "dog"           to LabelEs("un","perro","perro"),
    "horse"         to LabelEs("un","caballo","caballo"),
    "cow"           to LabelEs("una","vaca","vaca"),
    "elephant"      to LabelEs("un","elefante","elefante"),
    "bear"          to LabelEs("un","oso","oso"),
    "backpack"      to LabelEs("una","mochila","mochila"),
    "umbrella"      to LabelEs("un","paraguas","paraguas"),
    "handbag"       to LabelEs("una","bolsa","bolsa"),
    "suitcase"      to LabelEs("una","maleta","maleta"),
    "chair"         to LabelEs("una","silla","silla"),
    "couch"         to LabelEs("un","sofá","sofá"),
    "potted plant"  to LabelEs("una","maceta","maceta"),
    "bed"           to LabelEs("una","cama","cama"),
    "dining table"  to LabelEs("una","mesa","mesa"),
    "toilet"        to LabelEs("un","inodoro","inodoro"),
    "tv"            to LabelEs("un","televisor","televisor"),
    "laptop"        to LabelEs("una","computadora","computadora"),
    "cell phone"    to LabelEs("un","celular","celular"),
    "sink"          to LabelEs("un","lavabo","lavabo"),
    "refrigerator"  to LabelEs("un","refrigerador","refrigerador"),
    "bottle"        to LabelEs("una","botella","botella"),
    "cup"           to LabelEs("una","taza","taza"),
    "clock"         to LabelEs("un","reloj","reloj"),
    "sports ball"   to LabelEs("una","pelota","pelota"),
    "stairs"        to LabelEs("unas","escaleras","escaleras"),
    "book"          to LabelEs("un","libro","libro"),
    "vase"          to LabelEs("un","florero","florero")
)

// ─────────────────────────────────────────────────────────────────────────────
// OVERLAY VISUAL — dibuja bounding boxes sobre la cámara (modo demo)
// ─────────────────────────────────────────────────────────────────────────────
class DetectionOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // Colores por nivel de peligro
    private val paintCritico   = Paint().apply { color = Color.RED;    strokeWidth = 6f; style = Paint.Style.STROKE }
    private val paintPeligro   = Paint().apply { color = Color.parseColor("#FF6600"); strokeWidth = 5f; style = Paint.Style.STROKE }
    private val paintCerca     = Paint().apply { color = Color.YELLOW; strokeWidth = 4f; style = Paint.Style.STROKE }
    private val paintAviso     = Paint().apply { color = Color.parseColor("#00CCFF"); strokeWidth = 3f; style = Paint.Style.STROKE }
    private val paintLejano    = Paint().apply { color = Color.WHITE;  strokeWidth = 2f; style = Paint.Style.STROKE; alpha = 150 }

    private val paintText = Paint().apply {
        color     = Color.WHITE
        textSize  = 36f
        typeface  = Typeface.DEFAULT_BOLD
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private val paintTextBg = Paint().apply {
        color = Color.parseColor("#AA000000")
        style = Paint.Style.FILL
    }

    // Lista de detecciones actual — actualizada desde el hilo de análisis
    @Volatile private var detections: List<Pair<RectF, Pair<String, Float>>> = emptyList()
    @Volatile private var imgW = 1; @Volatile private var imgH = 1

    fun update(dets: List<Pair<RectF, Pair<String, Float>>>, width: Int, height: Int) {
        detections = dets; imgW = width; imgH = height
        postInvalidate()  // pide redibujo en el hilo UI
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dets = detections; if (dets.isEmpty()) return

        val scaleX = width.toFloat()
        val scaleY = height.toFloat()

        for ((normBox, labelScore) in dets) {
            val (label, score) = labelScore

            // Desnormalizar a píxeles de pantalla
            val left   = normBox.left   * scaleX
            val top    = normBox.top    * scaleY
            val right  = normBox.right  * scaleX
            val bottom = normBox.bottom * scaleY

            val area  = normBox.width() * normBox.height()
            val depth = when {
                area >= 0.20f -> 0.85f
                area >= 0.08f -> 0.65f
                area >= 0.03f -> 0.48f
                area >= 0.01f -> 0.32f
                else          -> 0.18f
            }

            // Seleccionar color según nivel de peligro
            val paint = when {
                depth >= DEPTH_CRITICO -> paintCritico
                depth >= DEPTH_PELIGRO -> paintPeligro
                depth >= DEPTH_CERCA   -> paintCerca
                depth >= DEPTH_AVISO   -> paintAviso
                else                   -> paintLejano
            }

            // Dibujar bounding box
            canvas.drawRect(left, top, right, bottom, paint)

            // Etiqueta con nombre y score
            val shortName = LABEL_ES[label]?.short ?: label
            val labelText = "$shortName ${(score * 100).toInt()}%"
            val textW = paintText.measureText(labelText)
            val textH = paintText.textSize

            // Fondo de la etiqueta
            canvas.drawRect(left, top - textH - 8f, left + textW + 12f, top, paintTextBg)

            // Color del texto igual al del box
            paintText.color = paint.color
            canvas.drawText(labelText, left + 6f, top - 6f, paintText)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DETECTOR YOLO — CPU pura (sin GpuDelegate para estabilidad)
// ─────────────────────────────────────────────────────────────────────────────
data class Detection(val box: RectF, val label: String, val score: Float)

class YoloDetector(modelPath: String, context: Context) {
    private var interpreter: Interpreter? = null

    init {
        try {
            val fd     = context.assets.openFd(modelPath)
            val buffer = FileInputStream(fd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            // CPU x4 hilos — estable en todos los dispositivos
            // En Snapdragon 7s Gen 4 esto da ~80-120ms por frame
            interpreter = Interpreter(buffer, Interpreter.Options().apply { numThreads = 4 })
            Log.d(TAG, "YOLOv8n OK — CPU x4")
        } catch (e: Exception) { Log.e(TAG, "Error YOLO: ${e.message}") }
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val interp  = interpreter ?: return emptyList()
        val scaled  = Bitmap.createScaledBitmap(bitmap, YOLO_INPUT_SIZE, YOLO_INPUT_SIZE, true)
        val inputBuf = ByteBuffer.allocateDirect(4 * YOLO_INPUT_SIZE * YOLO_INPUT_SIZE * 3)
            .order(ByteOrder.nativeOrder())

        for (y in 0 until YOLO_INPUT_SIZE) for (x in 0 until YOLO_INPUT_SIZE) {
            val px = scaled.getPixel(x, y)
            inputBuf.putFloat(((px shr 16) and 0xFF) / 255f)
            inputBuf.putFloat(((px shr  8) and 0xFF) / 255f)
            inputBuf.putFloat(( px         and 0xFF) / 255f)
        }
        inputBuf.rewind()

        val outputBuf = Array(1) { Array(84) { FloatArray(2100) } }
        try { interp.run(inputBuf, outputBuf) }
        catch (e: Exception) { Log.e(TAG, "YOLO inf: ${e.message}"); return emptyList() }

        val raw = outputBuf[0]
        data class Raw(val box: RectF, val cls: Int, val score: Float)
        val raws = mutableListOf<Raw>()

        for (a in 0 until 2100) {
            var bestCls = 0; var bestScore = 0f
            for (c in 0 until 80) {
                val s = raw[4 + c][a]; if (s > bestScore) { bestScore = s; bestCls = c }
            }
            if (bestScore < SCORE_MINIMO) continue
            val cx = raw[0][a]; val cy = raw[1][a]
            val w  = raw[2][a]; val h  = raw[3][a]
            val box = RectF(
                (cx - w / 2f).coerceIn(0f, 1f), (cy - h / 2f).coerceIn(0f, 1f),
                (cx + w / 2f).coerceIn(0f, 1f), (cy + h / 2f).coerceIn(0f, 1f)
            )
            if (box.width() > 0f && box.height() > 0f) raws.add(Raw(box, bestCls, bestScore))
        }

        raws.sortByDescending { it.score }
        val kept = BooleanArray(raws.size) { true }
        for (i in raws.indices) {
            if (!kept[i]) continue
            for (j in i + 1 until raws.size) {
                if (!kept[j]) continue
                if (raws[i].cls == raws[j].cls && iou(raws[i].box, raws[j].box) > NMS_IOU_THRESH)
                    kept[j] = false
            }
        }
        return raws.indices.filter { kept[it] }.take(MAX_DETECCIONES)
            .map { Detection(raws[it].box, COCO_LABELS.getOrElse(raws[it].cls) { "objeto" }, raws[it].score) }
    }

    private fun iou(a: RectF, b: RectF): Float {
        val il = maxOf(a.left, b.left); val it = maxOf(a.top, b.top)
        val ir = minOf(a.right, b.right); val ib = minOf(a.bottom, b.bottom)
        if (ir <= il || ib <= it) return 0f
        val inter = (ir - il) * (ib - it)
        return inter / (a.width() * a.height() + b.width() * b.height() - inter)
    }

    fun close() = interpreter?.close()
}

// ─────────────────────────────────────────────────────────────────────────────
// DEPTH ESTIMATOR — MiDaS CPU
// ─────────────────────────────────────────────────────────────────────────────
class DepthEstimator(context: Context) {
    private val SZ = 256
    private var interpreter: Interpreter? = null

    init {
        try {
            val fd  = context.assets.openFd(MODELO_DEPTH)
            val buf = FileInputStream(fd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            interpreter = Interpreter(buf, Interpreter.Options().apply { numThreads = 2 })
            Log.d(TAG, "MiDaS OK — CPU x2")
        } catch (e: Exception) { Log.w(TAG, "MiDaS no disponible: ${e.message}") }
    }

    fun estimate(bitmap: Bitmap): Array<FloatArray>? {
        val interp = interpreter ?: return null
        val scaled = Bitmap.createScaledBitmap(bitmap, SZ, SZ, true)
        val inputBuf = ByteBuffer.allocateDirect(4 * SZ * SZ * 3).order(ByteOrder.nativeOrder())
        for (y in 0 until SZ) for (x in 0 until SZ) {
            val px = scaled.getPixel(x, y)
            inputBuf.putFloat(((px shr 16) and 0xFF) / 255f)
            inputBuf.putFloat(((px shr  8) and 0xFF) / 255f)
            inputBuf.putFloat(( px         and 0xFF) / 255f)
        }
        inputBuf.rewind()
        val out = Array(1) { Array(SZ) { FloatArray(SZ) } }
        return try {
            interp.runForMultipleInputsOutputs(arrayOf(inputBuf), mapOf(0 to out))
            val raw = out[0]
            var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
            for (row in raw) for (v in row) { if (v < mn) mn = v; if (v > mx) mx = v }
            val range = (mx - mn).coerceAtLeast(1e-6f)
            Array(SZ) { y -> FloatArray(SZ) { x -> (raw[y][x] - mn) / range } }
        } catch (e: Exception) { Log.e(TAG, "MiDaS err: ${e.message}"); null }
    }

    fun close() = interpreter?.close()
}

// ─────────────────────────────────────────────────────────────────────────────
// TRACKING + KALMAN
// ─────────────────────────────────────────────────────────────────────────────
data class ObjectTrack(
    val id: Int, val label: String,
    var box: RectF, var depthScore: Float = 0f,
    var cx: Float = box.centerX(), var cy: Float = box.centerY(),
    var vx: Float = 0f, var vy: Float = 0f, var vDepth: Float = 0f,
    var framesLost: Int = 0, var framesTracked: Int = 0,
    var lastSeen: Long = 0L, var score: Float = 0f,
    var dangerLevel: Int = 0, var dangerFrames: Int = 0
) {
    fun update(newBox: RectF, newDepth: Float, now: Long) {
        val newCx = newBox.centerX(); val newCy = newBox.centerY()
        vx     = KALMAN_SMOOTH * vx     + (1f - KALMAN_SMOOTH) * (newCx - cx)
        vy     = KALMAN_SMOOTH * vy     + (1f - KALMAN_SMOOTH) * (newCy - cy)
        vDepth = KALMAN_SMOOTH * vDepth + (1f - KALMAN_SMOOTH) * (newDepth - depthScore)
        box = newBox; cx = newCx; cy = newCy; depthScore = newDepth
        framesLost = 0; framesTracked++; lastSeen = now
        val newLevel = when {
            newDepth >= DEPTH_CRITICO -> 4
            newDepth >= DEPTH_PELIGRO -> 3
            newDepth >= DEPTH_CERCA   -> 2
            newDepth >= DEPTH_AVISO   -> 1
            newDepth >= DEPTH_LEJANO  -> 0
            else -> -1
        }
        if (newLevel == dangerLevel) dangerFrames++ else { dangerLevel = newLevel; dangerFrames = 1 }
    }

    fun predict(frames: Int): Pair<Float, Float> =
        Pair((depthScore + vDepth * frames).coerceIn(0f, 1f), (cx + vx * frames).coerceIn(0f, 1f))

    val isApproaching: Boolean get() = vDepth > MIN_VELOCITY_WARN
    val isConfirmed:   Boolean get() = dangerFrames >= MIN_FRAMES_CONFIRMACION
    val zone: String get() = when {
        cx < ZONA_IZQ -> "izquierda"
        cx > ZONA_DER -> "derecha"
        else          -> "centro"
    }
}

class TrackManager {
    private var nextId = 0
    private val tracks = mutableListOf<ObjectTrack>()

    fun update(detections: List<Detection>, depthMap: Array<FloatArray>?, now: Long): List<ObjectTrack> {
        tracks.removeIf { it.framesLost > MAX_FRAMES_PERDIDO }
        if (detections.isEmpty()) {
            tracks.forEach { it.framesLost++ }; return tracks.filter { it.framesLost == 0 }
        }

        data class Match(val ti: Int, val di: Int, val iou: Float)
        val candidates = mutableListOf<Match>()
        for ((ti, t) in tracks.withIndex()) for ((di, d) in detections.withIndex()) {
            if (d.label != t.label) continue
            val v = iouBoxes(t.box, d.box); if (v >= IOU_MIN_MATCH) candidates.add(Match(ti, di, v))
        }
        candidates.sortByDescending { it.iou }
        val matched = BooleanArray(detections.size); val usedTrks = mutableSetOf<Int>()
        for (m in candidates) {
            if (m.ti in usedTrks || matched[m.di]) continue
            val depth = depthMap?.let { sampleDepth(it, detections[m.di].box) } ?: fallback(detections[m.di].box, detections[m.di].label)
            tracks[m.ti].update(detections[m.di].box, depth, now)
            tracks[m.ti].score = detections[m.di].score
            matched[m.di] = true; usedTrks.add(m.ti)
        }
        for ((di, det) in detections.withIndex()) {
            if (matched[di]) continue
            val depth = depthMap?.let { sampleDepth(it, det.box) } ?: fallback(det.box, det.label)
            tracks.add(ObjectTrack(id = nextId++, label = det.label, box = det.box,
                depthScore = depth, score = det.score, lastSeen = now))
        }
        for ((ti, t) in tracks.withIndex()) { if (ti !in usedTrks) t.framesLost++ }
        return tracks.filter { it.framesLost == 0 }
    }

    private fun iouBoxes(a: RectF, b: RectF): Float {
        val il = maxOf(a.left, b.left); val it2 = maxOf(a.top, b.top)
        val ir = minOf(a.right, b.right); val ib = minOf(a.bottom, b.bottom)
        if (ir <= il || ib <= it2) return 0f
        val inter = (ir - il) * (ib - it2)
        return inter / (a.width() * a.height() + b.width() * b.height() - inter)
    }

    private fun sampleDepth(map: Array<FloatArray>, box: RectF): Float {
        val mh = map.size; val mw = map[0].size
        val cx = box.centerX(); val cy = box.centerY()
        val hw = box.width() * 0.3f; val hh = box.height() * 0.3f
        val x0 = ((cx - hw) * mw).toInt().coerceIn(0, mw - 1)
        val x1 = ((cx + hw) * mw).toInt().coerceIn(0, mw - 1)
        val y0 = ((cy - hh) * mh).toInt().coerceIn(0, mh - 1)
        val y1 = ((cy + hh) * mh).toInt().coerceIn(0, mh - 1)
        var sum = 0f; var count = 0
        for (y in y0..y1 step 2) for (x in x0..x1 step 2) { sum += map[y][x]; count++ }
        val midasDepth = if (count > 0) sum / count else 0f
        // MiDaS tiene 80% de peso — el área solo modula levemente (20%)
        // Antes era 60/40, lo que inflaba objetos grandes aunque estuvieran lejos
        val area = (box.width() * box.height()).coerceIn(0f, 1f)
        return (midasDepth * 0.8f + area * 0.2f).coerceIn(0f, 1f)
    }

    private fun fallback(box: RectF, label: String = ""): Float {
        // Primero intentar estimación métrica por altura real del objeto (más precisa)
        val metricDepth = metricDepthFromHeight(box, label)
        if (metricDepth != null) return metricDepth
        // Si no hay altura típica conocida, usar área con techo conservador
        val area = (box.width() * box.height()).coerceIn(0f, 1f)
        // El área sola NO determina cercanía — solo da una pista débil con techo bajo.
        // Mapeo conservador: nunca supera 0.42 (DEPTH_AVISO), jamás dispara peligro falso.
        return (area * 1.5f + 0.15f).coerceAtMost(0.42f)
    }

    fun allTracks(): List<ObjectTrack> = tracks.filter { it.framesLost == 0 }
    fun clear() = tracks.clear()

    // Altura real típica de objetos conocidos (en metros)
    private val TYPICAL_HEIGHT_M = mapOf(
        "person" to 1.70f, "bicycle" to 1.10f, "car" to 1.45f, "motorcycle" to 1.20f,
        "bus" to 3.20f, "truck" to 3.00f, "traffic light" to 1.20f, "stop sign" to 0.60f,
        "bench" to 0.90f, "dog" to 0.50f, "chair" to 0.90f, "couch" to 0.80f,
        "dining table" to 0.75f, "fire hydrant" to 0.60f, "parking meter" to 1.20f,
        "train" to 3.50f, "boat" to 2.00f
    )

    // Estima distancia en metros usando la proporción del objeto en imagen.
    // FOV vertical ~65°, distancia focal normalizada ~0.9
    // Retorna profundidad normalizada 0..1 (1 = muy cerca, 0 = muy lejos, max 15m)
    private fun metricDepthFromHeight(box: RectF, label: String): Float? {
        val realH = TYPICAL_HEIGHT_M[label] ?: return null
        val imgH  = box.height()
        if (imgH <= 0.005f) return null
        // distanceM = realH / (imgH * 2 * tan(32.5°)) → simplificado con constante calibrada
        val distanceM = (realH / imgH) * 0.65f
        // Normalizar: 0m = 1.0, 15m = 0.0
        return (1f - (distanceM / 15f)).coerceIn(0f, 1f)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TTS CON PRIORIDAD
// ─────────────────────────────────────────────────────────────────────────────
enum class EventPriority(val level: Int) {
    CRITICO(5), PELIGRO_INMEDIATO(4), NAVEGACION_URGENTE(3), NAVEGACION_NORMAL(2), CONTEXTO(1), QUIETO(0)
}

data class NavEvent(val message: String, val priority: EventPriority, val ts: Long = System.currentTimeMillis()) : Comparable<NavEvent> {
    override fun compareTo(other: NavEvent): Int = compareValuesBy(other, this, { it.priority.level }, { it.ts })
}

class TtsPriorityQueue(private val tts: TextToSpeech) {
    private val queue = PriorityQueue<NavEvent>()
    private var lastTime = 0L; private var lastPriority = EventPriority.QUIETO

    private val cooldowns = mapOf(
        EventPriority.CRITICO            to COOLDOWN_CRITICO,
        EventPriority.PELIGRO_INMEDIATO  to COOLDOWN_PELIGRO,
        EventPriority.NAVEGACION_URGENTE to COOLDOWN_NAVEGACION,
        EventPriority.NAVEGACION_NORMAL  to COOLDOWN_NAVEGACION,
        EventPriority.CONTEXTO           to COOLDOWN_ESCENA,
        EventPriority.QUIETO             to COOLDOWN_QUIETO
    )

    @Synchronized fun enqueue(event: NavEvent) {
        val now = System.currentTimeMillis()
        val cd  = cooldowns[event.priority] ?: 5_000L
        if (event.priority.level <= lastPriority.level && now - lastTime < cd) return
        queue.removeIf { it.priority.level < event.priority.level }
        queue.offer(event); flush()
    }

    @Synchronized fun flush() {
        val event = queue.peek() ?: return
        val interrupt = event.priority.level >= EventPriority.PELIGRO_INMEDIATO.level
        if (tts.isSpeaking && !interrupt) return
        queue.poll()
        val speed = when (event.priority) {
            EventPriority.CRITICO            -> 1.2f
            EventPriority.PELIGRO_INMEDIATO  -> 1.15f
            EventPriority.NAVEGACION_URGENTE -> 1.05f
            else -> 0.95f
        }
        tts.setSpeechRate(speed)
        tts.speak(event.message, TextToSpeech.QUEUE_FLUSH, null,
            "nav_${event.priority.name}_${System.currentTimeMillis()}")
        lastTime = System.currentTimeMillis(); lastPriority = event.priority
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// INFERENCIA DE ESCENA
// ─────────────────────────────────────────────────────────────────────────────
enum class SceneType {
    INTERIOR_DESPEJADO, INTERIOR_CONCURRIDO,
    EXTERIOR_TRANQUILO, EXTERIOR_CONCURRIDO,
    CRUCE_PELIGROSO, DESCONOCIDO
}

fun inferScene(labels: List<String>, areas: List<Float>): SceneType {
    if (labels.isEmpty()) return SceneType.DESCONOCIDO
    val indoor   = labels.count { it in INDOOR_OBJS }
    val outdoor  = labels.count { it in OUTDOOR_OBJS }
    val persons  = labels.count { it == "person" }
    val cross    = labels.count { it in CROSSING_HINTS }
    if (cross >= 2 && (labels.contains("traffic light") || labels.contains("stop sign")))
        return SceneType.CRUCE_PELIGROSO
    val isIndoor  = indoor  > outdoor || (indoor  > 0 && outdoor == 0)
    val isOutdoor = outdoor > indoor  || (outdoor > 0 && indoor  == 0)
    val crowded   = labels.size >= 5 || persons >= 3 || areas.sum() > 0.35f
    return when {
        isOutdoor && crowded  -> SceneType.EXTERIOR_CONCURRIDO
        isOutdoor && !crowded -> SceneType.EXTERIOR_TRANQUILO
        isIndoor  && crowded  -> SceneType.INTERIOR_CONCURRIDO
        isIndoor  && !crowded -> SceneType.INTERIOR_DESPEJADO
        crowded               -> SceneType.INTERIOR_CONCURRIDO
        else                  -> SceneType.DESCONOCIDO
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MOTOR DE NAVEGACIÓN — Guía como acompañante humano
//
// REGLAS implementadas:
//   1. SEGURIDAD primero — peligros interrumpen siempre
//   2. BAJA LATENCIA — para objetos peligrosos no se espera confirmación de frames
//   3. PREDICCIÓN de tendencia — acercándose sube prioridad, alejándose la baja
//   4. ZONAS — centro > laterales con movimiento hacia el usuario
//   5. DISTANCIA — <2m crítico | 3-4m desvío | 5-7m aviso | >7m contexto
//   6. SALIDA — máximo 2 frases, directas, accionables
//      Verbos: AVANZA / DETENTE / GIRA / ESQUIVA / PRECAUCIÓN
// ─────────────────────────────────────────────────────────────────────────────
object NavigationEngine {
    data class NavDecision(
        val instruction: String,
        val priority: EventPriority,
        val vibrateMs: Long = 0L,
        val requestScan: Boolean = false
    )

    // Objetos que NO esperan confirmación de frames si están cerca y vienen al centro
    // Son inherentemente peligrosos y necesitan reacción inmediata
    private val SIN_CONFIRMACION = setOf("car","truck","bus","motorcycle","bicycle","person","dog")

    fun decide(tracks: List<ObjectTrack>): NavDecision? {
        if (tracks.isEmpty()) return NavDecision("Camino libre. Avanza.", EventPriority.CONTEXTO)

        // Separar tracks confiables (con historia) de los de alta prioridad sin historia
        // Los objetos peligrosos se reportan desde el primer frame si están muy cerca
        val reliable = tracks.filter { t ->
            t.framesTracked >= 2 || (t.label in SIN_CONFIRMACION && t.depthScore >= DEPTH_PELIGRO)
        }
        if (reliable.isEmpty()) return null

        val center = reliable.filter { it.zone == "centro" }.maxByOrNull { it.depthScore }
        val left   = reliable.filter { it.zone == "izquierda" }
        val right  = reliable.filter { it.zone == "derecha" }
        val lClear = left.none  { it.depthScore >= DEPTH_CERCA }
        val rClear = right.none { it.depthScore >= DEPTH_CERCA }

        // ── REGLA 1: CRÍTICO — <2m, acercándose, en trayectoria central ──────
        // No espera confirmación. Interrumpe cualquier mensaje.
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

        // ── REGLA 2: PELIGRO INMEDIATO — 2-3m en centro ──────────────────────
        // Objeto muy cercano bloqueando el paso. Requiere acción inmediata.
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
                    // Bloqueado por ambos lados → pedir escaneo lateral
                    NavDecision("Detente. $obj bloqueando. Mueve el teléfono a los lados.",
                        EventPriority.PELIGRO_INMEDIATO, 800L, requestScan = true)
            }
        }

        // ── REGLA 3: VEHÍCULO LATERAL ACERCÁNDOSE ─────────────────────────────
        // Un vehículo que viene de lado puede ser más peligroso que algo estático adelante
        val vehiculoLateral = (left + right)
            .filter { it.label in VEHICLES && it.isApproaching && it.depthScore >= DEPTH_CERCA }
            .maxByOrNull { it.depthScore }

        if (vehiculoLateral != null) {
            val obj  = shortName(vehiculoLateral.label)
            val away = if (vehiculoLateral.zone == "izquierda") "derecha" else "izquierda"
            return NavDecision("¡Precaución! $obj por ${vehiculoLateral.zone}. Muévete a la $away.",
                EventPriority.PELIGRO_INMEDIATO, 600L)
        }

        // ── REGLA 4: DESVÍO — 3-4m en centro ─────────────────────────────────
        // Objeto cerca pero aún hay tiempo para esquivar suavemente
        if (center != null && center.depthScore >= DEPTH_CERCA) {
            val obj = shortName(center.label)
            val dir = if (lClear) "izquierda" else if (rClear) "derecha" else "un lado"
            val accion = if (center.isApproaching) "Esquiva" else "Desvíate"
            return NavDecision("$obj adelante. $accion hacia la $dir.",
                EventPriority.NAVEGACION_URGENTE, 300L)
        }

        // ── REGLA 5: AVISO ANTICIPADO — 5-7m ─────────────────────────────────
        // Objeto en zona de aviso. Si se acerca, subir prioridad.
        if (center != null && center.depthScore >= DEPTH_AVISO) {
            val obj = shortName(center.label)
            return if (center.isApproaching)
                NavDecision("Precaución. $obj al frente y acercándose. Prepárate para desviar.",
                    EventPriority.NAVEGACION_NORMAL)
            else
                NavDecision("Precaución. $obj al frente. Avanza con cuidado.",
                    EventPriority.NAVEGACION_NORMAL)
        }

        // ── REGLA 6: AMENAZA LATERAL — objeto acercándose desde un lado ──────
        val amenazaLateral = (left + right)
            .filter { it.isApproaching && it.depthScore >= DEPTH_CERCA && it.isConfirmed }
            .maxByOrNull { it.depthScore }

        if (amenazaLateral != null) {
            val obj  = shortName(amenazaLateral.label)
            val away = if (amenazaLateral.zone == "izquierda") "derecha" else "izquierda"
            return NavDecision("$obj por ${amenazaLateral.zone}. Desvíate a la $away.",
                EventPriority.NAVEGACION_URGENTE, 300L)
        }

        // ── REGLA 7: CONTEXTO LEJANO — objetos a >7m o estáticos en laterales ─
        // Solo para objetos de alta prioridad que vale la pena mencionar
        val lejano = reliable
            .filter { it.depthScore >= DEPTH_LEJANO && it.label in HIGH_PRIORITY_OBJS && !it.isApproaching }
            .maxByOrNull { it.depthScore }

        if (lejano != null) {
            val obj = shortName(lejano.label)
            val pos = if (lejano.zone == "centro") "al frente" else "a la ${lejano.zone}"
            return NavDecision("$obj $pos. Continúa con precaución.",
                EventPriority.CONTEXTO)
        }

        // ── CAMINO LIBRE ──────────────────────────────────────────────────────
        return NavDecision("Camino libre. Avanza.", EventPriority.CONTEXTO)
    }

    private fun shortName(label: String): String = LABEL_ES[label]?.short ?: label

    // Función auxiliar pública para que processFrame pueda consultar si hay
    // peligro activo (para suprimir mensajes de escena durante alertas)
    fun hayPeligroActivo(tracks: List<ObjectTrack>): Boolean =
        tracks.any { it.zone == "centro" && it.depthScore >= DEPTH_CERCA } ||
                tracks.any { it.isApproaching && it.depthScore >= DEPTH_PELIGRO }
}

// ─────────────────────────────────────────────────────────────────────────────
// DECISION ENGINE — Decide CUÁNDO, QUÉ y CUÁNTAS VECES hablar
//
// Resuelve:
//   • Spam de mensajes repetidos → filtro por evento anterior
//   • Múltiples instrucciones → solo 1 objeto principal por vez
//   • Distancia irreal → convertida a "pasos"
//   • Sin instrucción de evasión → incluida en el mensaje
//   • No repite si el usuario ya reaccionó → usa movimiento del acelerómetro
// ─────────────────────────────────────────────────────────────────────────────
class DecisionEngine {

    /** Evento de navegación que representa UNA instrucción concreta */
    data class NavigationEvent(
        val objectId: Int,
        val label: String,
        val zone: String,
        val dangerLevel: Int,
        val action: String,     // texto ya generado de la instrucción
        val timestamp: Long
    )

    private var lastEvent: NavigationEvent? = null
    private var lastSpeakTime: Long = 0L

    // ── Resultado que devuelve DecisionEngine.process() ───────────────────────
    data class SpeakDecision(
        val message: String,
        val priority: EventPriority,
        val vibrateMs: Long = 0L,
        val requestScan: Boolean = false
    )

    // ── Convierte profundidad ajustada a "pasos" (UX humana) ──────────────────
    private fun depthToSteps(depth: Float): Int = when {
        depth >= 0.75f -> 1
        depth >= 0.60f -> 2
        depth >= 0.45f -> 3
        depth >= 0.30f -> 5
        else           -> 7
    }

    // ── Instrucción de evasión según zona y disponibilidad de caminos ─────────
    private fun getAvoidance(zone: String, lClear: Boolean, rClear: Boolean): String = when {
        zone == "izquierda"    -> "muévete a la derecha"
        zone == "derecha"      -> "muévete a la izquierda"
        lClear && !rClear      -> "gira a la izquierda"
        rClear && !lClear      -> "gira a la derecha"
        lClear                 -> "gira a la izquierda"
        rClear                 -> "gira a la derecha"
        else                   -> "detente"
    }

    // ── ¿Debo hablar ahora? — compara con el evento anterior ─────────────────
    private fun shouldSpeak(newEvent: NavigationEvent): Boolean {
        val last = lastEvent ?: return true
        // Mismo objeto + misma zona + misma acción + nivel de peligro sin cambio → NO repetir
        if (last.objectId == newEvent.objectId &&
            last.zone == newEvent.zone &&
            last.action == newEvent.action &&
            kotlin.math.abs(newEvent.dangerLevel - last.dangerLevel) < 1
        ) return false
        return true
    }

    /**
     * Función principal. Recibe los tracks activos y decide si hablar,
     * qué decir y con qué prioridad.
     *
     * @param tracks       Lista de objetos rastreados actualmente
     * @param lastMotionTime Timestamp del último movimiento del usuario (acelerómetro)
     * @param now          Timestamp actual
     */
    fun process(
        tracks: List<ObjectTrack>,
        lastMotionTime: Long,
        now: Long
    ): SpeakDecision? {

        // ── Sin tracks confiables → nada que decir ────────────────────────────
        val confirmed = tracks.filter { it.framesTracked >= 2 ||
                (it.label in setOf("car","truck","bus","motorcycle","bicycle","person","dog")
                        && it.depthScore >= DEPTH_PELIGRO) }
        if (confirmed.isEmpty()) return null

        // ── FILTRO DE OBJETOS SEGUROS ─────────────────────────────────────────
        // Los objetos de interior (sofás, mesas, TV...) nunca activan alarma de evasión.
        // Solo se convierten en "obstáculo genérico" si están EXTREMADAMENTE cerca (>= DEPTH_PELIGRO).
        val actionableTracks = confirmed.filter { it.label !in SAFE_OBJECTS }
        val safeTracks       = confirmed.filter { it.label in SAFE_OBJECTS }

        // Objetos seguros que están físicamente muy cerca → obstáculo genérico sin nombre alarmante
        val safeBlockers = safeTracks.filter { it.depthScore >= DEPTH_PELIGRO }

        // Lista final para tomar decisiones: objetos peligrosos + bloqueadores genéricos
        val tracksParaDecision = when {
            actionableTracks.isNotEmpty() -> actionableTracks
            safeBlockers.isNotEmpty()     ->
                // Tratar como obstáculo genérico de nivel 2 — no gritar "sofá peligroso"
                safeBlockers.map { it.copy(label = "obstacle") }
            else -> return null  // solo hay muebles lejos → silencio
        }

        // ── Calcular qué lados están libres ───────────────────────────────────
        val leftTracks  = tracksParaDecision.filter { it.zone == "izquierda" }
        val rightTracks = tracksParaDecision.filter { it.zone == "derecha"   }
        val lClear = leftTracks.none  { it.depthScore >= DEPTH_CERCA }
        val rClear = rightTracks.none { it.depthScore >= DEPTH_CERCA }

        // ── Seleccionar 1 SOLO objeto principal ───────────────────────────────
        // Prioridad: mayor dangerLevel → si empatan, mayor depthScore (más cercano)
        val mainTrack = tracksParaDecision
            .sortedWith(compareByDescending<ObjectTrack> { it.dangerLevel }
                .thenByDescending { it.depthScore })
            .firstOrNull() ?: return null

        // ── ANÁLISIS DEL PASILLO COMPLETO ─────────────────────────────────────
        // La instrucción describe por dónde CAMINAR, no solo qué objeto hay
        val centerClear = mainTrack.zone != "centro" || mainTrack.depthScore < DEPTH_CERCA

        val instruccionPasillo = when {
            // Centro bloqueado → indicar por dónde desviar
            !centerClear -> when {
                lClear && rClear  -> "Desvíate a la izquierda o derecha."
                lClear && !rClear -> "Gira a la izquierda."
                rClear && !lClear -> "Gira a la derecha."
                else              -> "Detente. Busca otro camino."  // activará escaneo
            }
            // Centro libre pero laterales ocupados → informar y seguir por centro
            else -> {
                val advertencias = mutableListOf<String>()
                if (!lClear) advertencias.add("objeto a la izquierda")
                if (!rClear) advertencias.add("objeto a la derecha")
                if (advertencias.isNotEmpty())
                    "${advertencias.joinToString(", ")}. Sigue por el centro."
                else
                    "Camino libre. Avanza."
            }
        }

        val label     = if (mainTrack.label == "obstacle") "obstáculo"
        else LABEL_ES[mainTrack.label]?.short ?: mainTrack.label
        val zone      = mainTrack.zone
        val danger    = mainTrack.dangerLevel
        val steps     = depthToSteps(mainTrack.depthScore)

        // ── Construir mensaje según nivel de peligro ──────────────────────────
        val requestScan = !centerClear && !lClear && !rClear

        val (msg, priority, vibrateMs) = when {
            danger >= 4 -> Triple(
                "¡Detente! $label muy cerca. ${if(!centerClear) instruccionPasillo else ""}".trim(),
                EventPriority.CRITICO, 1000L
            )
            danger >= 3 -> Triple(
                if (!centerClear)
                    "Detente. $label al $zone a $steps paso${if(steps>1)"s" else ""}. $instruccionPasillo"
                else
                    instruccionPasillo,
                EventPriority.PELIGRO_INMEDIATO, 700L
            )
            danger >= 2 -> Triple(
                if (!centerClear)
                    "$label al $zone, a $steps pasos. $instruccionPasillo"
                else
                    instruccionPasillo,
                EventPriority.NAVEGACION_URGENTE, 300L
            )
            danger >= 1 -> Triple(
                instruccionPasillo,
                EventPriority.NAVEGACION_NORMAL, 0L
            )
            else -> Triple(
                instruccionPasillo,
                EventPriority.CONTEXTO, 0L
            )
        }

        // ── Construir evento para comparar con el anterior ────────────────────
        val newEvent = NavigationEvent(
            objectId   = mainTrack.id,
            label      = label,
            zone       = zone,
            dangerLevel = danger,
            action     = instruccionPasillo,
            timestamp  = now
        )

        // ── Decidir si hablar ─────────────────────────────────────────────────
        val userMoved     = (now - lastMotionTime) < 3_000L
        val isHighDanger  = danger >= 3
        val timeSinceLast = now - lastSpeakTime

        val speak = when {
            // Peligro crítico/inmediato → siempre interrumpe
            isHighDanger && timeSinceLast > COOLDOWN_PELIGRO -> true
            // Evento nuevo (objeto o instrucción cambió) → hablar
            shouldSpeak(newEvent) -> true
            // El usuario no se movió en X segundos → repetir la instrucción
            !userMoved && timeSinceLast > REPEAT_IF_NO_MOVE_MS -> true
            // Mismo evento, usuario respondió o cooldown no expiró → silencio
            else -> false
        }

        if (!speak) return null

        // ── Guardar estado y retornar ─────────────────────────────────────────
        lastEvent     = newEvent
        lastSpeakTime = now

        return SpeakDecision(msg, priority, vibrateMs, requestScan)
    }

    fun reset() {
        lastEvent = null
        lastSpeakTime = 0L
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MAIN ACTIVITY
// ─────────────────────────────────────────────────────────────────────────────
class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: DetectionOverlay
    private lateinit var scanModeLabel: TextView
    private lateinit var tts: TextToSpeech
    @Volatile private var ttsReady = false
    private lateinit var ttsQueue: TtsPriorityQueue

    private var camera: Camera? = null
    private lateinit var vibrator: Vibrator

    private lateinit var yoloDetector: YoloDetector
    private var depthEstimator: DepthEstimator? = null
    private val depthAvailable = AtomicBoolean(false)
    private val trackManager   = TrackManager()

    // Flash
    private val brightHistory = ArrayDeque<Int>()
    private var isTorchOn = false; private var lastTorchChg = 0L

    // Sensores
    private lateinit var sensorMgr: SensorManager
    private var accel:  Sensor? = null
    private var gyro:   Sensor? = null
    @Volatile private var lastMotionTime = System.currentTimeMillis()

    // Estado de escaneo con giroscopio
    private var scanModeActive    = false
    private var scanStartTime     = 0L
    private var scanStartAngle    = 0f   // ángulo inicial al pedir escaneo
    private var scanMaxAngle      = 0f   // máximo ángulo barrido
    private var lastScanRequest   = 0L
    private var gyroAngleZ        = 0f   // ángulo acumulado del giroscopio

    // Timestamps
    private var lastSpeakTime   = 0L
    private var lastStillTime   = 0L
    private var lastSceneTime   = 0L
    private var lastCrossTime   = 0L

    // Decision Engine — cerebro de navegación
    private val decisionEngine = DecisionEngine()

    // Descripción inicial del entorno
    private var entornoDescrito = false
    private var framesParaEntorno = 0

    // Pipeline
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val depthExecutor  = Executors.newSingleThreadExecutor()
    @Volatile private var latestDepth: Array<FloatArray>? = null
    @Volatile private var depthTs: Long = 0L

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView  = findViewById(R.id.viewFinder)
        overlay      = findViewById(R.id.detectionOverlay)
        scanModeLabel = findViewById(R.id.scanModeLabel)

        @Suppress("DEPRECATION")
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        sensorMgr = getSystemService(SENSOR_SERVICE) as SensorManager
        accel = sensorMgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyro  = sensorMgr.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        initModels()
        initTts()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
    }

    override fun onResume() {
        super.onResume()
        accel?.let { sensorMgr.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        gyro?.let  { sensorMgr.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }
    override fun onPause() { super.onPause(); sensorMgr.unregisterListener(this) }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val mag = sqrt(event.values[0].pow(2) + event.values[1].pow(2) + event.values[2].pow(2))
                if (abs(mag - SensorManager.GRAVITY_EARTH) > 0.8f)
                    lastMotionTime = System.currentTimeMillis()
            }
            Sensor.TYPE_GYROSCOPE -> {
                // Acumular rotación en Z (yaw = girar el teléfono horizontalmente)
                val dt = 0.02f  // ~50Hz SENSOR_DELAY_GAME
                gyroAngleZ += Math.toDegrees(event.values[2].toDouble()).toFloat() * dt

                if (scanModeActive) {
                    val angleMoved = abs(gyroAngleZ - scanStartAngle)
                    if (angleMoved > scanMaxAngle) scanMaxAngle = angleMoved

                    // Completó el escaneo — giró suficiente
                    if (scanMaxAngle >= SCAN_ROTATION_DEG) {
                        completeScan()
                    }
                    // Timeout del escaneo
                    if (System.currentTimeMillis() - scanStartTime > SCAN_TIMEOUT_MS) {
                        cancelScan()
                    }
                }
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ── Escaneo ───────────────────────────────────────────────────────────────

    private fun startScanMode(direction: String = "ambos lados") {
        if (System.currentTimeMillis() - lastScanRequest < SCAN_COOLDOWN) return
        scanModeActive = true
        scanStartTime  = System.currentTimeMillis()
        scanStartAngle = gyroAngleZ
        scanMaxAngle   = 0f
        lastScanRequest = System.currentTimeMillis()

        runOnUiThread { scanModeLabel.visibility = View.VISIBLE }
        speak("Mueve el teléfono hacia $direction para ver mejor.", EventPriority.NAVEGACION_NORMAL)
    }

    private fun completeScan() {
        scanModeActive = false
        runOnUiThread { scanModeLabel.visibility = View.GONE }
        // El siguiente frame de detección ya mostrará lo que vio al girar
    }

    private fun cancelScan() {
        scanModeActive = false
        runOnUiThread { scanModeLabel.visibility = View.GONE }
    }

    // ── Inicialización ────────────────────────────────────────────────────────

    private fun initModels() {
        yoloDetector = YoloDetector(MODELO_YOLO, this)
        depthExecutor.execute {
            try { depthEstimator = DepthEstimator(this); depthAvailable.set(true) }
            catch (e: Exception) { Log.w(TAG, "Depth no disp: ${e.message}") }
        }
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts.setLanguage(java.util.Locale("es", "MX"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
                    tts.setLanguage(java.util.Locale("es"))
                ttsReady = true
                ttsQueue  = TtsPriorityQueue(tts)
                speak("Iniciando sistema. Analizando entorno...", EventPriority.CONTEXTO)
            } else Log.e(TAG, "TTS falló: $status")
        }
    }

    // ── CameraX ───────────────────────────────────────────────────────────────

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview  = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                val rotation = imageProxy.imageInfo.rotationDegrees
                val bitmap   = imageProxy.toBitmap(); imageProxy.close()
                val rotated  = rotateBitmap(bitmap, rotation)
                val now      = System.currentTimeMillis()

                controlTorch(rotated)

                if (depthAvailable.get()) depthExecutor.execute {
                    latestDepth = depthEstimator?.estimate(rotated)
                    depthTs     = System.currentTimeMillis()
                }

                val detections = yoloDetector.detect(rotated)
                val depthMap   = if (now - depthTs < 250L) latestDepth else null

                // Actualizar overlay visual (para demo en clase)
                val overlayData = detections.map { d ->
                    d.box to Pair(d.label, d.score)
                }
                overlay.update(overlayData, rotated.width, rotated.height)

                processFrame(detections, depthMap, now)
            }
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
        }, ContextCompat.getMainExecutor(this))
    }

    // ── Lógica principal ──────────────────────────────────────────────────────

    private fun processFrame(detections: List<Detection>, depthMap: Array<FloatArray>?, now: Long) {

        // Quietud
        if (now - lastMotionTime > STILLNESS_MS) {
            if (!tts.isSpeaking && now - lastStillTime > COOLDOWN_QUIETO
                && now - lastSpeakTime > COOLDOWN_POST_SPEAK) {
                speak("En pausa. Muévete para continuar.", EventPriority.QUIETO)
                lastStillTime = now
            }
            return
        }

        val tracks = trackManager.update(detections, depthMap, now)
        val labels = tracks.map { it.label }

        // ── DESCRIPCIÓN INICIAL DEL ENTORNO ───────────────────────────────────
        // Espera 5 frames para tener detecciones estables, luego describe el entorno
        if (!entornoDescrito) {
            framesParaEntorno++
            if (framesParaEntorno >= 5 && labels.isNotEmpty()) {
                describirEntornoInicial(labels, tracks)
                entornoDescrito = true
            }
            return  // No navegar hasta describir el entorno
        }

        checkCrossing(labels, now)

        // ── DECISION ENGINE — única fuente de verdad para hablar ─────────────
        // Selecciona 1 objeto principal, construye mensaje con pasos + evasión,
        // filtra repeticiones y repite solo si el usuario no reaccionó.
        val speakDecision = decisionEngine.process(tracks, lastMotionTime, now)

        if (speakDecision != null) {
            speak(speakDecision.message, speakDecision.priority)
            if (speakDecision.vibrateMs > 0) vibrate(speakDecision.vibrateMs)
            if (speakDecision.requestScan) startScanMode()
            lastSpeakTime = now
        }

        // Descripción periódica del entorno — SOLO si no hay peligro activo y
        // el Decision Engine está en silencio
        val peligroActivo = NavigationEngine.hayPeligroActivo(tracks)
        if (!peligroActivo && speakDecision == null
            && !tts.isSpeaking && now - lastSceneTime > COOLDOWN_ESCENA) {
            val areas = tracks.map { it.box.width() * it.box.height() }
            val scene = inferScene(labels, areas)
            val msg   = buildSceneMessage(scene, labels)
            if (msg != null) { speak(msg, EventPriority.CONTEXTO); lastSceneTime = now }
        }
    }

    /**
     * Primera descripción del entorno al arrancar.
     * Responde: ¿Dónde estoy? ¿Hay espacio? ¿Qué hay cerca?
     */
    private fun describirEntornoInicial(labels: List<String>, tracks: List<ObjectTrack>) {
        val areas = tracks.map { it.box.width() * it.box.height() }
        val scene = inferScene(labels, areas)
        val veh   = labels.count { it in VEHICLES }
        val per   = labels.count { it == "person" }
        val totalObjs = labels.size

        val entorno = when (scene) {
            SceneType.INTERIOR_DESPEJADO  -> "Pareces estar en un lugar cerrado con espacio disponible."
            SceneType.INTERIOR_CONCURRIDO ->
                if (per >= 3) "Estás en un lugar cerrado con $per personas cerca."
                else "Estás en un espacio interior con varios objetos."
            SceneType.EXTERIOR_TRANQUILO  ->
                if (veh > 0) "Estás en exteriores. Hay $veh vehículo${if(veh>1)"s" else ""} en la zona."
                else "Estás en exteriores con espacio abierto."
            SceneType.EXTERIOR_CONCURRIDO -> "Estás en exteriores con mucha actividad alrededor."
            SceneType.CRUCE_PELIGROSO     -> "Detecté una intersección o cruce. Precaución extrema."
            SceneType.DESCONOCIDO         ->
                if (totalObjs == 0) "No detecto objetos cercanos. El camino parece libre."
                else "Analizando el entorno. Detecto $totalObjs objeto${if(totalObjs>1)"s" else ""}."
        }

        // Agregar objetos más cercanos
        val masUrgente = tracks.filter { it.depthScore >= DEPTH_AVISO }
            .maxByOrNull { it.depthScore }
        val sufijo = if (masUrgente != null) {
            val obj = LABEL_ES[masUrgente.label]?.short ?: masUrgente.label
            " Hay ${LABEL_ES[masUrgente.label]?.let { "${it.art} $obj" } ?: obj} al ${masUrgente.zone}."
        } else ""

        speak("$entorno$sufijo", EventPriority.CONTEXTO)
        lastSceneTime = System.currentTimeMillis()
    }

    private fun buildSceneMessage(scene: SceneType, labels: List<String>): String? {
        val veh = labels.count { it in VEHICLES }
        val per = labels.count { it == "person" }
        return when (scene) {
            SceneType.INTERIOR_DESPEJADO  -> "Interior despejado."
            SceneType.INTERIOR_CONCURRIDO -> if (per >= 3) "Lugar con mucha gente." else "Espacio con objetos."
            SceneType.EXTERIOR_TRANQUILO  -> if (veh > 0) "Exterior, $veh vehículo${if(veh>1)"s" else ""} cerca." else "Exterior abierto."
            SceneType.EXTERIOR_CONCURRIDO -> "Exterior concurrido. Mantente alerta."
            SceneType.CRUCE_PELIGROSO     -> "Zona de cruce. Precaución."
            SceneType.DESCONOCIDO         -> null
        }
    }

    private fun checkCrossing(labels: List<String>, now: Long) {
        if (now - lastCrossTime < COOLDOWN_CRUCE) return
        if ((labels.contains("traffic light") || labels.contains("stop sign"))
            && labels.count { it in CROSSING_HINTS } >= 2) {
            speak("Cruce detectado. Detente y escanea los lados.", EventPriority.NAVEGACION_URGENTE)
            vibrate(500L)
            startScanMode("la izquierda y luego la derecha")
            lastCrossTime = now
        }
    }

    // ── Vibración ─────────────────────────────────────────────────────────────
    private fun vibrate(ms: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") vibrator.vibrate(ms)
    }

    // ── Flash ─────────────────────────────────────────────────────────────────
    private fun controlTorch(bitmap: Bitmap) {
        brightHistory.addLast(calcBrightness(bitmap))
        if (brightHistory.size > BRIGHT_SAMPLES) brightHistory.removeFirst()
        if (brightHistory.size < BRIGHT_SAMPLES) return
        val avg = brightHistory.average().toInt()
        val now = System.currentTimeMillis()
        if (now - lastTorchChg < TORCH_DEBOUNCE) return
        when {
            avg < DARK_THRESHOLD  && !isTorchOn -> { camera?.cameraControl?.enableTorch(true);  isTorchOn = true;  lastTorchChg = now; brightHistory.clear() }
            avg > TORCH_OFF_THRESH && isTorchOn -> { camera?.cameraControl?.enableTorch(false); isTorchOn = false; lastTorchChg = now; brightHistory.clear() }
        }
    }

    private fun calcBrightness(bitmap: Bitmap): Int {
        var total = 0L; var count = 0
        for (x in 0 until bitmap.width step 20) for (y in 0 until bitmap.height step 20) {
            val p = bitmap.getPixel(x, y)
            total += (0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)).toLong()
            count++
        }
        return if (count > 0) (total / count).toInt() else 128
    }

    private fun rotateBitmap(bmp: Bitmap, deg: Int): Bitmap {
        if (deg == 0) return bmp
        val m = Matrix(); m.postRotate(deg.toFloat())
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun speak(text: String, priority: EventPriority = EventPriority.NAVEGACION_NORMAL) {
        if (!ttsReady) return
        ttsQueue.enqueue(NavEvent(text, priority))
        lastSpeakTime = System.currentTimeMillis()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            startCamera() else finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::tts.isInitialized) tts.shutdown()
        yoloDetector.close(); depthEstimator?.close()
        cameraExecutor.shutdown(); depthExecutor.shutdown()
        trackManager.clear()
    }
}