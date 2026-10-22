package app.liquidtv.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

enum class Mode { Idle, Bar, Settings }

val PITCH = 112.dp // ширина значка 96 + промежуток 16

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Launcher(this) }
    }
}

class Cfg(private val p: Prefs) {
    var pinned by mutableStateOf(p.apps)
    var hide by mutableStateOf(p.hideSec)
    var cs by mutableStateOf(p.clockScale)
    var auto by mutableStateOf(p.autoMin)
    var wall by mutableStateOf(p.wall)
    var blur by mutableStateOf(p.blur)
    var hdmi by mutableStateOf(p.hdmi)
    var autoInk by mutableStateOf(p.autoInk)
    var dimMin by mutableStateOf(p.dimMin)
    var night by mutableStateOf(p.night)
    fun save() {
        p.apps = pinned; p.hideSec = hide; p.clockScale = cs; p.wall = wall; p.autoMin = auto
        p.blur = blur; p.hdmi = hdmi; p.autoInk = autoInk; p.dimMin = dimMin; p.night = night
    }
}

val NIGHT = listOf<Pair<Int, Int>?>(null, 22 to 7, 23 to 7, 0 to 8)
fun nightText(i: Int) = NIGHT.getOrNull(i)?.let { "%02d:00–%02d:00".format(it.first, it.second) } ?: "выкл"
fun isNight(i: Int, d: Date): Boolean {
    val r = NIGHT.getOrNull(i) ?: return false
    val h = Calendar.getInstance().apply { time = d }.get(Calendar.HOUR_OF_DAY)
    return if (r.first > r.second) h >= r.first || h < r.second else h >= r.first && h < r.second
}

fun Modifier.glass(r: Dp): Modifier {
    val shape = RoundedCornerShape(r)
    return this.clip(shape)
        .background(Brush.verticalGradient(listOf(Color(0x4DFFFFFF), Color(0x1FFFFFFF))))
        .border(1.dp, Brush.verticalGradient(listOf(Color(0xAAFFFFFF), Color(0x22FFFFFF))), shape)
}

@Composable
fun T(s: String, size: Float, w: FontWeight = FontWeight.Normal, a: Float = 1f, m: Modifier = Modifier, c: Color = Color.White) =
    BasicText(s, m, style = TextStyle(
        color = c.copy(alpha = a), fontSize = size.sp, fontWeight = w,
        shadow = if (c.red > 0.5f) Shadow(Color(0x55000000), Offset(0f, 2f), 12f) else null))

