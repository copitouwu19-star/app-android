package com.example.myapp

import android.Manifest
import android.content.Context
import android.content.Intent
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
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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

internal const val MODELO_YOLO  = "yolov8n-oiv7_float32.tflite"
internal const val MODELO_DEPTH = "midas_v21_small_256.tflite"

internal const val YOLO_INPUT_SIZE = 320
internal const val SCORE_MINIMO    = 0.28f  // umbral bajo para capturar muebles en interiores con poca luz
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
internal const val MAX_FRAMES_PERDIDO      = 3
internal const val KALMAN_SMOOTH           = 0.55f
internal const val MIN_VELOCITY_WARN       = 0.012f
internal const val COLLISION_FRAMES        = 10
internal const val MIN_FRAMES_CONFIRMACION = 1

// Flash
internal const val DARK_THRESHOLD   = 55
internal const val TORCH_OFF_THRESH = 150
internal const val TORCH_DEBOUNCE   = 5_000L
internal const val BRIGHT_SAMPLES   = 8

// Cooldowns TTS
internal const val COOLDOWN_CRITICO    = 1_500L  // peligro máximo: 1.5s
internal const val COOLDOWN_PELIGRO    = 2_000L  // reducido a 2s para respuesta más rápida
internal const val COOLDOWN_NAVEGACION = 2_500L  // reducido a 2.5s para instrucciones de ruta más frecuentes
internal const val COOLDOWN_QUIETO     = 25_000L
internal const val COOLDOWN_POST_SPEAK = 5_000L
internal const val COOLDOWN_ESCENA     = 40_000L
internal const val COOLDOWN_CRUCE      = 8_000L
internal const val STILLNESS_MS        = 12_000L  // 12s sin movimiento → pausa (era 7s, demasiado corto caminando despacio)

// Escaneo con giroscopio
internal const val SCAN_TRIGGER_DEPTH   = 0.70f   // si hay peligro y no se ve bien → pedir escaneo
internal const val SCAN_ROTATION_DEG    = 15f     // grados de rotación para considerar que escaneó
internal const val SCAN_COOLDOWN        = 15_000L // cada 15s puede pedir escaneo
internal const val SCAN_TIMEOUT_MS      = 8_000L  // 8s para completar el escaneo
internal const val REPEAT_IF_NO_MOVE_MS = 8_000L  // repetir instrucción si usuario no se movió en 8s

// ─────────────────────────────────────────────────────────────────────────────
// ETIQUETAS COCO — 80 clases
// ─────────────────────────────────────────────────────────────────────────────
internal val OIV7_LABELS = listOf(
    "accordion","adhesive tape","aircraft","airplane","alarm clock","alpaca","ambulance","animal",
    "ant","antelope","apple","armadillo","artichoke","auto part","axe","backpack",
    "bagel","baked goods","balance beam","ball","balloon","banana","band-aid","banjo",
    "barge","barrel","baseball bat","baseball glove","bat (animal)","bathroom accessory","bathroom cabinet","bathtub",
    "beaker","bear","bed","bee","beehive","beer","beetle","bell pepper",
    "belt","bench","bicycle","bicycle helmet","bicycle wheel","bidet","billboard","billiard table",
    "binoculars","bird","blender","blue jay","boat","bomb","book","bookcase",
    "boot","bottle","bottle opener","bow and arrow","bowl","bowling equipment","box","boy",
    "brassiere","bread","briefcase","broccoli","bronze sculpture","brown bear","building","bull",
    "burrito","bus","bust","butterfly","cabbage","cabinetry","cake","cake stand",
    "calculator","camel","camera","can opener","canary","candle","candy","cannon",
    "canoe","cantaloupe","car","carnivore","carrot","cart","cassette deck","castle",
    "cat","cat furniture","caterpillar","cattle","ceiling fan","cello","centipede","chainsaw",
    "chair","cheese","cheetah","chest of drawers","chicken","chime","chisel","chopsticks",
    "christmas tree","clock","closet","clothing","coat","cocktail","cocktail shaker","coconut",
    "coffee","coffee cup","coffee table","coffeemaker","coin","common fig","common sunflower","computer keyboard",
    "computer monitor","computer mouse","container","convenience store","cookie","cooking spray","corded phone","cosmetics",
    "couch","countertop","cowboy hat","crab","cream","cricket ball","crocodile","croissant",
    "crown","crutch","cucumber","cupboard","curtain","cutting board","dagger","dairy product",
    "deer","desk","dessert","diaper","dice","digital clock","dinosaur","dishwasher",
    "dog","dog bed","doll","dolphin","door","door handle","doughnut","dragonfly",
    "drawer","dress","drill (tool)","drink","drinking straw","drum","duck","dumbbell",
    "eagle","earrings","egg (food)","elephant","envelope","eraser","face powder","facial tissue holder",
    "falcon","fashion accessory","fast food","fax","fedora","filing cabinet","fire hydrant","fireplace",
    "fish","flag","flashlight","flower","flowerpot","flute","flying disc","food",
    "food processor","football","football helmet","footwear","fork","fountain","fox","french fries",
    "french horn","frog","fruit","frying pan","furniture","garden asparagus","gas stove","giraffe",
    "girl","glasses","glove","goat","goggles","goldfish","golf ball","golf cart",
    "gondola","goose","grape","grapefruit","grinder","guacamole","guitar","hair dryer",
    "hair spray","hamburger","hammer","hamster","hand dryer","handbag","handgun","harbor seal",
    "harmonica","harp","harpsichord","hat","headphones","heater","hedgehog","helicopter",
    "helmet","high heels","hiking equipment","hippopotamus","home appliance","honeycomb","horizontal bar","horse",
    "hot dog","house","houseplant","human arm","human beard","human body","human ear","human eye",
    "human face","human foot","human hair","human hand","human head","human leg","human mouth","human nose",
    "humidifier","ice cream","indoor rower","infant bed","insect","invertebrate","ipod","isopod",
    "jacket","jacuzzi","jaguar (animal)","jeans","jellyfish","jet ski","jug","juice",
    "kangaroo","kettle","kitchen & dining room table","kitchen appliance","kitchen knife","kitchen utensil","kitchenware","kite",
    "knife","koala","ladder","ladle","ladybug","lamp","land vehicle","lantern",
    "laptop","lavender (plant)","lemon","leopard","light bulb","light switch","lighthouse","lily",
    "limousine","lion","lipstick","lizard","lobster","loveseat","luggage and bags","lynx",
    "magpie","mammal","man","mango","maple","maracas","marine invertebrates","marine mammal",
    "measuring cup","mechanical fan","medical equipment","microphone","microwave oven","milk","miniskirt","mirror",
    "missile","mixer","mixing bowl","mobile phone","monkey","moths and butterflies","motorcycle","mouse",
    "muffin","mug","mule","mushroom","musical instrument","musical keyboard","nail (construction)","necklace",
    "nightstand","oboe","office building","office supplies","orange","organ (musical instrument)","ostrich","otter",
    "oven","owl","oyster","paddle","palm tree","pancake","panda","paper cutter",
    "paper towel","parachute","parking meter","parrot","pasta","pastry","peach","pear",
    "pen","pencil case","pencil sharpener","penguin","perfume","person","personal care","personal flotation device",
    "piano","picnic basket","picture frame","pig","pillow","pineapple","pitcher (container)","pizza",
    "pizza cutter","plant","plastic bag","plate","platter","plumbing fixture","polar bear","pomegranate",
    "popcorn","porch","porcupine","poster","potato","power plugs and sockets","pressure cooker","pretzel",
    "printer","pumpkin","punching bag","rabbit","raccoon","racket","radish","ratchet (device)",
    "raven","rays and skates","red panda","refrigerator","remote control","reptile","rhinoceros","rifle",
    "ring binder","rocket","roller skates","rose","rugby ball","ruler","salad","salt and pepper shakers",
    "sandal","sandwich","saucer","saxophone","scale","scarf","scissors","scoreboard",
    "scorpion","screwdriver","sculpture","sea lion","sea turtle","seafood","seahorse","seat belt",
    "segway","serving tray","sewing machine","shark","sheep","shelf","shellfish","shirt",
    "shorts","shotgun","shower","shrimp","sink","skateboard","ski","skirt",
    "skull","skunk","skyscraper","slow cooker","snack","snail","snake","snowboard",
    "snowman","snowmobile","snowplow","soap dispenser","sock","sofa bed","sombrero","sparrow",
    "spatula","spice rack","spider","spoon","sports equipment","sports uniform","squash (plant)","squid",
    "squirrel","stairs","stapler","starfish","stationary bicycle","stethoscope","stool","stop sign",
    "strawberry","street light","stretcher","studio couch","submarine","submarine sandwich","suit","suitcase",
    "sun hat","sunglasses","surfboard","sushi","swan","swim cap","swimming pool","swimwear",
    "sword","syringe","table","table tennis racket","tablet computer","tableware","taco","tank",
    "tap","tart","taxi","tea","teapot","teddy bear","telephone","television",
    "tennis ball","tennis racket","tent","tiara","tick","tie","tiger","tin can",
    "tire","toaster","toilet","toilet paper","tomato","tool","toothbrush","torch",
    "tortoise","towel","tower","toy","traffic light","traffic sign","train","training bench",
    "treadmill","tree","tree house","tripod","trombone","trousers","truck","trumpet",
    "turkey","turtle","umbrella","unicycle","van","vase","vegetable","vehicle",
    "vehicle registration plate","violin","volleyball (ball)","waffle","waffle iron","wall clock","wardrobe","washing machine",
    "waste container","watch","watercraft","watermelon","weapon","whale","wheel","wheelchair",
    "whisk","whiteboard","willow","window","window blind","wine","wine glass","wine rack",
    "winter melon","wok","woman","wood-burning stove","woodpecker","worm","wrench","zebra",
    "zucchini"
)

