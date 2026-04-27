package com.example.myapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.tensorflow.lite.Interpreter
//import org.tensorflow.lite.gpu.GpuDelegate
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
internal const val SCORE_MINIMO    = 0.45f
internal const val NMS_IOU_THRESH  = 0.45f
internal const val MAX_DETECCIONES = 10

internal const val ZONA_IZQ = 0.30f
internal const val ZONA_DER = 0.70f

// Umbrales de profundidad — ajustados tras pruebas reales
// Si detectas falsos positivos baja DEPTH_PELIGRO a 0.70
// Si no detecta obstáculos cercanos súbelo a 0.80
internal const val DEPTH_PELIGRO = 0.75f
internal const val DEPTH_CERCA   = 0.55f
internal const val DEPTH_AVISO   = 0.38f

// Tracking
internal const val IOU_MIN_MATCH      = 0.30f
internal const val MAX_FRAMES_PERDIDO = 5
internal const val KALMAN_SMOOTH      = 0.6f
internal const val MIN_VELOCITY_WARN  = 0.015f
internal const val COLLISION_FRAMES   = 8

// Flash
internal const val DARK_THRESHOLD   = 55
internal const val TORCH_OFF_THRESH = 115
internal const val TORCH_DEBOUNCE   = 5_000L
internal const val BRIGHT_SAMPLES   = 8

// ── COOLDOWNS AJUSTADOS PARA INVIDENTE ────────────────────────────────────────
// Más cortos en peligro, más largos en contexto para no saturar
internal const val COOLDOWN_PELIGRO    = 2_500L  // alertas urgentes: cada 2.5s
internal const val COOLDOWN_NAVEGACION = 4_000L  // desvíos: cada 4s
internal const val COOLDOWN_LIBRE      = 20_000L // "camino libre": cada 20s (era 14s)
internal const val COOLDOWN_QUIETO     = 25_000L // quietud: cada 25s
internal const val COOLDOWN_POST_SPEAK = 6_000L
internal const val COOLDOWN_ESCENA     = 45_000L // escena: cada 45s (era 30s)
internal const val COOLDOWN_CRUCE      = 10_000L
internal const val STILLNESS_MS        = 7_000L

// ── FILTRO ANTI-SPAM ──────────────────────────────────────────────────────────
// Solo hablar si el nivel de peligro cambió O si pasó el cooldown
internal const val MIN_FRAMES_CONFIRMACION = 3  // frames consecutivos antes de hablar

// ─────────────────────────────────────────────────────────────────────────────
// ETIQUETAS COCO — 80 clases YOLOv8n
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
    "sink","refrigerator","potted plant","clock","cup","bottle","cell phone")
internal val OUTDOOR_OBJS   = setOf("car","truck","bus","motorcycle","bicycle",
    "traffic light","stop sign","fire hydrant","bench","train","boat")
internal val CROSSING_HINTS = setOf("traffic light","stop sign","car","truck","bus","bicycle")

// ── MENSAJES CORTOS PARA INVIDENTE ────────────────────────────────────────────
// Máximo 5-6 palabras por mensaje de peligro — el TTS debe terminar rápido
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
    "stop sign"     to LabelEs("una","señal de alto","señal de alto"),
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
    "stairs"        to LabelEs("unas","escaleras","escaleras")
)

// ─────────────────────────────────────────────────────────────────────────────
// DETECCIÓN — YOLOv8n TFLite
// ─────────────────────────────────────────────────────────────────────────────
data class Detection(val box: RectF, val label: String, val score: Float)

class YoloDetector(modelPath: String, context: Context) {
    private var interpreter: Interpreter? = null