@Composable
fun Launcher(ctx: Context) {
    val prefs = remember { Prefs(ctx) }
    val cfg = remember { Cfg(prefs) }
    var mode by remember { mutableStateOf(Mode.Idle) }
    var sel by remember { mutableStateOf(0) }
    var edit by remember { mutableStateOf(false) }
    var ping by remember { mutableStateOf(0) }
    var act by remember { mutableStateOf(0) }
    var dimmed by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    var permTick by remember { mutableStateOf(0) }
    var now by remember { mutableStateOf(Date()) }
    var wx by remember { mutableStateOf("…") }
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }
    val files = remember(mode, permTick) { wallFiles(ctx) }
    val total = files.size
    val cur = if (total == 0) -1 else cfg.wall % total
    val tiles = remember(cfg.hdmi, mode) { if (cfg.hdmi) tvInputs(ctx) else emptyList() }
    val items = cfg.pinned + tiles.map { "tv:" + it.first } + ""
    val n = items.size
    val night = isNight(cfg.night, now)
    val fr = remember { FocusRequester() }
    val sw = LocalConfiguration.current.screenWidthDp.dp
    val sh = LocalConfiguration.current.screenHeightDp.dp

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(ctx, perm) != PackageManager.PERMISSION_GRANTED) permLauncher.launch(perm)
    }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(5000) } }
    LaunchedEffect(Unit) {
        while (true) {
            val r = withContext(Dispatchers.IO) { weather(prefs.lat, prefs.lon) }
            if (r != null) { wx = r; delay(1_800_000) }
            else { if (wx == "…") wx = "нет данных"; delay(60_000) }
        }
    }
    LaunchedEffect(cfg.auto, total, night) {
        if (cfg.auto > 0 && total > 1 && !night) while (true) {
            delay(cfg.auto * 60_000L); cfg.wall = (cfg.wall + 1) % total; cfg.save()
        }
    }
    LaunchedEffect(mode, ping, cfg.hide, edit) {
        if (mode == Mode.Bar && !edit) { delay(cfg.hide * 1000L); mode = Mode.Idle }
    }
    LaunchedEffect(mode) { if (mode != Mode.Bar) edit = false }
    LaunchedEffect(act, cfg.dimMin) {
        dimmed = false
        if (cfg.dimMin > 0) { delay(cfg.dimMin * 60_000L); dimmed = true }
    }
    LaunchedEffect(mode) { if (mode != Mode.Settings) runCatching { fr.requestFocus() } }
    BackHandler { if (edit) edit = false else if (mode != Mode.Idle) mode = Mode.Idle }

    val lum by produceState(0f, cur, files) {
        value = files.getOrNull(cur)?.let { f -> withContext(Dispatchers.IO) { lumOf(f) } } ?: 0f
    }
    val inkTarget = if (cfg.autoInk && lum > 0.62f) Color(0xFF1C1C1E) else Color.White
    val ink by animateColorAsState(inkTarget, tween(1200), label = "ink")
    val dimA by animateFloatAsState(
        maxOf(if (dimmed) 0.78f else 0f, if (night) 0.45f else 0f), tween(2500), label = "dim")

    val time = remember(now) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(now) }
    val date = remember(now) {
        SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(now).replaceFirstChar { it.uppercase() }
    }

    fun okPressed() {
        if (mode == Mode.Idle) { sel = 0; mode = Mode.Bar; return }
        if (edit) { edit = false; ping++; return }
        val k = items.getOrNull(sel) ?: return
        when {
            k.isEmpty() -> mode = Mode.Settings
            k.startsWith("tv:") -> openInput(ctx, k.substring(3))
            else -> openApp(ctx, k)
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(fr).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) act++
                if (mode == Mode.Settings) return@onPreviewKeyEvent false
                val k = e.key
                if (k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter) {
                    if (e.type == KeyEventType.KeyDown) {
                        if (e.nativeKeyEvent.repeatCount >= 1 && !longFired) {
                            longFired = true
                            if (mode == Mode.Bar && sel < cfg.pinned.size) { edit = true; ping++ }
                        }
                    } else if (e.type == KeyEventType.KeyUp) {
                        if (!longFired) okPressed()
                        longFired = false
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (k) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        val d = if (k == Key.DirectionRight) 1 else -1
                        if (mode == Mode.Idle) { sel = 0; mode = Mode.Bar }
                        else if (edit) {
                            val j = sel + d
                            if (j in cfg.pinned.indices) { cfg.pinned = cfg.pinned.swap(sel, j); cfg.save(); sel = j }
                            ping++
                        } else { sel = (sel + d).coerceIn(0, n - 1); ping++ }
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (mode == Mode.Idle) { sel = 0; mode = Mode.Bar }
                        else {
                            if (total > 0) {
                                cfg.wall = (cfg.wall + if (k == Key.DirectionDown) 1 else total - 1) % total
                                cfg.save()
                            }
                            ping++
                        }
                        true
                    }
                    Key.Menu -> { mode = Mode.Settings; true }
                    else -> false
                }
            }
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp))) {
            Crossfade(cur, animationSpec = tween(1500), label = "wall") { i -> Wallpaper(i, files) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                listOf(Color(0x47000000), Color.Transparent, Color.Transparent, Color(0x59000000)))))

            Column(
                Modifier.align(Alignment.TopCenter).padding(top = 32.dp).alpha(if (night) 0.6f else 1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                T(date, 26f * cfg.cs, FontWeight.Medium, 0.85f, c = ink)
                Clock(time, cfg.cs, ink)
                Box(Modifier.padding(top = 8.dp).glass(24.dp).padding(horizontal = 20.dp, vertical = 8.dp)) { T(wx, 22f, c = ink) }
            }

            if (total == 0 && mode == Mode.Idle)
                T("Нет обоев: положите фото в Pictures/Wallpapers (память ТВ или флешка)", 16f, a = 0.5f,
                    m = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp))

            AnimatedVisibility(
                mode == Mode.Bar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                enter = fadeIn(tween(400)) + slideInVertically(spring(0.7f, 200f)) { it / 2 },
                exit = fadeOut(tween(500)) + slideOutVertically(tween(500)) { it / 3 }
            ) {
                val barW = minOf(PITCH * n + 40.dp, sw * 0.92f)
                val visible = ((barW.value - 40f) / 112f).toInt().coerceAtLeast(1)
                val ls = rememberLazyListState()
                LaunchedEffect(sel, mode) { if (mode == Mode.Bar) ls.animateScrollToItem((sel - visible / 2).coerceAtLeast(0)) }
                var pos by remember { mutableStateOf(Offset.Zero) }
                val shape = RoundedCornerShape(32.dp)
                val blurOn = cfg.blur && Build.VERSION.SDK_INT >= 31
                Box(Modifier.width(barW).onGloballyPositioned { pos = it.positionInRoot() }.clip(shape)) {
                    if (blurOn) Box(
                        Modifier.matchParentSize().wrapContentSize(Alignment.TopStart, unbounded = true)
                            .size(sw, sh).offset { IntOffset(-pos.x.roundToInt(), -pos.y.roundToInt()) }
                            .blur(30.dp)
                    ) { Wallpaper(cur, files) }
                    Box(Modifier.matchParentSize()
                        .background(Brush.verticalGradient(
                            if (blurOn) listOf(Color(0x33FFFFFF), Color(0x14FFFFFF)) else listOf(Color(0x55FFFFFF), Color(0x26FFFFFF))))
                        .border(1.dp, Brush.verticalGradient(listOf(Color(0xAAFFFFFF), Color(0x22FFFFFF))), shape))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(), state = ls,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(items) { i, k ->
                            BarItem(ctx, k, tiles.firstOrNull { "tv:" + it.first == k }?.second, i == sel, edit)
                        }
                    }
                }
            }

            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dimA)))

            if (mode == Mode.Settings) Settings(ctx, cfg, total) { mode = Mode.Idle }
        }
    }
}