internal val VEHICLES       = setOf("bicycle","car","motorcycle","bus","train","truck","boat","van","taxi","ambulance")
internal val ANIMALS        = setOf("bird","cat","dog","horse","cattle","elephant","bear","brown bear",
    "zebra","giraffe","lion","tiger","leopard","cheetah","jaguar (animal)","crocodile","deer","shark")
internal val INDOOR_OBJS    = setOf("chair","couch","bed","kitchen & dining room table","toilet","television","laptop",
    "sink","refrigerator","houseplant","clock","coffee cup","mug","bottle","mobile phone","microwave oven",
    "oven","toaster","book","chest of drawers","nightstand","wardrobe","bookcase","desk",
    "coffee table","dishwasher","mirror","shelf","cupboard","bathroom cabinet","cabinetry",
    "fireplace","lamp","pillow","digital clock","wall clock","computer monitor","computer keyboard",
    "washing machine","infant bed","loveseat","sofa bed","stool","waste container","curtain")
internal val OUTDOOR_OBJS   = setOf("car","truck","bus","motorcycle","bicycle",
    "traffic light","stop sign","fire hydrant","bench","train","boat","parking meter",
    "van","taxi","ambulance","traffic sign","billboard","street light",
    // Fuertes indicadores de exterior — corrige detección de calle/patio como "interior"
    "house","building","office building","skyscraper","tower","porch",
    "tree","palm tree","fence","gate","road","traffic cone")
internal val CROSSING_HINTS = setOf("traffic light","stop sign","car","truck","bus","bicycle","motorcycle","van","taxi")

// Objetos peligrosos a cualquier distancia (avisar aunque estén lejos)
internal val HIGH_PRIORITY_OBJS = setOf(
    "car","truck","bus","motorcycle","bicycle","person","man","woman","boy","girl",
    "dog","cat","stairs","door","ladder","van","taxi","ambulance","train","boat"
)

// Objetos que NUNCA deben generar instrucciones de evasión o peligro.
internal val SAFE_OBJECTS = setOf(
    "fork","knife","spoon","banana","apple","sandwich","orange","doughnut","cake",
    "scissors","toothbrush","food","snack","baked goods","fast food","dessert",
    "fruit","vegetable","cookie","candy","pizza","pasta","bread","salad",
    // Ropa y partes del cuerpo: no son obstáculos de navegación
    "clothing","fashion accessory","footwear","coat","jacket","dress","shirt",
    "jeans","shorts","hat","sunglasses","glasses","suit","brassiere","glove","sock",
    "human face","human hair","human hand","human arm","human leg","human body",
    "human eye","human ear","human nose","human mouth","human head","human foot"
)