    init {
        try {
            val fd     = context.assets.openFd(modelPath)
            val buffer = FileInputStream(fd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            val options = Interpreter.Options().apply {
                numThreads = 4
                Log.d(TAG, "YOLO: CPU x4 hilos")
            }
            interpreter = Interpreter(buffer, options)
            Log.d(TAG, "YOLOv8n OK")
        } catch (e: Exception) { Log.e(TAG, "Error YOLO: ${e.message}") }
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val interp = interpreter ?: return emptyList()
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

        val numAnchors = 2100  // para imgsz=320: 2100 anchors
        val outputBuf  = Array(1) { Array(84) { FloatArray(numAnchors) } }
        try { interp.run(inputBuf, outputBuf) }
        catch (e: Exception) { Log.e(TAG, "YOLO inf: ${e.message}"); return emptyList() }

        val raw = outputBuf[0]
        data class Raw(val box: RectF, val cls: Int, val score: Float)
        val raws = mutableListOf<Raw>()

        for (a in 0 until numAnchors) {
            var bestCls = 0; var bestScore = 0f
            for (c in 0 until 80) { val s = raw[4+c][a]; if (s > bestScore) { bestScore=s; bestCls=c } }
            if (bestScore < SCORE_MINIMO) continue
            val cx=raw[0][a]; val cy=raw[1][a]; val w=raw[2][a]; val h=raw[3][a]
            val box = RectF(
                (cx-w/2f).coerceIn(0f,1f),(cy-h/2f).coerceIn(0f,1f),
                (cx+w/2f).coerceIn(0f,1f),(cy+h/2f).coerceIn(0f,1f)
            )
            if (box.width()>0f && box.height()>0f) raws.add(Raw(box, bestCls, bestScore))
        }

        raws.sortByDescending { it.score }
        val kept = BooleanArray(raws.size) { true }
        for (i in raws.indices) { if (!kept[i]) continue
            for (j in i+1 until raws.size) { if (!kept[j]) continue
                if (raws[i].cls==raws[j].cls && iou(raws[i].box,raws[j].box)>NMS_IOU_THRESH) kept[j]=false
            }
        }
        return raws.indices.filter { kept[it] }.take(MAX_DETECCIONES)
            .map { Detection(raws[it].box, COCO_LABELS.getOrElse(raws[it].cls){"objeto"}, raws[it].score) }
    }

    private fun iou(a: RectF, b: RectF): Float {
        val il=maxOf(a.left,b.left); val it=maxOf(a.top,b.top)
        val ir=minOf(a.right,b.right); val ib=minOf(a.bottom,b.bottom)
        if (ir<=il||ib<=it) return 0f
        val inter=(ir-il)*(ib-it)
        return inter/(a.width()*a.height()+b.width()*b.height()-inter)
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
    // ── NUEVO: contador de frames confirmando mismo nivel ──────────────────
    var dangerLevel: Int = 0,
    var dangerFrames: Int = 0  // cuántos frames consecutivos en este nivel
) {
    fun update(newBox: RectF, newDepth: Float, now: Long) {
        val newCx=newBox.centerX(); val newCy=newBox.centerY()
        vx     = KALMAN_SMOOTH*vx     + (1f-KALMAN_SMOOTH)*(newCx-cx)
        vy     = KALMAN_SMOOTH*vy     + (1f-KALMAN_SMOOTH)*(newCy-cy)
        vDepth = KALMAN_SMOOTH*vDepth + (1f-KALMAN_SMOOTH)*(newDepth-depthScore)
        box=newBox; cx=newCx; cy=newCy; depthScore=newDepth
        framesLost=0; framesTracked++; lastSeen=now

        // Actualizar nivel de peligro y contador de confirmación
        val newLevel = when {
            newDepth >= DEPTH_PELIGRO -> 3
            newDepth >= DEPTH_CERCA   -> 2
            newDepth >= DEPTH_AVISO   -> 1
            else                      -> 0
        }
        if (newLevel == dangerLevel) dangerFrames++
        else { dangerLevel = newLevel; dangerFrames = 1 }
    }

    fun predict(frames: Int): Pair<Float,Float> =
        Pair((depthScore+vDepth*frames).coerceIn(0f,1f),(cx+vx*frames).coerceIn(0f,1f))

    val isApproaching: Boolean get() = vDepth >  MIN_VELOCITY_WARN
    val isReceding:    Boolean get() = vDepth < -MIN_VELOCITY_WARN
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
        if (detections.isEmpty()) { tracks.forEach { it.framesLost++ }; return tracks.filter { it.framesLost==0 } }

        data class Match(val ti: Int, val di: Int, val iou: Float)
        val candidates = mutableListOf<Match>()
        for ((ti,t) in tracks.withIndex()) for ((di,d) in detections.withIndex()) {
            if (d.label!=t.label) continue
            val v=iouBoxes(t.box,d.box); if (v>=IOU_MIN_MATCH) candidates.add(Match(ti,di,v))
        }
        candidates.sortByDescending { it.iou }
        val matched=BooleanArray(detections.size); val usedTrks=mutableSetOf<Int>()
        for (m in candidates) {
            if (m.ti in usedTrks||matched[m.di]) continue
            val depth=depthMap?.let { sampleDepth(it,detections[m.di].box) } ?: fallback(detections[m.di].box)
            tracks[m.ti].update(detections[m.di].box,depth,now)
            tracks[m.ti].score=detections[m.di].score
            matched[m.di]=true; usedTrks.add(m.ti)
        }
        for ((di,det) in detections.withIndex()) {
            if (matched[di]) continue
            val depth=depthMap?.let { sampleDepth(it,det.box) } ?: fallback(det.box)
            tracks.add(ObjectTrack(id=nextId++,label=det.label,box=det.box,depthScore=depth,score=det.score,lastSeen=now))
        }
        for ((ti,t) in tracks.withIndex()) { if (ti !in usedTrks) t.framesLost++ }
        return tracks.filter { it.framesLost==0 }
    }

    private fun iouBoxes(a: RectF, b: RectF): Float {
        val il=maxOf(a.left,b.left); val it2=maxOf(a.top,b.top)
        val ir=minOf(a.right,b.right); val ib=minOf(a.bottom,b.bottom)
        if (ir<=il||ib<=it2) return 0f
        val inter=(ir-il)*(ib-it2)
        return inter/(a.width()*a.height()+b.width()*b.height()-inter)
    }
    private fun sampleDepth(map: Array<FloatArray>, box: RectF): Float {
        val mh=map.size; val mw=map[0].size
        val cx=box.centerX(); val cy=box.centerY()
        val hw=box.width()*0.3f; val hh=box.height()*0.3f
        val x0=((cx-hw)*mw).toInt().coerceIn(0,mw-1); val x1=((cx+hw)*mw).toInt().coerceIn(0,mw-1)
        val y0=((cy-hh)*mh).toInt().coerceIn(0,mh-1); val y1=((cy+hh)*mh).toInt().coerceIn(0,mh-1)
        var sum=0f; var count=0
        for (y in y0..y1 step 2) for (x in x0..x1 step 2) { sum+=map[y][x]; count++ }
        return if (count>0) sum/count else 0f
    }
    private fun fallback(box: RectF): Float {
        val area=box.width()*box.height()
        return when { area>=0.20f->0.85f; area>=0.08f->0.65f; area>=0.03f->0.45f; else->0.20f }
    }
    fun clear() = tracks.clear()
}

// ─────────────────────────────────────────────────────────────────────────────
// DEPTH ESTIMATOR — MiDaS
// ─────────────────────────────────────────────────────────────────────────────
class DepthEstimator(context: Context) {
    private val SZ = 256
    private var interpreter: Interpreter? = null
    init {
        try {
            val fd=context.assets.openFd(MODELO_DEPTH)
            val buf=FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY,fd.startOffset,fd.declaredLength)
            val opts = Interpreter.Options().apply { numThreads = 2 }
            interpreter=Interpreter(buf,opts); Log.d(TAG,"MiDaS OK")
        } catch(e:Exception){ Log.w(TAG,"MiDaS no disponible: ${e.message}") }
    }
    fun estimate(bitmap: Bitmap): Array<FloatArray>? {
        val interp=interpreter ?: return null
        val scaled=Bitmap.createScaledBitmap(bitmap,SZ,SZ,true)
        val inputBuf=ByteBuffer.allocateDirect(4*SZ*SZ*3).order(ByteOrder.nativeOrder())
        for (y in 0 until SZ) for (x in 0 until SZ) {
            val px=scaled.getPixel(x,y)
            inputBuf.putFloat(((px shr 16) and 0xFF)/255f)
            inputBuf.putFloat(((px shr  8) and 0xFF)/255f)
            inputBuf.putFloat(( px         and 0xFF)/255f)
        }
        inputBuf.rewind()
        val out=Array(1){Array(SZ){FloatArray(SZ)}}
        return try {
            interp.runForMultipleInputsOutputs(arrayOf(inputBuf),mapOf(0 to out))
            val raw=out[0]; var mn=Float.MAX_VALUE; var mx=-Float.MAX_VALUE
            for (row in raw) for (v in row) { if(v<mn)mn=v; if(v>mx)mx=v }
            val range=(mx-mn).coerceAtLeast(1e-6f)
            Array(SZ){y->FloatArray(SZ){x->(raw[y][x]-mn)/range}}
        } catch(e:Exception){ Log.e(TAG,"MiDaS err: ${e.message}"); null }
    }
    fun close()=interpreter?.close()
}