@Composable
fun Clock(time: String, cs: Float, ink: Color) {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { delay(1000); on = !on } }
    val a by animateFloatAsState(if (on) 1f else 0.2f, tween(450), label = "colon")
    val parts = time.split(":")
    val st = TextStyle(
        color = ink.copy(alpha = 0.95f), fontSize = (150f * cs).sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-4).sp, fontFeatureSettings = "tnum",
        shadow = if (ink.red > 0.5f) Shadow(Color(0x55000000), Offset(0f, 4f), 24f) else null)
    Row(
        Modifier.padding(vertical = (22f * cs).dp).graphicsLayer(scaleY = 1.22f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(parts[0], style = st)
        BasicText(":", Modifier.alpha(a).offset(y = (-8f * cs).dp), style = st)
        BasicText(parts.getOrElse(1) { "00" }, style = st)
    }
}

@Composable
fun Wallpaper(i: Int, files: List<File>) {
    val f = files.getOrNull(i)
    if (f == null) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B0F1E), Color(0xFF1B2140)))))
        return
    }
    val bmp by produceState<ImageBitmap?>(null, f) { value = withContext(Dispatchers.IO) { decodeCached(f) } }
    bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
}

@Composable
fun BarItem(ctx: Context, key: String, tvLabel: String?, sel: Boolean, edit: Boolean) {
    val s by animateFloatAsState(if (sel) 1.18f else 0.86f, spring(0.5f, 260f), label = "s")
    val a by animateFloatAsState(if (sel) 1f else 0.65f, label = "a")
    val label = remember(key) {
        when {
            key.isEmpty() -> "Настройки"
            key.startsWith("tv:") -> tvLabel ?: "HDMI"
            else -> appLabel(ctx, key)
        }
    }
    Column(Modifier.width(96.dp).scale(s).alpha(a), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(90.dp).then(if (sel) Modifier.glass(24.dp) else Modifier), contentAlignment = Alignment.Center) {
            when {
                key.isEmpty() -> T("⚙", 38f)
                key.startsWith("tv:") -> T("HDMI", 18f, FontWeight.Bold)
                else -> remember(key) { appIcon(ctx, key) }?.let { Image(it, null, Modifier.size(72.dp)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        T(if (sel && edit) "◀  ▶" else label, 16f, a = if (sel) 1f else 0f)
    }
}

@Composable
fun Item(text: String, onClick: () -> Unit, m: Modifier = Modifier) {
    var f by remember { mutableStateOf(false) }
    Box(m.onFocusChanged { f = it.isFocused }.clip(RoundedCornerShape(14.dp))
        .background(if (f) Color(0x66FFFFFF) else Color(0x1AFFFFFF))
        .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp)) { T(text, 22f) }
}

fun <X> cycle(l: List<X>, cur: X): X = l[(l.indexOf(cur) + 1) % l.size]
fun <X> List<X>.swap(i: Int, j: Int): List<X> =
    if (j !in indices) this else toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }
fun yn(b: Boolean) = if (b) "вкл" else "выкл"

@Composable
fun Settings(ctx: Context, cfg: Cfg, total: Int, onClose: () -> Unit) {
    val all = remember { installed(ctx) }
    var q by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).padding(48.dp)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { T("Настройки лаунчера", 36f, FontWeight.Bold) }
            item { Item("Скрывать панель через: ${cfg.hide} с", { cfg.hide = cycle(listOf(5, 8, 10, 15), cfg.hide); cfg.save() }, Modifier.focusRequester(first)) }
            item { Item("Размытие стекла (Android 12+): ${yn(cfg.blur)}", { cfg.blur = !cfg.blur; cfg.save() }) }
            item { Item("Плитки источников (HDMI): ${yn(cfg.hdmi)}", { cfg.hdmi = !cfg.hdmi; cfg.save() }) }
            item { Item("Размер часов: ${(cfg.cs * 100).toInt()}%", { cfg.cs = cycle(listOf(0.8f, 1f, 1.2f), cfg.cs); cfg.save() }) }
            item { Item("Цвет часов под обои: ${if (cfg.autoInk) "авто" else "всегда белый"}", { cfg.autoInk = !cfg.autoInk; cfg.save() }) }
            item { Item("Затемнение при бездействии: " + (if (cfg.dimMin == 0) "выкл" else "через ${cfg.dimMin} мин"), { cfg.dimMin = cycle(listOf(0, 10, 30, 60), cfg.dimMin); cfg.save() }) }
            item { Item("Ночной режим: ${nightText(cfg.night)}", { cfg.night = (cfg.night + 1) % NIGHT.size; cfg.save() }) }
            item { Item("Смена обоев: " + (if (cfg.auto == 0) "выкл" else "каждые ${cfg.auto} мин"), { cfg.auto = cycle(listOf(0, 1, 2, 5, 15), cfg.auto); cfg.save() }) }
            item { Item("Обои: найдено $total (OK — следующие). Папка Pictures/Wallpapers или Wallpapers на флешке", { if (total > 0) { cfg.wall = (cfg.wall + 1) % total; cfg.save() } }) }
            item { T("На панели (на самой панели: долгое OK — перестановка, Меню — настройки)", 22f, a = 0.7f) }
            itemsIndexed(cfg.pinned) { i, p ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(appLabel(ctx, p), 22f, m = Modifier.width(320.dp))
                    Item("Выше", { cfg.pinned = cfg.pinned.swap(i, i - 1); cfg.save() })
                    Item("Ниже", { cfg.pinned = cfg.pinned.swap(i, i + 1); cfg.save() })
                    Item("Убрать", { cfg.pinned = cfg.pinned - p; cfg.save() })
                }
            }
            item { T("Добавить приложение (удаление с панели не удаляет его с ТВ)", 22f, a = 0.7f) }
            item {
                Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x1AFFFFFF)).padding(horizontal = 20.dp, vertical = 12.dp)) {
                    BasicTextField(
                        q, { q = it }, singleLine = true, modifier = Modifier.width(420.dp),
                        textStyle = TextStyle(color = Color.White, fontSize = 22.sp),
                        cursorBrush = SolidColor(Color.White),
                        decorationBox = { inner -> if (q.isEmpty()) T("Поиск: нажмите OK и введите название", 22f, a = 0.5f); inner() })
                }
            }
            items(all.filter { it.pkg !in cfg.pinned && it.label.contains(q, true) }) { a ->
                Item("+ ${a.label}", { cfg.pinned = cfg.pinned + a.pkg; cfg.save() })
            }
            item { Item("Готово", onClose) }
        }
    }
}
