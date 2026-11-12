package com.aavante.rgbstudio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlin.math.*
import kotlin.random.Random

// ---------- Bluetooth layer ----------
// NOTE: The boAt Aavante Bar 950 has NO documented lighting control, so this app
// refuses to send anything to devices named "Aavante"/"boAt". It controls
// separate BLE RGB LED strips (ELK-BLEDOM style protocol).

@SuppressLint("MissingPermission")
class Strip(private val ctx: Context) {
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    val seen = mutableStateMapOf<String, Pair<String, Int>>()
    var status by mutableStateOf("Not connected")
    var connected by mutableStateOf(false)
    var scanning by mutableStateOf(false)
    private var gatt: BluetoothGatt? = null
    private var ch: BluetoothGattCharacteristic? = null

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val n = r.device.name ?: r.scanRecord?.deviceName ?: return
            if (r.isConnectable) seen[r.device.address] = n to r.rssi
        }
        override fun onScanFailed(code: Int) { status = "Scan failed ($code)"; scanning = false }
    }

    fun scan() {
        val sc = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || sc == null) { status = "Turn Bluetooth on"; return }
        seen.clear(); sc.startScan(scanCb); scanning = true; status = "Scanning..."
    }
    fun stopScan() { adapter?.bluetoothLeScanner?.stopScan(scanCb); scanning = false }

    fun connect(addr: String, name: String) {
        if (name.contains("aavante", true) || name.contains("boat", true)) {
            status = "Blocked: the soundbar has no verified RGB control. Pick an LED strip."
            return
        }
        stopScan(); status = "Connecting to $name..."
        gatt?.close()
        gatt = adapter?.getRemoteDevice(addr)?.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() { gatt?.disconnect(); gatt?.close(); gatt = null; ch = null; connected = false; status = "Not connected" }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, ns: Int) {
            if (ns == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
            else { connected = false; ch = null; status = "Disconnected" }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val writable = g.services.flatMap { it.characteristics }.filter {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            }
            ch = writable.firstOrNull { it.uuid.toString().startsWith("0000fff3") }
                ?: writable.firstOrNull { it.uuid.toString().startsWith("0000ffe1") }
            if (ch == null) { status = "No supported LED characteristic found on this device"; g.disconnect() }
            else { connected = true; status = "Connected" }
        }
    }

    @Suppress("DEPRECATION")
    fun sendRgb(r: Int, gr: Int, b: Int) {
        val gt = gatt ?: return
        val c = ch ?: return
        val data = byteArrayOf(0x7E, 0x07, 0x05, 0x03, r.toByte(), gr.toByte(), b.toByte(), 0x10, 0xEF.toByte())
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (Build.VERSION.SDK_INT >= 33) gt.writeCharacteristic(c, data, type)
        else { c.writeType = type; c.value = data; gt.writeCharacteristic(c) }
    }

    fun sendHsv(h: Float, s: Float, v: Float) {
        val c = Color.hsv(h.mod(360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
        sendRgb((c.red * 255).roundToInt(), (c.green * 255).roundToInt(), (c.blue * 255).roundToInt())
    }
}

// ---------- UI ----------
enum class Fx(val label: String) {
    STATIC("Static"), RAINBOW("Rainbow"), BREATH("Breathing"), FLICKER("Flicker"), MUSIC("Music")
}

val Bg = Color(0xFF0B0B14); val Panel = Color(0xFF151526)
val Cyan = Color(0xFF00F0FF); val Pink = Color(0xFFFF2BD6)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, secondary = Pink, background = Bg, surface = Bg)) {
                Surface(Modifier.fillMaxSize(), color = Bg) { App() }
            }
        }
    }
}

fun pickFrom(o: Offset, size: IntSize, onPick: (Float, Float) -> Unit) {
    val d = o - Offset(size.width / 2f, size.height / 2f)
    val s = (d.getDistance() / (size.width / 2f)).coerceIn(0f, 1f)
    var a = Math.toDegrees(atan2(d.y.toDouble(), d.x.toDouble())).toFloat()
    if (a < 0) a += 360f
    onPick(a, s)
}

@Composable
fun Wheel(hue: Float, sat: Float, onPick: (Float, Float) -> Unit) {
    Canvas(
        Modifier.size(280.dp)
            .pointerInput(Unit) { detectTapGestures { pickFrom(it, size, onPick) } }
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { pickFrom(it, size, onPick) }) { ch, _ -> pickFrom(ch.position, size, onPick) }
            }
    ) {
        val r = size.minDimension / 2f
        drawCircle(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)), r)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), radius = r), r)
        val a = Math.toRadians(hue.toDouble())
        val p = center + Offset((cos(a) * sat * r).toFloat(), (sin(a) * sat * r).toFloat())
        drawCircle(Color.Black, 15f, p); drawCircle(Color.White, 11f, p)
    }
}

@Composable
fun Card2(title: String, accent: Color = Cyan, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp))
            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) { Text(title, color = accent, fontSize = 13.sp); content() }
}