// ─────────────────────────────────────────────────────────────────────────────
// TTS CON PRIORIDAD + VELOCIDAD DE VOZ AJUSTADA
// ─────────────────────────────────────────────────────────────────────────────
enum class EventPriority(val level: Int) {
    PELIGRO_INMEDIATO(4), NAVEGACION_URGENTE(3), NAVEGACION_NORMAL(2), CONTEXTO(1), QUIETO(0)
}

data class NavEvent(val message: String, val priority: EventPriority, val ts: Long=System.currentTimeMillis()) : Comparable<NavEvent> {
    override fun compareTo(other: NavEvent): Int = compareValuesBy(other,this,{it.priority.level},{it.ts})
}

class TtsPriorityQueue(private val tts: TextToSpeech) {
    private val queue=PriorityQueue<NavEvent>()
    private var lastTime=0L; private var lastPriority=EventPriority.QUIETO

    private val cooldowns=mapOf(
        EventPriority.PELIGRO_INMEDIATO  to COOLDOWN_PELIGRO,
        EventPriority.NAVEGACION_URGENTE to COOLDOWN_NAVEGACION,
        EventPriority.NAVEGACION_NORMAL  to COOLDOWN_NAVEGACION,
        EventPriority.CONTEXTO           to COOLDOWN_ESCENA,
        EventPriority.QUIETO             to COOLDOWN_QUIETO
    )