internal data class LabelEs(val art: String, val noun: String, val short: String)
internal val LABEL_ES = mapOf(
    // Personas
    "person"         to LabelEs("una","persona","persona"),
    "man"            to LabelEs("un","hombre","persona"),
    "woman"          to LabelEs("una","mujer","persona"),
    "boy"            to LabelEs("un","niño","persona"),
    "girl"           to LabelEs("una","niña","persona"),
    // Vehículos
    "bicycle"        to LabelEs("una","bicicleta","bici"),
    "car"            to LabelEs("un","automóvil","auto"),
    "motorcycle"     to LabelEs("una","motocicleta","moto"),
    "airplane"       to LabelEs("un","avión","avión"),
    "aircraft"       to LabelEs("un","avión","avión"),
    "bus"            to LabelEs("un","autobús","autobús"),
    "train"          to LabelEs("un","tren","tren"),
    "truck"          to LabelEs("un","camión","camión"),
    "van"            to LabelEs("una","camioneta","camioneta"),
    "taxi"           to LabelEs("un","taxi","taxi"),
    "ambulance"      to LabelEs("una","ambulancia","ambulancia"),
    "boat"           to LabelEs("un","bote","bote"),
    // Señales
    "traffic light"  to LabelEs("un","semáforo","semáforo"),
    "traffic sign"   to LabelEs("una","señal","señal"),
    "fire hydrant"   to LabelEs("un","hidrante","hidrante"),
    "stop sign"      to LabelEs("una","señal de alto","señal"),
    "parking meter"  to LabelEs("un","parquímetro","parquímetro"),
    "bench"          to LabelEs("una","banca","banca"),
    // Animales
    "bird"           to LabelEs("un","pájaro","pájaro"),
    "cat"            to LabelEs("un","gato","gato"),
    "dog"            to LabelEs("un","perro","perro"),
    "horse"          to LabelEs("un","caballo","caballo"),
    "cattle"         to LabelEs("una","vaca","vaca"),
    "elephant"       to LabelEs("un","elefante","elefante"),
    "bear"           to LabelEs("un","oso","oso"),
    "brown bear"     to LabelEs("un","oso pardo","oso"),
    "zebra"          to LabelEs("una","cebra","cebra"),
    "giraffe"        to LabelEs("una","jirafa","jirafa"),
    "lion"           to LabelEs("un","león","león"),
    "tiger"          to LabelEs("un","tigre","tigre"),
    "deer"           to LabelEs("un","venado","venado"),
    "crocodile"      to LabelEs("un","cocodrilo","cocodrilo"),
    // Accesorios personales
    "backpack"       to LabelEs("una","mochila","mochila"),
    "umbrella"       to LabelEs("un","paraguas","paraguas"),
    "handbag"        to LabelEs("una","bolsa","bolsa"),
    "suitcase"       to LabelEs("una","maleta","maleta"),
    "luggage and bags" to LabelEs("una","maleta","maleta"),
    // Muebles — NUEVO: clases OIV7
    "chair"          to LabelEs("una","silla","silla"),
    "couch"          to LabelEs("un","sofá","sofá"),
    "loveseat"       to LabelEs("un","sofá","sofá"),
    "sofa bed"       to LabelEs("un","sofá cama","sofá"),
    "studio couch"   to LabelEs("un","sofá","sofá"),
    "bed"            to LabelEs("una","cama","cama"),
    "infant bed"     to LabelEs("una","cuna","cuna"),
    "kitchen & dining room table" to LabelEs("una","mesa","mesa"),
    "coffee table"   to LabelEs("una","mesa de centro","mesa"),
    "table"          to LabelEs("una","mesa","mesa"),
    "desk"           to LabelEs("un","escritorio","escritorio"),
    "toilet"         to LabelEs("un","inodoro","inodoro"),
    "television"     to LabelEs("un","televisor","televisor"),
    "laptop"         to LabelEs("una","computadora","computadora"),
    "mobile phone"   to LabelEs("un","celular","celular"),
    "sink"           to LabelEs("un","lavabo","lavabo"),
    "refrigerator"   to LabelEs("un","refrigerador","refrigerador"),
    "chest of drawers" to LabelEs("un","gavetero","gavetero"),
    "nightstand"     to LabelEs("una","mesita de noche","mesita"),
    "wardrobe"       to LabelEs("un","ropero","ropero"),
    "bookcase"       to LabelEs("un","librero","librero"),
    "cabinetry"      to LabelEs("un","armario","armario"),
    "bathroom cabinet" to LabelEs("un","gabinete","gabinete"),
    "filing cabinet" to LabelEs("un","archivero","archivero"),
    "cupboard"       to LabelEs("una","alacena","alacena"),
    "shelf"          to LabelEs("un","estante","estante"),
    "mirror"         to LabelEs("un","espejo","espejo"),
    "door"           to LabelEs("una","puerta","puerta"),
    "window"         to LabelEs("una","ventana","ventana"),
    "stairs"         to LabelEs("unas","escaleras","escaleras"),
    "ladder"         to LabelEs("una","escalera","escalera"),
    "lamp"           to LabelEs("una","lámpara","lámpara"),
    "fireplace"      to LabelEs("una","chimenea","chimenea"),
    "stool"          to LabelEs("un","taburete","taburete"),
    "curtain"        to LabelEs("una","cortina","cortina"),
    "pillow"         to LabelEs("una","almohada","almohada"),
    "waste container" to LabelEs("un","basurero","basurero"),
    "wheelchair"     to LabelEs("una","silla de ruedas","silla de ruedas"),
    // Electrodomésticos
    "microwave oven" to LabelEs("un","microondas","microondas"),
    "oven"           to LabelEs("un","horno","horno"),
    "toaster"        to LabelEs("una","tostadora","tostadora"),
    "dishwasher"     to LabelEs("un","lavavajillas","lavavajillas"),
    "washing machine" to LabelEs("una","lavadora","lavadora"),
    "coffeemaker"    to LabelEs("una","cafetera","cafetera"),
    "computer keyboard" to LabelEs("un","teclado","teclado"),
    "computer monitor" to LabelEs("una","pantalla","pantalla"),
    "computer mouse" to LabelEs("un","ratón","ratón"),
    "remote control" to LabelEs("un","control","control"),
    "printer"        to LabelEs("una","impresora","impresora"),
    // Utensilios y cocina
    "bottle"         to LabelEs("una","botella","botella"),
    "coffee cup"     to LabelEs("una","taza","taza"),
    "mug"            to LabelEs("una","taza","taza"),
    "bowl"           to LabelEs("un","tazón","tazón"),
    "clock"          to LabelEs("un","reloj","reloj"),
    "digital clock"  to LabelEs("un","reloj digital","reloj"),
    "wall clock"     to LabelEs("un","reloj de pared","reloj"),
    "book"           to LabelEs("un","libro","libro"),
    "vase"           to LabelEs("un","florero","florero"),
    "houseplant"     to LabelEs("una","planta","planta"),
    "plant"          to LabelEs("una","planta","planta"),
    "scissors"       to LabelEs("unas","tijeras","tijeras"),
    "toothbrush"     to LabelEs("un","cepillo dental","cepillo"),
    "teddy bear"     to LabelEs("un","peluche","peluche"),
    "wine glass"     to LabelEs("una","copa","copa"),
    // Electrodomésticos / ventilación (frecuentes en interiores)
    "ceiling fan"    to LabelEs("un","ventilador de techo","ventilador"),
    "mechanical fan" to LabelEs("un","ventilador","ventilador"),
    "heater"         to LabelEs("un","calefactor","calefactor"),
    "humidifier"     to LabelEs("un","humidificador","humidificador"),
    "gas stove"      to LabelEs("una","estufa de gas","estufa"),
    "kitchen appliance" to LabelEs("un","electrodoméstico","electrodoméstico"),
    "home appliance" to LabelEs("un","electrodoméstico","electrodoméstico"),
    "blender"        to LabelEs("una","licuadora","licuadora"),
    "kettle"         to LabelEs("una","tetera","tetera"),
    "slow cooker"    to LabelEs("una","olla de cocción lenta","olla"),
    "pressure cooker" to LabelEs("una","olla a presión","olla"),
    "food processor" to LabelEs("un","procesador de alimentos","procesador"),
    "sewing machine" to LabelEs("una","máquina de coser","máquina"),
    // Baño
    "bathtub"        to LabelEs("una","bañera","bañera"),
    "shower"         to LabelEs("una","ducha","ducha"),
    "tap"            to LabelEs("un","grifo","grifo"),
    "towel"          to LabelEs("una","toalla","toalla"),
    "soap dispenser" to LabelEs("un","dispensador de jabón","jabón"),
    // Habitación / sala
    "closet"         to LabelEs("un","clóset","clóset"),
    "drawer"         to LabelEs("un","cajón","cajón"),
    "door handle"    to LabelEs("una","manija","manija"),
    "window blind"   to LabelEs("una","persiana","persiana"),
    "picture frame"  to LabelEs("un","cuadro","cuadro"),
    "countertop"     to LabelEs("una","encimera","encimera"),
    "jacuzzi"        to LabelEs("un","jacuzzi","jacuzzi"),
    // Gimnasio / deporte
    "treadmill"      to LabelEs("una","caminadora","caminadora"),
    "stationary bicycle" to LabelEs("una","bicicleta estática","bici"),
    "indoor rower"   to LabelEs("una","máquina de remo","máquina"),
    "dumbbell"       to LabelEs("una","pesa","pesa"),
    "training bench" to LabelEs("una","banca de ejercicio","banca"),
    "punching bag"   to LabelEs("un","saco de boxeo","saco"),
    // Otros objetos comunes en interiores
    "whiteboard"     to LabelEs("una","pizarra","pizarra"),
    "poster"         to LabelEs("un","póster","póster"),
    "light switch"   to LabelEs("un","interruptor","interruptor"),
    "light bulb"     to LabelEs("una","bombilla","bombilla"),
    "power plugs and sockets" to LabelEs("un","enchufe","enchufe"),
    "toilet paper"   to LabelEs("un","papel higiénico","papel"),
    "flowerpot"      to LabelEs("una","maceta","maceta"),
    "sculpture"      to LabelEs("una","escultura","escultura"),
    "box"            to LabelEs("una","caja","caja"),
    "cat furniture"  to LabelEs("un","árbol para gatos","árbol"),
    // Exterior — estas faltan y aparecen en inglés
    "house"          to LabelEs("una","casa","casa"),
    "building"       to LabelEs("un","edificio","edificio"),
    "office building" to LabelEs("un","edificio","edificio"),
    "tree"           to LabelEs("un","árbol","árbol"),
    "palm tree"      to LabelEs("una","palma","palma"),
    "fence"          to LabelEs("una","cerca","cerca"),
    "gate"           to LabelEs("una","reja","reja"),
    "porch"          to LabelEs("un","portal","portal"),
    "tower"          to LabelEs("una","torre","torre"),
    "skyscraper"     to LabelEs("un","rascacielos","rascacielos"),
    "street light"   to LabelEs("un","poste de luz","poste"),
    "traffic cone"   to LabelEs("un","cono de tráfico","cono"),
    "road"           to LabelEs("una","calle","calle"),
    // Ropa y personas (aparecen en inglés)
    "clothing"       to LabelEs("ropa","ropa","ropa"),
    "fashion accessory" to LabelEs("un","accesorio","accesorio"),
    "footwear"       to LabelEs("calzado","calzado","calzado"),
    "coat"           to LabelEs("un","abrigo","abrigo"),
    "jacket"         to LabelEs("una","chaqueta","chaqueta"),
    "dress"          to LabelEs("un","vestido","vestido"),
    "shirt"          to LabelEs("una","camisa","camisa"),
    "jeans"          to LabelEs("unos","pantalones","pantalón"),
    "shorts"         to LabelEs("unos","shorts","shorts"),
    "hat"            to LabelEs("un","sombrero","sombrero"),
    "sunglasses"     to LabelEs("unos","lentes","lentes"),
    "glasses"        to LabelEs("unos","lentes","lentes"),
    // Naturaleza / exterior
    "flower"         to LabelEs("una","flor","flor"),
    "rose"           to LabelEs("una","rosa","rosa"),
    "houseplant"     to LabelEs("una","planta","planta"),
    "mushroom"       to LabelEs("un","hongo","hongo"),
    // Animales comunes no mapeados
    "rabbit"         to LabelEs("un","conejo","conejo"),
    "squirrel"       to LabelEs("una","ardilla","ardilla"),
    "turtle"         to LabelEs("una","tortuga","tortuga"),
    "duck"           to LabelEs("un","pato","pato"),
    "chicken"        to LabelEs("un","pollo","pollo"),
    "goat"           to LabelEs("una","cabra","cabra"),
    "sheep"          to LabelEs("una","oveja","oveja"),
    "pig"            to LabelEs("un","cerdo","cerdo"),
    "snake"          to LabelEs("una","serpiente","serpiente"),
    // Vehículos extra
    "segway"         to LabelEs("un","segway","segway"),
    "skateboard"     to LabelEs("una","patineta","patineta"),
    "jet ski"        to LabelEs("una","moto de agua","moto de agua"),
    "helicopter"     to LabelEs("un","helicóptero","helicóptero"),
    // Objetos comunes sin traducir
    "barrel"         to LabelEs("un","barril","barril"),
    "box"            to LabelEs("una","caja","caja"),
    "container"      to LabelEs("un","contenedor","contenedor"),
    "ladder"         to LabelEs("una","escalera","escalera"),
    "rope"           to LabelEs("una","cuerda","cuerda"),
    "chain"          to LabelEs("una","cadena","cadena"),
    "flag"           to LabelEs("una","bandera","bandera"),
    "sign"           to LabelEs("un","letrero","letrero"),
    "billboard"      to LabelEs("una","valla","valla"),
    "fire hydrant"   to LabelEs("un","hidrante","hidrante")
)

//dibujar boxes en cada objeto que vaya detectando

class DetectionOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paintCritico = Paint().apply { color = Color.RED;    strokeWidth = 7f; style = Paint.Style.STROKE }
    private val paintPeligro = Paint().apply { color = Color.parseColor("#FF6600"); strokeWidth = 5f; style = Paint.Style.STROKE }
    private val paintCerca   = Paint().apply { color = Color.YELLOW; strokeWidth = 4f; style = Paint.Style.STROKE }
    private val paintAviso   = Paint().apply { color = Color.parseColor("#00CCFF"); strokeWidth = 3f; style = Paint.Style.STROKE }
    private val paintLejano  = Paint().apply { color = Color.WHITE;  strokeWidth = 2f; style = Paint.Style.STROKE; alpha = 130 }

    private val paintText = Paint().apply {
        color = Color.WHITE; textSize = 34f; typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }
    private val paintTextBg = Paint().apply {
        color = Color.parseColor("#BB000000"); style = Paint.Style.FILL
    }

    private val smoothedBoxes = mutableMapOf<String, RectF>()
    private val LERP = 0.13f  // bajo para boxes estables como cámaras de seguridad

    @Volatile private var tracks: List<ObjectTrack> = emptyList()

    /** Recibe los ObjectTrack del TrackManager — claves estables por track.id */
    fun update(newTracks: List<ObjectTrack>) {
        tracks = newTracks
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = tracks
        if (current.isEmpty()) { smoothedBoxes.clear(); return }

        val scaleX = width.toFloat()
        val scaleY = height.toFloat()
        val currentKeys = mutableSetOf<String>()

        for (track in current) {
            val key = track.id.toString()
            currentKeys.add(key)

            // Usar smoothW/smoothH (Kalman sobre dimensiones) para estabilizar el tamaño
            val bw = track.smoothW
            val bh = track.smoothH
            val targetBox = RectF(
                (track.cx - bw / 2f) * scaleX, (track.cy - bh / 2f) * scaleY,
                (track.cx + bw / 2f) * scaleX, (track.cy + bh / 2f) * scaleY
            )

            val smoothed = smoothedBoxes.getOrPut(key) { RectF(targetBox) }
            smoothed.left   += (targetBox.left   - smoothed.left)   * LERP
            smoothed.top    += (targetBox.top    - smoothed.top)    * LERP
            smoothed.right  += (targetBox.right  - smoothed.right)  * LERP
            smoothed.bottom += (targetBox.bottom - smoothed.bottom) * LERP

            val paint = when {
                track.depthScore >= DEPTH_CRITICO -> paintCritico
                track.depthScore >= DEPTH_PELIGRO -> paintPeligro
                track.depthScore >= DEPTH_CERCA   -> paintCerca
                track.depthScore >= DEPTH_AVISO   -> paintAviso
                else                              -> paintLejano
            }

            canvas.drawRect(smoothed, paint)

            // Etiqueta: nombre + distancia estimada
            val name  = LABEL_ES[track.label]?.short ?: track.label
            val distM = DepthCalibration.toMeters(track.depthScore)
            val distStr = if (distM < 5.5f) " ~${"%.1f".format(distM)}m" else ""
            val moveIndicator = when {
                track.vDepth > 0.015f -> " →"   // acercándose
                track.vDepth < -0.015f -> " ←"  // alejándose
                else -> ""
            }
            val labelText = "$name$distStr$moveIndicator"
            val textW = paintText.measureText(labelText)
            val textH = paintText.textSize

            canvas.drawRect(smoothed.left, smoothed.top - textH - 8f,
                smoothed.left + textW + 12f, smoothed.top, paintTextBg)
            paintText.color = paint.color
            canvas.drawText(labelText, smoothed.left + 6f, smoothed.top - 6f, paintText)
        }

        smoothedBoxes.keys.retainAll(currentKeys)
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

            interpreter = Interpreter(buffer, Interpreter.Options().apply { numThreads = 4 })
            Log.d(TAG, "YOLOv8n OK — CPU x4")
        } catch (e: Exception) { Log.e(TAG, "Error YOLO: ${e.message}") }
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val interp  = interpreter ?: return emptyList()

        // ── LETTERBOX: escalar conservando proporción y rellenar a un cuadrado ──
        // Sin esto, el frame vertical se APLASTA a 320×320 → YOLO clasifica mal
        // (falsos positivos) y las cajas salen deformadas → distancias erróneas.
        val w0 = bitmap.width.toFloat(); val h0 = bitmap.height.toFloat()
        val scale = minOf(YOLO_INPUT_SIZE / w0, YOLO_INPUT_SIZE / h0)
        val newW  = (w0 * scale).roundToInt()
        val newH  = (h0 * scale).roundToInt()
        val padX  = (YOLO_INPUT_SIZE - newW) / 2f
        val padY  = (YOLO_INPUT_SIZE - newH) / 2f

        val resized = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        val letter  = Bitmap.createBitmap(YOLO_INPUT_SIZE, YOLO_INPUT_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(letter).apply {
            drawColor(Color.rgb(114, 114, 114))   // gris neutro estándar de YOLO
            drawBitmap(resized, padX, padY, null)
        }
        if (resized !== bitmap) resized.recycle()

        val inputBuf = ByteBuffer.allocateDirect(4 * YOLO_INPUT_SIZE * YOLO_INPUT_SIZE * 3)
            .order(ByteOrder.nativeOrder())

        for (y in 0 until YOLO_INPUT_SIZE) for (x in 0 until YOLO_INPUT_SIZE) {
            val px = letter.getPixel(x, y)
            inputBuf.putFloat(((px shr 16) and 0xFF) / 255f)
            inputBuf.putFloat(((px shr  8) and 0xFF) / 255f)
            inputBuf.putFloat(( px         and 0xFF) / 255f)
        }
        inputBuf.rewind()
        letter.recycle()

        // Factores para revertir el letterbox: de coords del cuadrado de entrada
        // a coords normalizadas del frame ORIGINAL.
        val sx = newW / YOLO_INPUT_SIZE.toFloat(); val ox = padX / YOLO_INPUT_SIZE
        val sy = newH / YOLO_INPUT_SIZE.toFloat(); val oy = padY / YOLO_INPUT_SIZE

        val outputBuf = Array(1) { Array(605) { FloatArray(2100) } }
        try { interp.run(inputBuf, outputBuf) }
        catch (e: Exception) { Log.e(TAG, "YOLO inf: ${e.message}"); return emptyList() }

        val raw = outputBuf[0]
        data class Raw(val box: RectF, val cls: Int, val score: Float)
        val raws = mutableListOf<Raw>()

        for (a in 0 until 2100) {
            var bestCls = 0; var bestScore = 0f
            for (c in 0 until 601) {
                val s = raw[4 + c][a]; if (s > bestScore) { bestScore = s; bestCls = c }
            }
            if (bestScore < SCORE_MINIMO) continue
            // Deshacer el letterbox: pasar de coords del cuadrado al frame original
            val cx = (raw[0][a] - ox) / sx
            val cy = (raw[1][a] - oy) / sy
            val w  = raw[2][a] / sx
            val h  = raw[3][a] / sy
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
            .map { Detection(raws[it].box, OIV7_LABELS.getOrElse(raws[it].cls) { "objeto" }, raws[it].score) }
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
        // Shape corregido: [1, 256, 256, 1] — el modelo tiene canal extra al final
        val out = Array(1) { Array(SZ) { Array(SZ) { FloatArray(1) } } }
        return try {
            interp.runForMultipleInputsOutputs(arrayOf(inputBuf), mapOf(0 to out))
            // Extraer canal único → convertir a Array<FloatArray> normal
            val raw = Array(SZ) { y -> FloatArray(SZ) { x -> out[0][y][x][0] } }
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
    var dangerLevel: Int = 0, var dangerFrames: Int = 0,
    var smoothW: Float = box.width(), var smoothH: Float = box.height()
) {
    fun update(newBox: RectF, newDepth: Float, now: Long) {
        val newCx = newBox.centerX(); val newCy = newBox.centerY()
        vx     = KALMAN_SMOOTH * vx     + (1f - KALMAN_SMOOTH) * (newCx - cx)
        vy     = KALMAN_SMOOTH * vy     + (1f - KALMAN_SMOOTH) * (newCy - cy)
        vDepth = KALMAN_SMOOTH * vDepth + (1f - KALMAN_SMOOTH) * (newDepth - depthScore)
        smoothW = KALMAN_SMOOTH * smoothW + (1f - KALMAN_SMOOTH) * newBox.width()
        smoothH = KALMAN_SMOOTH * smoothH + (1f - KALMAN_SMOOTH) * newBox.height()
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
            val depth = depthMap?.let { sampleDepth(it, detections[m.di].box, detections[m.di].label) } ?: fallback(detections[m.di].box, detections[m.di].label)
            tracks[m.ti].update(detections[m.di].box, depth, now)
            tracks[m.ti].score = detections[m.di].score
            matched[m.di] = true; usedTrks.add(m.ti)
        }
        for ((di, det) in detections.withIndex()) {
            if (matched[di]) continue
            val depth = depthMap?.let { sampleDepth(it, det.box, det.label) } ?: fallback(det.box, det.label)
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

    // sampleDepth recibe también label para usar estimación métrica como ancla anti-inflación
    private fun sampleDepth(map: Array<FloatArray>, box: RectF, label: String = ""): Float {
        val mh = map.size; val mw = map[0].size
        val cx = box.centerX(); val cy = box.centerY()
        val hw = box.width() * 0.25f; val hh = box.height() * 0.25f
        val x0 = ((cx - hw) * mw).toInt().coerceIn(0, mw - 1)
        val x1 = ((cx + hw) * mw).toInt().coerceIn(0, mw - 1)
        val y0 = ((cy - hh) * mh).toInt().coerceIn(0, mh - 1)
        val y1 = ((cy + hh) * mh).toInt().coerceIn(0, mh - 1)
        var sum = 0f; var count = 0
        for (y in y0..y1 step 2) for (x in x0..x1 step 2) { sum += map[y][x]; count++ }
        val midasRaw = if (count > 0) sum / count else 0f
        // Usar ancla métrica combinada (altura + ancho + techo de píxeles).
        // Si MiDaS infla valores en interiores, la métrica lo corrige.
        val metricAnchor = bestMetricAnchor(box, label)
        return if (metricAnchor != null) {
            // 40% métrica (tamaño del box) + 60% MiDaS (profundidad real)
            // Más peso a MiDaS para no inflar distancias cuando YOLO dibuja boxes grandes
            (metricAnchor * 0.40f + midasRaw * 0.60f).coerceIn(0f, 1f)
        } else {
            // Sin referencia métrica: MiDaS con techo conservador basado en área
            val areaCap = (box.width() * box.height() * 1.5f + 0.15f).coerceAtMost(0.45f)
            minOf(midasRaw, areaCap + 0.20f).coerceIn(0f, 1f)
        }
    }

    private fun fallback(box: RectF, label: String = ""): Float {
        val metricDepth = bestMetricAnchor(box, label)
        if (metricDepth != null) return metricDepth.coerceIn(0f, 1f)
        val area = (box.width() * box.height()).coerceIn(0f, 1f)
        return (area * 2.5f + 0.10f).coerceAtMost(0.90f)
    }

    fun allTracks(): List<ObjectTrack> = tracks.filter { it.framesLost == 0 }
    fun clear() = tracks.clear()

    private fun bestMetricAnchor(box: RectF, label: String): Float? {
        val anchor = DepthCalibration.bestAnchor(box.width(), box.height(), label)
        if (anchor != null)
            Log.v(TAG, "ANCHOR $label w=${"%.2f".format(box.width())} h=${"%.2f".format(box.height())} " +
                "→ depth=${"%.2f".format(anchor)} (~${"%.1f".format(DepthCalibration.toMeters(anchor))}m)")
        return anchor
    }
}

// TTS CON PRIORIDAD

enum class EventPriority(val level: Int) {
    CRITICO(5), PELIGRO_INMEDIATO(4), NAVEGACION_URGENTE(3), NAVEGACION_NORMAL(2), CONTEXTO(1), QUIETO(0)
}

data class NavEvent(val message: String, val priority: EventPriority, val ts: Long = System.currentTimeMillis()) : Comparable<NavEvent> {
    override fun compareTo(other: NavEvent): Int = compareValuesBy(other, this, { it.priority.level }, { it.ts })
}

class TtsPriorityQueue(private val tts: TextToSpeech) {
    private val queue = PriorityQueue<NavEvent>()
    private var lastTime = 0L; private var lastPriority = EventPriority.QUIETO
    var userSpeedMultiplier = 1.0f
    var userPitch           = 1.0f

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
        // Descartar mensajes viejos de baja prioridad (evita instrucciones de una escena anterior).
        while (true) {
            val ev = queue.peek() ?: return
            if (ev.priority.level < EventPriority.PELIGRO_INMEDIATO.level) {
                val staleMs = if (ev.priority == EventPriority.NAVEGACION_URGENTE) 3_000L else 2_500L
                if (System.currentTimeMillis() - ev.ts > staleMs) { queue.poll(); continue }
            }
            break
        }
        val event = queue.peek() ?: return
        val interrupt = event.priority.level >= EventPriority.PELIGRO_INMEDIATO.level
        if (tts.isSpeaking && !interrupt) return
        queue.poll()
        val baseSpeed = when (event.priority) {
            EventPriority.CRITICO            -> 1.1f
            EventPriority.PELIGRO_INMEDIATO  -> 1.05f
            EventPriority.NAVEGACION_URGENTE -> 1.0f
            else -> 0.98f
        }
        if (event.priority == EventPriority.CRITICO && tts.isSpeaking) tts.stop()
        tts.setPitch(userPitch)
        tts.setSpeechRate(baseSpeed * userSpeedMultiplier)
        tts.speak(event.message, TextToSpeech.QUEUE_FLUSH, null,
            "nav_${event.priority.name}_${System.currentTimeMillis()}")
        lastTime = System.currentTimeMillis(); lastPriority = event.priority
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MAIN ACTIVITY
// ─────────────────────────────────────────────────────────────────────────────
class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: DetectionOverlay
    private lateinit var scanModeLabel: TextView
    private lateinit var settingsBtn: ImageButton
    private lateinit var tts: TextToSpeech
    @Volatile private var ttsReady = false
    private lateinit var ttsQueue: TtsPriorityQueue
    private lateinit var sessionManager: SessionManager

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
    private var scanModeActive      = false
    private var scanStartTime       = 0L
    private var scanStartAngle      = 0f
    private var scanMaxAngle        = 0f
    private var lastScanRequest     = 0L
    private var gyroAngleZ          = 0f
    // true cuando el escaneo acaba de terminar y hay que verificar si seguimos bloqueados
    private var postScanCheckPending = false

    // Timestamps
    private var lastSpeakTime       = 0L
    private var lastStillTime       = 0L
    private var lastSceneTime       = 0L
    private var lastCrossTime       = 0L
    private var lastWallTime        = 0L
    private var lastStairsTime      = 0L
    private var lastObstacleTime    = 0L
    private var lastStreetGuideTime = 0L

    // Tracking de tipo de escena para detectar cambios de entorno
    private var lastSceneType: SceneType = SceneType.DESCONOCIDO
    private var lastSceneAnnounceTime: Long = 0L

    // Decision Engine — cerebro de navegación principal
    private val decisionEngine = DecisionEngine()

    // Descripción inicial del entorno
    private var entornoDescrito   = false
    private var framesParaEntorno = 0

    private var frameCount = 0

    // ── Gemini — consultor ocasional ─────────────────────────────────────────
    // ⚠️ Reemplaza TU_API_KEY con tu clave de Google AI Studio antes de compilar
    private val gemini = GeminiAdvisor(apiKey = "AIzaSyCl49xEMRKk6EF1x2T-5Emrtf5P_r0PRPk")
    private lateinit var userPrefs: UserPreferences

    // Timestamps de control de Gemini
    private var lastGeminiSceneTime = 0L   // última vez que actualizarEscena() habló
    private var ultimaDescGemini    = ""   // última descripción dada, para detectar cambios

    // Detector de oscilación de etiquetas YOLO (para confirmarObjeto)
    private var labelOscilacion1  = ""
    private var labelOscilacion2  = ""
    private var labelOscilacionTs = 0L
    private var labelOscilacionId = -1

    // Flag para no lanzar coroutines Gemini simultáneas
    @Volatile private var geminiRunning = false

    // Copia del último frame para pasarla a Gemini
    @Volatile private var latestBitmap: Bitmap? = null

    // Pipeline
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val depthExecutor  = Executors.newSingleThreadExecutor()
    @Volatile private var latestDepth: Array<FloatArray>? = null
    @Volatile private var depthTs: Long = 0L

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView   = findViewById(R.id.viewFinder)
        overlay       = findViewById(R.id.detectionOverlay)
        scanModeLabel = findViewById(R.id.scanModeLabel)
        settingsBtn   = findViewById(R.id.settingsBtn)

        userPrefs = UserPreferences(this)
        sessionManager = SessionManager(this, lifecycleScope)

        @Suppress("DEPRECATION")
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        sensorMgr = getSystemService(SENSOR_SERVICE) as SensorManager
        accel = sensorMgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyro  = sensorMgr.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        initModels()
        initTts()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
            requestGpsPermissionIfNeeded()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION),
                10
            )
        }
    }

    override fun onResume() {
        super.onResume()
        accel?.let { sensorMgr.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        gyro?.let  { sensorMgr.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        // Aplicar cambios de preferencias que el usuario pudo haber cambiado en Settings
        if (::ttsQueue.isInitialized) {
            ttsQueue.userSpeedMultiplier = userPrefs.getSpeechRate()
            ttsQueue.userPitch           = userPrefs.getPitch()
        }
        // El sistema puede apagar el flash al pausar; resetear estado para re-evaluar.
        isTorchOn = false
        lastTorchChg = 0L
        brightHistory.clear()
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

                // Girar/apuntar la cámara cuenta como ACTIVIDAD. Antes solo el
                // acelerómetro reseteaba la quietud, así que mover la cámara sin
                // caminar disparaba la pausa y CONGELABA las cajas ~12s.
                val rotRate = sqrt(event.values[0].pow(2) + event.values[1].pow(2) + event.values[2].pow(2))
                if (rotRate > 0.15f) lastMotionTime = System.currentTimeMillis()

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
        postScanCheckPending = true
        runOnUiThread { scanModeLabel.visibility = View.GONE }
    }

    private fun cancelScan() {
        scanModeActive = false
        postScanCheckPending = true
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
                ttsQueue = TtsPriorityQueue(tts).also {
                    it.userSpeedMultiplier = userPrefs.getSpeechRate()
                    it.userPitch           = userPrefs.getPitch()
                }
                selectBestVoice()
                speak("Iniciando sistema. Analizando entorno...", EventPriority.CONTEXTO)
                // Iniciar sesión en DB una vez que el sistema está listo
                sessionManager.startSession(userPrefs)
            } else Log.e(TAG, "TTS falló: $status")
        }
    }

    /** Selecciona la voz española de mayor calidad disponible en el dispositivo.
     *  Las voces "enhanced" de Google TTS suenan significativamente más naturales.
     *  Un pitch ligeramente más bajo (0.92) reduce el sonido robótico. */
    private fun selectBestVoice() {
        val allVoices = tts.voices ?: return

        // Si el usuario eligió una voz específica en Settings, usarla
        val savedName = userPrefs.vozNombre
        if (savedName.isNotEmpty()) {
            val savedVoice = allVoices.firstOrNull { it.name == savedName }
            if (savedVoice != null) {
                tts.voice = savedVoice
                Log.d(TAG, "TTS voz guardada: ${savedVoice.name}")
                tts.setPitch(1.0f)
                return
            }
        }

        // Auto-selección: mejor voz española disponible
        val best = allVoices
            .filter { v ->
                v.locale.language == "es" &&
                !v.features.contains(android.speech.tts.TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            .minByOrNull { v ->
                when {
                    v.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH -> -2000 + v.latency
                    v.quality >= android.speech.tts.Voice.QUALITY_HIGH      -> -1000 + v.latency
                    else                                                     ->  10000 + v.latency
                }
            }
        if (best != null) {
            tts.voice = best
            Log.d(TAG, "TTS voz auto: ${best.name} calidad=${best.quality} latencia=${best.latency}ms")
        }
        tts.setPitch(1.0f)
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

                // Guardar copia ligera del frame actual para Gemini
                // (solo se usa cuando Gemini es invocado, no en cada frame)
                val prevBitmap = latestBitmap
                latestBitmap = rotated.copy(rotated.config ?: Bitmap.Config.ARGB_8888, false)
                prevBitmap?.recycle()

                // MiDaS cada 4 frames
                frameCount++
                if (depthAvailable.get() && frameCount % 4 == 0) {
                    val bmpCopy = rotated.copy(rotated.config ?: Bitmap.Config.ARGB_8888, false)
                    depthExecutor.execute {
                        latestDepth = depthEstimator?.estimate(bmpCopy)
                        depthTs     = System.currentTimeMillis()
                        bmpCopy.recycle()
                    }

                }

                val t0 = System.currentTimeMillis()
                val detections = yoloDetector.detect(rotated)
                val yoloMs = System.currentTimeMillis() - t0
                // MiDaS CPU tarda 300-600ms — aceptamos hasta 1200ms para no quedarnos sin depth
                val depthAge = now - depthTs
                val depthMap = if (depthAge < 1200L) latestDepth else null
                Log.d(TAG, "FRAME yolo=${yoloMs}ms dets=${detections.size} depthAge=${depthAge}ms depthOk=${depthMap != null}")

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
        Log.d(TAG, "TRACKS total=${tracks.size} labels=${labels.distinct()}")

        // Overlay con tracks estables (IDs fijos → sin temblor)
        overlay.update(tracks)

        // ── DESCRIPCIÓN INICIAL DEL ENTORNO ───────────────────────────────────
        // Espera 5 frames para tener detecciones estables, luego describe el entorno
        if (!entornoDescrito) {
            framesParaEntorno++
            if (framesParaEntorno >= 5 && (labels.isNotEmpty() || framesParaEntorno >= 20)) {
                describirEntornoInicial(labels, tracks)
                entornoDescrito = true
            }
            return  // No navegar hasta describir el entorno
        }

        checkCrossing(tracks, labels, now)

        // Detectar cambio de entorno y guía de cruce en calle
        val areas = tracks.map { it.box.width() * it.box.height() }
        checkSceneChange(inferScene(labels, areas), tracks, labels, now)

        // ── DECISION ENGINE — única fuente de verdad para hablar ─────────────
        // Selecciona 1 objeto principal, construye mensaje con pasos + evasión,
        // filtra repeticiones y repite solo si el usuario no reaccionó.
        val speakDecision = decisionEngine.process(tracks, lastMotionTime, now, userPrefs.getDepthThreshold())

        if (speakDecision != null) {
            speak(speakDecision.message, speakDecision.priority)
            if (speakDecision.vibrateMs > 0) vibrate(speakDecision.vibrateMs)
            if (speakDecision.requestScan) startScanMode()
            lastSpeakTime = now
        }

        // ── POST-SCAN: si el escaneo terminó y seguimos bloqueados → Gemini ─────
        if (postScanCheckPending && !geminiRunning) {
            postScanCheckPending = false
            if (speakDecision?.requestScan == true) {
                intentarSalidaConGemini(tracks, now)
            }
        }

        // ── ESCALONES (peligro de caída) ──────────────────────────────────────
        // Se evalúa SIEMPRE y con prioridad alta para que pueda interrumpir.
        // Antes estaba bloqueado tras speakDecision==null y !tts.isSpeaking, por
        // eso el aviso llegaba ~10s tarde (cuando ya habías pasado el escalón).
        if (now - lastStairsTime > 6_000L && detectEscalonesAdelante(depthMap)) {
            speak("¡Cuidado! Posible escalón adelante.", EventPriority.PELIGRO_INMEDIATO)
            vibrate(400L)
            lastStairsTime = now
        }

        // ── OBSTÁCULO/PARED SIN ETIQUETA YOLO ────────────────────────────────
        // Cubre gavetero, mesita, pared, esquina — cualquier bloqueo que YOLO
        // no reconoció. Se evalúa siempre que el centro no tenga track YOLO.
        // Excluir SAFE_OBJECTS: frutas/comida en el centro no deben bloquear la detección sin etiqueta.
        val centerCoveredByYolo = tracks.any { it.zone == "centro" && it.depthScore >= DEPTH_CERCA && it.label !in SAFE_OBJECTS }
        if (!centerCoveredByYolo && now - lastObstacleTime > 2_800L) {
            val isPared  = detectParedAlFrente(depthMap)
            val obsDepth = if (!isPared) detectObstaculoCercanoSinYolo(depthMap) else 0f
            when {
                isPared && now - lastWallTime > 7_000L -> {
                    speak("Camino obstruido. Detente.", EventPriority.PELIGRO_INMEDIATO)
                    vibrate(350L); lastWallTime = now; lastObstacleTime = now
                }
                obsDepth >= DEPTH_PELIGRO -> {
                    val prio = if (obsDepth >= DEPTH_CRITICO) EventPriority.PELIGRO_INMEDIATO
                               else EventPriority.NAVEGACION_URGENTE
                    speak("Objeto al frente.", prio)
                    vibrate(if (prio == EventPriority.PELIGRO_INMEDIATO) 400L else 200L)
                    lastObstacleTime = now
                }
                obsDepth >= DEPTH_CERCA -> {
                    speak("Algo al frente. Avanza con cuidado.", EventPriority.NAVEGACION_NORMAL)
                    lastObstacleTime = now
                }
            }
        }

        // Descripción periódica del entorno — SOLO si no hay peligro activo y
        // el Decision Engine está en silencio
        val peligroActivo = NavigationEngine.hayPeligroActivo(tracks)
        if (!peligroActivo && speakDecision == null
            && !tts.isSpeaking && now - lastSceneTime > COOLDOWN_ESCENA) {
            // Gemini intenta actualizar la escena; si falla cae a buildSceneMessage local
            intentarActualizarEscenaConGemini(labels, tracks, now)
        }

        // Detector de oscilación de etiquetas YOLO — llama confirmarObjeto() si oscila
        checkLabelOscilacion(tracks, now)
    }

    /**
     * Detecta cuando un mismo objeto (mismo trackId) alterna entre 2 etiquetas
     * distintas en menos de 2 segundos. En ese caso pide a Gemini que confirme.
     */
    private fun checkLabelOscilacion(tracks: List<ObjectTrack>, now: Long) {
        if (geminiRunning) return

        for (track in tracks) {
            val id    = track.id
            val label = track.label

            if (id == labelOscilacionId) {
                // Mismo objeto — ¿cambió de etiqueta?
                if (label != labelOscilacion1 && label != labelOscilacion2) {
                    // Nueva etiqueta distinta — reiniciar
                    labelOscilacion1  = label
                    labelOscilacion2  = ""
                    labelOscilacionTs = now
                } else if (label != labelOscilacion1 && labelOscilacion2.isEmpty()) {
                    // Segunda etiqueta distinta detectada
                    labelOscilacion2  = label
                    labelOscilacionTs = now
                } else if (labelOscilacion2.isNotEmpty()
                    && label == labelOscilacion1
                    && now - labelOscilacionTs < 2_000L) {
                    // Oscilación confirmada: alterna entre label1 y label2 en <2s
                    val l1 = labelOscilacion1
                    val l2 = labelOscilacion2
                    val bmpCopy = latestBitmap?.copy(latestBitmap!!.config ?: Bitmap.Config.ARGB_8888, false)
                    if (bmpCopy != null) {
                        geminiRunning = true
                        lifecycleScope.launch {
                            val confirmado = gemini.confirmarObjeto(bmpCopy, l1, l2)
                            bmpCopy.recycle()
                            if (confirmado != null) {
                                Log.d(TAG, "GEMINI confirmo objeto: $l1/$l2 → $confirmado")
                                // No habla aquí — el DecisionEngine usará el label correcto
                                // en el próximo frame cuando YOLO lo reclasifique
                            }
                            geminiRunning = false
                        }
                    }
                    // Resetear para no disparar de nuevo inmediatamente
                    labelOscilacion1  = ""
                    labelOscilacion2  = ""
                    labelOscilacionId = -1
                    break
                }
            } else {
                // Nuevo objeto que no estábamos siguiendo
                if (labelOscilacion1.isEmpty()) {
                    labelOscilacion1  = label
                    labelOscilacionId = id
                    labelOscilacionTs = now
                }
            }
        }
    }   // ← cierre de checkLabelOscilacion

    /**
     * Primera descripción del entorno al arrancar.
     * Intenta llamar a Gemini (1 sola vez por sesión).
     * Si Gemini falla o hay timeout → usa la descripción local original como fallback.
     */
    private fun describirEntornoInicial(labels: List<String>, tracks: List<ObjectTrack>) {
        if (geminiRunning) return
        geminiRunning = true

        val bmpCopy      = latestBitmap?.copy(latestBitmap!!.config ?: Bitmap.Config.ARGB_8888, false)
        val labelCercano = tracks.filter { it.label !in SAFE_OBJECTS }
            .maxByOrNull { it.depthScore }?.label

        lifecycleScope.launch {
            var respuesta: String? = null

            // Intentar con Gemini solo si tenemos imagen
            if (bmpCopy != null) {
                respuesta = gemini.describirEntornoInicial(bmpCopy, labels, labelCercano)
                bmpCopy.recycle()
            }

            if (respuesta != null) {
                // Gemini respondió — usar su descripción natural
                ultimaDescGemini = respuesta
                speak(respuesta, EventPriority.CONTEXTO)
                Log.d(TAG, "GEMINI entorno inicial: $respuesta")
            } else {
                // Fallback local — la lógica original sin cambios
                val areas = tracks.map { it.box.width() * it.box.height() }
                val scene = inferScene(labels, areas)
                val veh   = labels.count { it in VEHICLES }
                val per   = labels.count { it == "person" }
                val total = labels.size

                val entorno = when (scene) {
                    SceneType.COCINA              -> "Pareces estar en una cocina."
                    SceneType.HABITACION          -> "Pareces estar en una habitación."
                    SceneType.SALA                -> "Pareces estar en una sala."
                    SceneType.BANO                -> "Pareces estar en un baño."
                    SceneType.OFICINA             -> "Pareces estar en una oficina o estudio."
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
                        if (total == 0) "No detecto objetos cercanos. El camino parece libre."
                        else "Analizando el entorno. Detecto $total objeto${if(total>1)"s" else ""}."
                }
                val masUrgente = tracks.filter { it.depthScore >= DEPTH_AVISO && it.label !in SAFE_OBJECTS }
                    .maxByOrNull { it.depthScore }
                val sufijo = if (masUrgente != null) {
                    val obj = LABEL_ES[masUrgente.label]?.short ?: masUrgente.label
                    " Hay ${LABEL_ES[masUrgente.label]?.let { "${it.art} $obj" } ?: obj} al ${masUrgente.zone}."
                } else ""

                val msg = "$entorno$sufijo"
                ultimaDescGemini = msg
                speak(msg, EventPriority.CONTEXTO)
                Log.d(TAG, "FALLBACK entorno inicial: $msg")
            }

            lastSceneTime   = System.currentTimeMillis()
            geminiRunning   = false
        }
    }

    private fun buildSceneMessage(scene: SceneType, labels: List<String>): String? {
        val veh = labels.count { it in VEHICLES }
        val per = labels.count { it == "person" }
        return when (scene) {
            SceneType.COCINA              -> "Pareces estar en una cocina."
            SceneType.HABITACION          -> "Pareces estar en una habitación."
            SceneType.SALA                -> "Pareces estar en una sala."
            SceneType.BANO                -> "Pareces estar en un baño."
            SceneType.OFICINA             -> "Pareces estar en una oficina o estudio."
            SceneType.INTERIOR_DESPEJADO  -> "Interior despejado."
            SceneType.INTERIOR_CONCURRIDO -> if (per >= 3) "Lugar con mucha gente." else "Espacio interior con objetos."
            SceneType.EXTERIOR_TRANQUILO  -> if (veh > 0) "Exterior, $veh vehículo${if(veh>1)"s" else ""} cerca." else "Exterior abierto."
            SceneType.EXTERIOR_CONCURRIDO -> "Exterior concurrido. Mantente alerta."
            SceneType.CRUCE_PELIGROSO     -> "Zona de cruce. Precaución."
            SceneType.DESCONOCIDO         -> null
        }
    }

    /**
     * Actualización periódica de escena — intenta Gemini, cae a buildSceneMessage() si falla.
     * Solo se llama cuando no hay peligro activo y el DecisionEngine está en silencio.
     * Gemini solo habla si detecta un cambio real respecto a la última descripción dada.
     */
    private fun intentarActualizarEscenaConGemini(labels: List<String>, tracks: List<ObjectTrack>, now: Long) {
        if (geminiRunning) return
        if (now - lastGeminiSceneTime < COOLDOWN_ESCENA) return
        geminiRunning = true

        val bmpCopy = latestBitmap?.copy(latestBitmap!!.config ?: Bitmap.Config.ARGB_8888, false)

        lifecycleScope.launch {
            var respondioGemini = false

            if (bmpCopy != null) {
                val respuesta = gemini.actualizarEscena(bmpCopy, labels, ultimaDescGemini)
                bmpCopy.recycle()

                if (respuesta != null) {
                    ultimaDescGemini    = respuesta
                    lastGeminiSceneTime = System.currentTimeMillis()
                    lastSceneTime       = System.currentTimeMillis()
                    // Escena cambió → limpiar objetos "ya avisados" para que se anuncien de nuevo
                    decisionEngine.resetWarnedFar()
                    speak(respuesta, EventPriority.CONTEXTO)
                    Log.d(TAG, "GEMINI escena actualizada: $respuesta")
                    respondioGemini = true
                }
            } else {
                bmpCopy?.recycle()
            }

            // Fallback local si Gemini no respondió o no hay imagen
            if (!respondioGemini) {
                val areas = tracks.map { it.box.width() * it.box.height() }
                val scene = inferScene(labels, areas)
                val msg   = buildSceneMessage(scene, labels)
                if (msg != null) {
                    ultimaDescGemini = msg
                    lastSceneTime    = System.currentTimeMillis()
                    speak(msg, EventPriority.CONTEXTO)
                    Log.d(TAG, "FALLBACK escena local: $msg")
                }
            }

            geminiRunning = false
        }
    }

    /**
     * Llama a Gemini para sugerir una salida cuando el camino está completamente bloqueado
     * y el escaneo lateral no encontró alternativas.
     * Se activa máximo una vez por escaneo, con resultado hablado al usuario.
     */
    private fun intentarSalidaConGemini(tracks: List<ObjectTrack>, now: Long) {
        if (geminiRunning) return
        geminiRunning = true

        val bmpCopy = latestBitmap?.copy(latestBitmap!!.config ?: Bitmap.Config.ARGB_8888, false)
        val labelsBloq = tracks.filter { it.depthScore >= DEPTH_CERCA }
            .map { it.label }.distinct()
        val zonas = buildString {
            if (tracks.any { it.zone == "centro"    && it.depthScore >= DEPTH_CERCA }) append("centro ")
            if (tracks.any { it.zone == "izquierda" && it.depthScore >= DEPTH_CERCA }) append("izquierda ")
            if (tracks.any { it.zone == "derecha"   && it.depthScore >= DEPTH_CERCA }) append("derecha")
        }.trim().ifEmpty { "todas" }

        lifecycleScope.launch {
            val respuesta = if (bmpCopy != null) {
                gemini.sugerirSalidaBloqueado(bmpCopy, labelsBloq, zonas).also { bmpCopy.recycle() }
            } else null

            val msg = respuesta ?: "Camino bloqueado. Intenta retroceder y buscar otra ruta."
            speak(msg, EventPriority.NAVEGACION_URGENTE)
            Log.d(TAG, "GEMINI salida bloqueado: $msg")
            geminiRunning = false
        }
    }

    private fun checkSceneChange(scene: SceneType, tracks: List<ObjectTrack>, labels: List<String>, now: Long) {
        // No anunciar si acabamos de hablar de otra cosa (evitar solapamientos)
        if (now - lastSpeakTime < 2_000L) return

        // Anunciar cambio de entorno solo si cambió significativamente y pasó al menos 8s
        val cambioSignificativo = scene != SceneType.DESCONOCIDO &&
            scene != lastSceneType &&
            now - lastSceneAnnounceTime > 8_000L

        if (cambioSignificativo) {
            val isNowExterior = scene == SceneType.EXTERIOR_TRANQUILO || scene == SceneType.EXTERIOR_CONCURRIDO
            val wasInterior   = lastSceneType in setOf(SceneType.SALA, SceneType.HABITACION,
                SceneType.COCINA, SceneType.BANO, SceneType.OFICINA, SceneType.INTERIOR_DESPEJADO,
                SceneType.INTERIOR_CONCURRIDO)
            val isNowInterior = scene in setOf(SceneType.SALA, SceneType.HABITACION,
                SceneType.COCINA, SceneType.BANO, SceneType.OFICINA, SceneType.INTERIOR_DESPEJADO,
                SceneType.INTERIOR_CONCURRIDO)

            val msg = when {
                isNowExterior -> "Pareces estar en exteriores."
                scene == SceneType.SALA       -> "Pareces estar en una sala."
                scene == SceneType.HABITACION -> "Pareces estar en una habitación."
                scene == SceneType.COCINA     -> "Pareces estar en una cocina."
                scene == SceneType.BANO       -> "Pareces estar en un baño."
                scene == SceneType.OFICINA    -> "Pareces estar en una oficina."
                isNowInterior && !wasInterior -> "Pareces estar en un lugar cerrado."
                else -> null
            }
            if (msg != null) {
                speak(msg, EventPriority.CONTEXTO)
                lastSceneAnnounceTime = now
                decisionEngine.resetWarnedFar()
            }
            lastSceneType = scene
        }

        // Guía de cruce en calle: cuando hay vehículos en exterior, ofrecer escaneo lateral
        val isExterior = scene == SceneType.EXTERIOR_TRANQUILO || scene == SceneType.EXTERIOR_CONCURRIDO
        if (isExterior && now - lastStreetGuideTime > 40_000L && !NavigationEngine.hayPeligroActivo(tracks)) {
            val hayVehiculos = tracks.any { it.label in VEHICLES }
            if (hayVehiculos) {
                speak("Vehículos detectados. Mueve el teléfono a ambos lados antes de cruzar.",
                    EventPriority.NAVEGACION_NORMAL)
                startScanMode("ambos lados")
                lastStreetGuideTime = now
            }
        }
    }

    private fun checkCrossing(tracks: List<ObjectTrack>, labels: List<String>, now: Long) {
        if (now - lastCrossTime < COOLDOWN_CRUCE) return
        val hayLuz   = labels.contains("traffic light")
        val haySenal = labels.contains("stop sign")
        if (!(hayLuz || haySenal) || labels.count { it in CROSSING_HINTS } < 2) return

        // Detectar color del semáforo si hay uno visible
        val bmp = latestBitmap
        val colorSemaforo = if (hayLuz && bmp != null) {
            val sf = tracks.firstOrNull { it.label == "traffic light" }
            if (sf != null) detectSemaforoColor(bmp, sf.box) else "?"
        } else "?"

        val msg = when (colorSemaforo) {
            "rojo"  -> "Semáforo en rojo. Espera antes de cruzar."
            "verde" -> "Semáforo en verde. Puedes cruzar, con cuidado."
            else    -> "Cruce detectado. Detente y escanea los lados."
        }
        speak(msg, EventPriority.NAVEGACION_URGENTE)
        vibrate(500L)
        if (colorSemaforo != "verde") startScanMode("la izquierda y luego la derecha")
        lastCrossTime = now
    }

    /** Analiza el color dominante dentro del bbox de un semáforo.
     *  Muestrea el tercio superior (rojo) y el tercio inferior (verde). */
    private fun detectSemaforoColor(bitmap: Bitmap, box: RectF): String {
        val bw = bitmap.width.toFloat(); val bh = bitmap.height.toFloat()
        val l = (box.left  * bw).toInt().coerceIn(0, bitmap.width  - 1)
        val t = (box.top   * bh).toInt().coerceIn(0, bitmap.height - 1)
        val r = (box.right  * bw).toInt().coerceIn(1, bitmap.width)
        val b = (box.bottom * bh).toInt().coerceIn(1, bitmap.height)
        if (r - l < 4 || b - t < 4) return "?"

        val h3 = ((b - t) / 3).coerceAtLeast(1)
        var redPx = 0; var greenPx = 0; var samples = 0

        for (y in t until (t + h3) step 2) for (x in l until r step 2) {
            val p = bitmap.getPixel(x, y)
            val rv = (p shr 16) and 0xFF; val gv = (p shr 8) and 0xFF; val bv = p and 0xFF
            if (rv > 160 && rv > gv * 1.5f && rv > bv * 1.5f) redPx++
            samples++
        }
        for (y in (b - h3) until b step 2) for (x in l until r step 2) {
            val p = bitmap.getPixel(x, y)
            val rv = (p shr 16) and 0xFF; val gv = (p shr 8) and 0xFF; val bv = p and 0xFF
            if (gv > 110 && gv > rv * 1.2f && gv > bv * 1.0f) greenPx++
        }
        return when {
            samples > 0 && redPx.toFloat()   / samples > 0.10f -> "rojo"
            greenPx > 6                                         -> "verde"
            else                                                -> "?"
        }
    }

    /** Detecta pared cercana por mapa de profundidad: zona central con alta profundidad uniforme */
    private fun detectParedAlFrente(depthMap: Array<FloatArray>?): Boolean {
        if (depthMap == null) return false
        val h = depthMap.size; val w = depthMap[0].size
        val y0 = (h * 0.25).toInt(); val y1 = (h * 0.75).toInt()
        val x0 = (w * 0.25).toInt(); val x1 = (w * 0.75).toInt()
        var sum = 0f; var count = 0
        for (y in y0 until y1 step 3) for (x in x0 until x1 step 3) { sum += depthMap[y][x]; count++ }
        if (count == 0) return false
        val avg = sum / count
        var variance = 0f
        for (y in y0 until y1 step 3) for (x in x0 until x1 step 3) {
            val d = depthMap[y][x] - avg; variance += d * d
        }
        variance /= count
        // Pared plana: alta profundidad + poca varianza
        // Esquina: profundidad EXTREMA aunque la varianza sea mayor (dos paredes distintas)
        return (avg > 0.74f && variance < 0.022f) || avg > 0.87f
    }

    /** Detecta obstáculo cercano no clasificado por YOLO (mesita, gavetero, silla ocluida…).
     *  varianza ≥ 0.008 excluye superficies planas que ya cubre detectParedAlFrente.
     *  Devuelve la profundidad promedio del centro si hay obstáculo, 0f si no. */
    private fun detectObstaculoCercanoSinYolo(depthMap: Array<FloatArray>?): Float {
        if (depthMap == null) return 0f
        val h = depthMap.size; val w = depthMap[0].size
        val y0 = (h * 0.12).toInt(); val y1 = (h * 0.82).toInt()
        val x0 = (w * 0.22).toInt(); val x1 = (w * 0.78).toInt()
        var sum = 0f; var count = 0
        for (y in y0 until y1 step 3) for (x in x0 until x1 step 3) { sum += depthMap[y][x]; count++ }
        if (count == 0) return 0f
        val avg = sum / count
        var variance = 0f
        for (y in y0 until y1 step 3) for (x in x0 until x1 step 3) {
            val d = depthMap[y][x] - avg; variance += d * d
        }
        variance /= count
        return if (variance >= 0.005f) avg else 0f
    }

    /** Detecta escalones por patrón de gradiente alternante en la franja inferior del mapa */
    private fun detectEscalonesAdelante(depthMap: Array<FloatArray>?): Boolean {
        if (depthMap == null) return false
        val h = depthMap.size; val w = depthMap[0].size
        val cx = w / 2
        val step = (h / 22).coerceAtLeast(1)
        var transitions = 0; var prevSlope = 0f
        var prevDepth = depthMap[(h * 0.50).toInt().coerceIn(0, h - 1)][cx]

        for (yi in (h * 0.50).toInt() until (h * 0.92).toInt() step step) {
            val depth = depthMap[yi.coerceIn(0, h - 1)][cx]
            val slope = depth - prevDepth
            if (abs(slope) > 0.040f && slope * prevSlope < 0f) transitions++
            prevSlope = slope; prevDepth = depth
        }
        return transitions >= 2
    }

    // ── Vibración ─────────────────────────────────────────────────────────────
    private fun vibrate(ms: Long) {
        if (!userPrefs.vibracionActivada) return
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

    private fun requestGpsPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                11
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            10 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startCamera()
                    requestGpsPermissionIfNeeded()
                } else finish()
            }
            11 -> Unit  // GPS es opcional — SessionManager verifica el permiso al usarlo
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionManager.stopSession()
        if (::tts.isInitialized) tts.shutdown()
        yoloDetector.close(); depthEstimator?.close()
        cameraExecutor.shutdown(); depthExecutor.shutdown()
        trackManager.clear()
        latestBitmap?.recycle(); latestBitmap = null
    }
}