@SuppressLint("MissingPermission")
@Composable
fun App() {
    val ctx = LocalContext.current
    val st = remember { Strip(ctx.applicationContext) }
    var hue by remember { mutableStateOf(190f) }
    var sat by remember { mutableStateOf(1f) }
    var bright by remember { mutableStateOf(0.8f) }
    var speed by remember { mutableStateOf(0.5f) }
    var fx by remember { mutableStateOf(Fx.STATIC) }
    var hex by remember { mutableStateOf("") }
    val prefs = remember { ctx.getSharedPreferences("presets", 0) }
    var presets by remember { mutableStateOf(prefs.getString("p", "")!!.split("\n").filter { it.isNotBlank() }) }
    val scope = rememberCoroutineScope()

    val btPerms = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.values.all { ok -> ok }) st.scan() else st.status = "Bluetooth permission denied"
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) fx = Fx.MUSIC else st.status = "Microphone permission denied"
    }

    LaunchedEffect(hue, sat) { hex = "%06X".format(Color.hsv(hue, sat, 1f).toArgb() and 0xFFFFFF) }

    // Effect engine: streams real colour commands to the connected strip.
    LaunchedEffect(fx, hue, sat, bright, speed, st.connected) {
        if (!st.connected) return@LaunchedEffect
        when (fx) {
            Fx.STATIC -> st.sendHsv(hue, sat, bright)
            Fx.RAINBOW -> { var h = hue; while (true) { st.sendHsv(h, 1f, bright); h = (h + 2f + speed * 10f) % 360f; delay(80) } }
            Fx.BREATH -> { var t = 0f; while (true) { st.sendHsv(hue, sat, bright * (0.05f + 0.95f * (sin(t) + 1f) / 2f)); t += 0.05f + speed * 0.3f; delay(60) } }
            Fx.FLICKER -> while (true) { st.sendHsv(hue, sat, bright * Random.nextFloat().coerceIn(0.15f, 1f)); delay((200 - speed * 150).toLong()) }
            Fx.MUSIC -> {
                if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@LaunchedEffect
                val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val rec = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, 2048))
                val buf = ShortArray(1024)
                rec.startRecording()
                try {
                    while (isActive) {
                        val n = withContext(Dispatchers.IO) { rec.read(buf, 0, buf.size) }
                        if (n > 0) {
                            var sum = 0.0; for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                            val level = (sqrt(sum / n) / 6000.0 * (0.5 + speed)).toFloat().coerceIn(0.05f, 1f)
                            st.sendHsv(hue, sat, bright * level)
                        }
                    }
                } finally { rec.stop(); rec.release() }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AAVANTE RGB STUDIO", color = Cyan, fontSize = 22.sp)

        Card2("CONNECTION", Pink) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(if (st.connected) Color.Green else Color.Red, CircleShape))
                Spacer(Modifier.width(8.dp)); Text(st.status, fontSize = 13.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { btLauncher.launch(btPerms) }, enabled = !st.scanning) { Text("Scan") }
                OutlinedButton(onClick = { st.disconnect() }, enabled = st.connected) { Text("Disconnect") }
                OutlinedButton(onClick = { scope.launch { st.sendRgb(255, 0, 0) } }, enabled = st.connected) { Text("Test red") }
            }
            if (!st.connected) st.seen.entries.sortedByDescending { it.value.second }.forEach { (addr, v) ->
                TextButton(onClick = { st.connect(addr, v.first) }) { Text("${v.first}  (${v.second} dBm)") }
            }
        }

        Card2("COLOUR") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Wheel(hue, sat) { h, s -> hue = h; sat = s } }
            OutlinedTextField(hex, { v ->
                val c = v.uppercase().filter { it in "0123456789ABCDEF" }.take(6); hex = c
                if (c.length == 6) {
                    val a = FloatArray(3); android.graphics.Color.colorToHSV(android.graphics.Color.parseColor("#$c"), a)
                    hue = a[0]; sat = a[1]
                }
            }, label = { Text("HEX") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }

        Card2("EFFECT", Pink) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Fx.values().toList()) { f ->
                    FilterChip(selected = fx == f, onClick = {
                        if (f == Fx.MUSIC && ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO) else fx = f
                    }, label = { Text(f.label) })
                }
            }
            Text("Brightness", fontSize = 12.sp); Slider(bright, { bright = it }, valueRange = 0.02f..1f)
            Text("Speed / sensitivity", fontSize = 12.sp); Slider(speed, { speed = it }, valueRange = 0.05f..1f)
        }

        Card2("PRESETS") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    presets = presets + "$hue,$sat,$bright,$speed,${fx.ordinal}"
                    prefs.edit().putString("p", presets.joinToString("\n")).apply()
                }) { Text("Save current") }
                OutlinedButton(onClick = { presets = emptyList(); prefs.edit().clear().apply() }) { Text("Clear all") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(presets.withIndex().toList()) { (i, p) ->
                    AssistChip(onClick = {
                        val x = p.split(","); hue = x[0].toFloat(); sat = x[1].toFloat(); bright = x[2].toFloat()
                        speed = x[3].toFloat(); fx = Fx.values()[x[4].toInt().coerceIn(0, Fx.values().size - 1)]
                    }, label = { Text("Preset ${i + 1}") })
                }
            }
        }
        Text("Works with ELK-BLEDOM-style BLE LED strips. It will not send anything to the boAt soundbar.", fontSize = 11.sp)
    }
}