    @Synchronized fun enqueue(event: NavEvent) {
        val now=System.currentTimeMillis()
        val cd=cooldowns[event.priority] ?: 5_000L
        if (event.priority.level<=lastPriority.level && now-lastTime<cd) return
        queue.removeIf { it.priority.level<event.priority.level }
        queue.offer(event); flush()
    }

    @Synchronized fun flush() {
        val event=queue.peek() ?: return
        val interrupt=event.priority==EventPriority.PELIGRO_INMEDIATO
        if (tts.isSpeaking && !interrupt) return
        queue.poll()
        // Velocidad de habla: más rápido en peligro (1.15x), normal en contexto (0.95x)
        val speed = when(event.priority) {
            EventPriority.PELIGRO_INMEDIATO  -> 1.15f
            EventPriority.NAVEGACION_URGENTE -> 1.05f
            else                             -> 0.95f
        }
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
        tts.setSpeechRate(speed)
        tts.speak(event.message, TextToSpeech.QUEUE_FLUSH, params, "nav_${event.priority.name}_${System.currentTimeMillis()}")
        lastTime=System.currentTimeMillis(); lastPriority=event.priority
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// INFERENCIA DE ESCENA
// ─────────────────────────────────────────────────────────────────────────────
enum class SceneType { INTERIOR_DESPEJADO, INTERIOR_CONCURRIDO, EXTERIOR_TRANQUILO, EXTERIOR_CONCURRIDO, CRUCE_PELIGROSO, DESCONOCIDO }

fun inferScene(labels: List<String>, areas: List<Float>): SceneType {
    if (labels.isEmpty()) return SceneType.DESCONOCIDO
    val indoor=labels.count{it in INDOOR_OBJS}; val outdoor=labels.count{it in OUTDOOR_OBJS}
    val persons=labels.count{it=="person"}; val cross=labels.count{it in CROSSING_HINTS}
    if (cross>=2&&(labels.contains("traffic light")||labels.contains("stop sign"))) return SceneType.CRUCE_PELIGROSO
    val isIndoor=indoor>outdoor||(indoor>0&&outdoor==0); val isOutdoor=outdoor>indoor||(outdoor>0&&indoor==0)
    val crowded=labels.size>=5||persons>=3||areas.sum()>0.40f
    return when {
        isOutdoor&&crowded->SceneType.EXTERIOR_CONCURRIDO; isOutdoor&&!crowded->SceneType.EXTERIOR_TRANQUILO
        isIndoor&&crowded->SceneType.INTERIOR_CONCURRIDO;  isIndoor&&!crowded->SceneType.INTERIOR_DESPEJADO
        crowded->SceneType.INTERIOR_CONCURRIDO; else->SceneType.DESCONOCIDO
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MOTOR DE NAVEGACIÓN — MENSAJES CORTOS Y DIRECTOS
// ─────────────────────────────────────────────────────────────────────────────
object NavigationEngine {
    data class NavDecision(val instruction: String, val priority: EventPriority, val vibrateMs: Long = 0L)

    fun decide(tracks: List<ObjectTrack>): NavDecision? {
        if (tracks.isEmpty()) return NavDecision("Camino libre.", EventPriority.CONTEXTO)

        val reliable = tracks.filter { it.framesTracked >= 2 }
        if (reliable.isEmpty()) return null

        // 1. Colisión inminente predicha — SOLO si está confirmada N frames
        reliable.filter { it.zone=="centro" && it.isApproaching && it.isConfirmed }
            .mapNotNull { t -> val (pd,_)=t.predict(COLLISION_FRAMES); if(pd>=DEPTH_PELIGRO) t to pd else null }
            .maxByOrNull { it.second }
            ?.let { (t,_) ->
                val obj=shortName(t.label)
                // Vibración larga = peligro máximo
                return NavDecision("¡Para! $obj enfrente.", EventPriority.PELIGRO_INMEDIATO, 800L)
            }

        // 2. Obstáculos por profundidad
        val center=reliable.filter{it.zone=="centro"}.maxByOrNull{it.depthScore}
        val left  =reliable.filter{it.zone=="izquierda"}
        val right =reliable.filter{it.zone=="derecha"}

        if (center!=null && center.isConfirmed) {
            val obj=shortName(center.label)
            val lClear=left.none{it.depthScore>=DEPTH_CERCA}
            val rClear=right.none{it.depthScore>=DEPTH_CERCA}
            return when {
                center.depthScore>=DEPTH_PELIGRO -> {
                    val (dir, vib) = when {
                        lClear&&!rClear -> "Gira izquierda." to 600L
                        rClear&&!lClear -> "Gira derecha."   to 600L
                        else            -> "¡Para!"          to 800L
                    }
                    NavDecision("$obj al frente. $dir", EventPriority.PELIGRO_INMEDIATO, vib)
                }
                center.depthScore>=DEPTH_CERCA -> {
                    val dir=if(lClear)"izquierda" else if(rClear)"derecha" else "un lado"
                    // Vibración corta = advertencia
                    NavDecision("$obj adelante. Ve hacia $dir.", EventPriority.NAVEGACION_URGENTE, 300L)
                }
                center.depthScore>=DEPTH_AVISO ->
                    NavDecision("Atención, $obj al frente.", EventPriority.NAVEGACION_NORMAL)
                else -> null
            }
        }

        // 3. Amenaza lateral confirmada
        (left+right).filter{it.isApproaching&&it.depthScore>=DEPTH_CERCA&&it.isConfirmed}
            .maxByOrNull{it.depthScore}
            ?.let { t ->
                val obj=shortName(t.label)
                val away=if(t.zone=="izquierda")"derecha" else "izquierda"
                return NavDecision("$obj por ${t.zone}. Muévete a la $away.", EventPriority.NAVEGACION_URGENTE, 300L)
            }

        return NavDecision("Camino libre.", EventPriority.CONTEXTO)
    }

    private fun shortName(label: String): String = LABEL_ES[label]?.short ?: label
}

// ─────────────────────────────────────────────────────────────────────────────
// MAIN ACTIVITY
// ─────────────────────────────────────────────────────────────────────────────
class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var tts: TextToSpeech
    @Volatile private var ttsReady = false
    private lateinit var ttsQueue: TtsPriorityQueue

    private var camera: Camera? = null
    private lateinit var vibrator: Vibrator

    private lateinit var yoloDetector: YoloDetector
    private var depthEstimator: DepthEstimator? = null
    private val depthAvailable = AtomicBoolean(false)

    private val trackManager = TrackManager()

    private val brightHistory = ArrayDeque<Int>()
    private var isTorchOn=false; private var lastTorchChg=0L

    private lateinit var sensorMgr: SensorManager
    private var accel: Sensor? = null
    @Volatile private var lastMotionTime = System.currentTimeMillis()

    private var lastSpeakTime=0L; private var lastStillTime=0L
    private var lastSceneTime=0L; private var lastCrossTime=0L

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val depthExecutor  = Executors.newSingleThreadExecutor()

    @Volatile private var latestDepth: Array<FloatArray>? = null
    @Volatile private var depthTs: Long = 0L

    // ── NUEVO: estado anterior para detectar cambios reales ───────────────────
    private var lastDecisionKey = ""  // evita repetir el mismo mensaje

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.viewFinder)

        // Vibrador
        @Suppress("DEPRECATION")
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        sensorMgr = getSystemService(SENSOR_SERVICE) as SensorManager
        accel     = sensorMgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        initModels()
        initTts()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
    }

    override fun onResume() { super.onResume(); accel?.let { sensorMgr.registerListener(this,it,SensorManager.SENSOR_DELAY_NORMAL) } }
    override fun onPause()  { super.onPause();  sensorMgr.unregisterListener(this) }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type!=Sensor.TYPE_ACCELEROMETER) return
        val mag=sqrt(event.values[0].pow(2)+event.values[1].pow(2)+event.values[2].pow(2))
        if (abs(mag-SensorManager.GRAVITY_EARTH)>0.8f) lastMotionTime=System.currentTimeMillis()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun initModels() {
        yoloDetector = YoloDetector(MODELO_YOLO, this)
        depthExecutor.execute {
            try { depthEstimator=DepthEstimator(this); depthAvailable.set(true) }
            catch(e:Exception){ Log.w(TAG,"Depth no disp: ${e.message}") }
        }
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status==TextToSpeech.SUCCESS) {
                val r=tts.setLanguage(java.util.Locale("es","MX"))
                if (r==TextToSpeech.LANG_MISSING_DATA||r==TextToSpeech.LANG_NOT_SUPPORTED)
                    tts.setLanguage(java.util.Locale("es"))
                ttsReady=true
                ttsQueue=TtsPriorityQueue(tts)
                speak("Navegación activa.",EventPriority.CONTEXTO)
            } else Log.e(TAG,"TTS falló: $status")
        }
    }

    private fun startCamera() {
        val future=ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider=future.get()
            val preview=Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analyzer=ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                val rotation=imageProxy.imageInfo.rotationDegrees
                val bitmap=imageProxy.toBitmap(); imageProxy.close()
                val rotated=rotateBitmap(bitmap,rotation)
                val now=System.currentTimeMillis()

                controlTorch(rotated)

                if (depthAvailable.get()) depthExecutor.execute {
                    latestDepth=depthEstimator?.estimate(rotated); depthTs=System.currentTimeMillis()
                }

                val detections=yoloDetector.detect(rotated)
                val depthMap=if (now-depthTs<250L) latestDepth else null
                processFrame(detections,depthMap,now)
            }
            provider.unbindAll()
            camera=provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,analyzer)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(detections: List<Detection>, depthMap: Array<FloatArray>?, now: Long) {
        // Quietud
        if (now-lastMotionTime>STILLNESS_MS) {
            if (!tts.isSpeaking&&now-lastStillTime>COOLDOWN_QUIETO&&now-lastSpeakTime>COOLDOWN_POST_SPEAK) {
                speak("En pausa. Muévete para continuar.",EventPriority.QUIETO); lastStillTime=now
            }
            return
        }

        val tracks=trackManager.update(detections,depthMap,now)
        val labels=tracks.map{it.label}

        checkCrossing(labels,now)

        val decision=NavigationEngine.decide(tracks) ?: return

        // ── FILTRO ANTI-SPAM: no repetir el mismo mensaje si nada cambió ─────
        // La clave incluye prioridad + objeto principal para detectar cambios reales
        val decisionKey="${decision.priority}_${decision.instruction.take(20)}"
        val changed = decisionKey != lastDecisionKey
        val isHighPriority = decision.priority.level >= EventPriority.NAVEGACION_URGENTE.level

        if (changed || isHighPriority) {
            speak(decision.instruction, decision.priority)
            if (decision.vibrateMs > 0) vibrate(decision.vibrateMs)
            lastDecisionKey = decisionKey
        }

        // Escena periódica — solo si camino libre
        if (decision.priority == EventPriority.CONTEXTO &&
            !tts.isSpeaking && now-lastSceneTime>COOLDOWN_ESCENA) {
            val areas=tracks.map{it.box.width()*it.box.height()}
            val scene=inferScene(labels,areas)
            val veh=labels.count{it in VEHICLES}; val per=labels.count{it=="person"}
            val msg=when(scene) {
                SceneType.INTERIOR_DESPEJADO  -> "Interior despejado."
                SceneType.INTERIOR_CONCURRIDO -> if(per>=3)"Lugar con mucha gente." else "Espacio con objetos."
                SceneType.EXTERIOR_TRANQUILO  -> if(veh>0)"Exterior con vehículos cerca." else "Exterior abierto."
                SceneType.EXTERIOR_CONCURRIDO -> "Exterior concurrido."
                SceneType.CRUCE_PELIGROSO     -> "Zona de cruce. Precaución."
                SceneType.DESCONOCIDO         -> return
            }
            speak(msg,EventPriority.CONTEXTO); lastSceneTime=now
        }
    }

    private fun checkCrossing(labels: List<String>, now: Long) {
        if (now-lastCrossTime<COOLDOWN_CRUCE) return
        if ((labels.contains("traffic light")||labels.contains("stop sign"))
            && labels.count{it in CROSSING_HINTS}>=2) {
            speak("Cruce detectado. Detente.",EventPriority.NAVEGACION_URGENTE)
            vibrate(500L); lastCrossTime=now
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
        if (brightHistory.size>BRIGHT_SAMPLES) brightHistory.removeFirst()
        if (brightHistory.size<BRIGHT_SAMPLES) return
        val avg=brightHistory.average().toInt(); val now=System.currentTimeMillis()
        if (now-lastTorchChg<TORCH_DEBOUNCE) return
        when {
            avg<DARK_THRESHOLD&&!isTorchOn  -> { camera?.cameraControl?.enableTorch(true);  isTorchOn=true;  lastTorchChg=now; brightHistory.clear() }
            avg>TORCH_OFF_THRESH&&isTorchOn -> { camera?.cameraControl?.enableTorch(false); isTorchOn=false; lastTorchChg=now; brightHistory.clear() }
        }
    }
    private fun calcBrightness(bitmap: Bitmap): Int {
        var total=0L; var count=0
        for (x in 0 until bitmap.width step 20) for (y in 0 until bitmap.height step 20) {
            val p=bitmap.getPixel(x,y)
            total+=(0.299*((p shr 16)and 0xFF)+0.587*((p shr 8)and 0xFF)+0.114*(p and 0xFF)).toLong(); count++
        }
        return if(count>0)(total/count).toInt() else 128
    }

    private fun rotateBitmap(bmp: Bitmap, deg: Int): Bitmap {
        if (deg==0) return bmp
        val m=Matrix(); m.postRotate(deg.toFloat())
        return Bitmap.createBitmap(bmp,0,0,bmp.width,bmp.height,m,true)
    }

    private fun speak(text: String, priority: EventPriority=EventPriority.NAVEGACION_NORMAL) {
        if (!ttsReady) return
        ttsQueue.enqueue(NavEvent(text,priority)); lastSpeakTime=System.currentTimeMillis()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if (requestCode==10&&grantResults.isNotEmpty()&&grantResults[0]==PackageManager.PERMISSION_GRANTED) startCamera() else finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::tts.isInitialized) tts.shutdown()
        yoloDetector.close(); depthEstimator?.close()
        cameraExecutor.shutdown(); depthExecutor.shutdown(); trackManager.clear()
    }
}