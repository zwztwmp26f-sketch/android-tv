package app.liquidtv.launcher

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Mode { Idle, Bar, Settings }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Launcher(this) }
    }
}

fun Modifier.glass(r: Dp): Modifier {
    val shape = RoundedCornerShape(r)
    return this.clip(shape)
        .background(Brush.verticalGradient(listOf(Color(0x4DFFFFFF), Color(0x1FFFFFFF))))
        .border(1.dp, Brush.verticalGradient(listOf(Color(0xAAFFFFFF), Color(0x22FFFFFF))), shape)
}

@Composable
fun T(s: String, size: Float, w: FontWeight = FontWeight.Normal, a: Float = 1f, m: Modifier = Modifier) =
    BasicText(s, m, style = TextStyle(
        color = Color.White.copy(alpha = a), fontSize = size.sp, fontWeight = w,
        shadow = Shadow(Color(0x55000000), Offset(0f, 2f), 12f)))

@Composable
fun Launcher(ctx: Context) {
    val prefs = remember { Prefs(ctx) }
    var mode by remember { mutableStateOf(Mode.Idle) }
    var sel by remember { mutableStateOf(0) }
    var pinned by remember { mutableStateOf(prefs.apps) }
    var hide by remember { mutableStateOf(prefs.hideSec) }
    var cs by remember { mutableStateOf(prefs.clockScale) }
    var auto by remember { mutableStateOf(prefs.autoMin) }
    var wall by remember { mutableStateOf(prefs.wall) }
    var ping by remember { mutableStateOf(0) }
    var now by remember { mutableStateOf(Date()) }
    var wx by remember { mutableStateOf("…") }
    val files = remember(mode) { wallFiles(ctx) }
    val total = BUILTIN.size + files.size
    val fr = remember { FocusRequester() }
    val n = pinned.size + 1

    LaunchedEffect(Unit) { while (true) { now = Date(); delay(5000) } }
    LaunchedEffect(Unit) {
        while (true) {
            val r = withContext(Dispatchers.IO) { weather(prefs.lat, prefs.lon) }
            if (r != null) { wx = r; delay(1_800_000) }
            else { if (wx == "…") wx = "нет данных"; delay(60_000) }
        }
    }
    LaunchedEffect(auto, total) {
        if (auto > 0) while (true) { delay(auto * 60_000L); wall = (wall + 1) % total; prefs.wall = wall }
    }
    LaunchedEffect(mode, ping, hide) { if (mode == Mode.Bar) { delay(hide * 1000L); mode = Mode.Idle } }
    LaunchedEffect(mode) { if (mode != Mode.Settings) runCatching { fr.requestFocus() } }
    BackHandler { if (mode != Mode.Idle) mode = Mode.Idle }

    val time = remember(now) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(now) }
    val date = remember(now) {
        SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(now).replaceFirstChar { it.uppercase() }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(fr).focusable()
            .onPreviewKeyEvent { e ->
                if (mode == Mode.Settings || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (mode == Mode.Idle) { sel = 0; mode = Mode.Bar }
                        else { sel = (sel + if (e.key == Key.DirectionRight) 1 else -1).coerceIn(0, n - 1); ping++ }
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (mode == Mode.Idle) { sel = 0; mode = Mode.Bar }
                        else if (sel < pinned.size) openApp(ctx, pinned[sel])
                        else mode = Mode.Settings
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (mode == Mode.Idle) { sel = 0; mode = Mode.Bar }
                        else {
                            wall = (wall + if (e.key == Key.DirectionDown) 1 else total - 1) % total
                            prefs.wall = wall
                            ping++
                        }
                        true
                    }
                    else -> false
                }
            }
    ) {
        Crossfade(wall % total, animationSpec = tween(1500), label = "wall") { i -> Wallpaper(i, files) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(Color(0x47000000), Color.Transparent, Color.Transparent, Color(0x59000000)))))

        Column(Modifier.align(Alignment.TopCenter).padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            T(date, 26f * cs, FontWeight.Medium, 0.85f)
            Clock(time, cs)
            Box(Modifier.padding(top = 8.dp).glass(24.dp).padding(horizontal = 20.dp, vertical = 8.dp)) { T(wx, 22f) }
        }

        AnimatedVisibility(
            mode == Mode.Bar, Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp),
            enter = fadeIn(tween(400)) + slideInVertically(spring(0.7f, 200f)) { it / 2 },
            exit = fadeOut(tween(500)) + slideOutVertically(tween(500)) { it / 3 }
        ) {
            val items = pinned + ""
            val barW = minOf(132.dp * items.size + 48.dp, LocalConfiguration.current.screenWidthDp.dp * 0.92f)
            val visible = ((barW.value - 48f) / 132f).toInt().coerceAtLeast(1)
            val ls = rememberLazyListState()
            LaunchedEffect(sel, mode) { if (mode == Mode.Bar) ls.animateScrollToItem((sel - visible / 2).coerceAtLeast(0)) }
            LazyRow(
                modifier = Modifier.width(barW).glass(36.dp), state = ls,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) { itemsIndexed(items) { i, p -> BarItem(ctx, p, i == sel) } }
        }

        if (mode == Mode.Settings) Settings(
            ctx, pinned, { pinned = it; prefs.apps = it; sel = sel.coerceAtMost(it.size) },
            hide, { hide = it; prefs.hideSec = it }, cs, { cs = it; prefs.clockScale = it },
            auto, { auto = it; prefs.autoMin = it }, wall, { wall = it; prefs.wall = it }, total
        ) { mode = Mode.Idle }
    }
}

@Composable
fun Clock(time: String, cs: Float) {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { delay(1000); on = !on } }
    val a by animateFloatAsState(if (on) 1f else 0.2f, tween(450), label = "colon")
    val parts = time.split(":")
    val st = TextStyle(
        color = Color.White.copy(alpha = 0.95f), fontSize = (150f * cs).sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-4).sp, fontFeatureSettings = "tnum",
        shadow = Shadow(Color(0x55000000), Offset(0f, 4f), 24f))
    Row(Modifier.padding(vertical = (22f * cs).dp).graphicsLayer(scaleY = 1.22f), verticalAlignment = Alignment.CenterVertically) {
        BasicText(parts[0], style = st)
        BasicText(":", Modifier.alpha(a).offset(y = (-8f * cs).dp), style = st)
        BasicText(parts.getOrElse(1) { "00" }, style = st)
    }
}

@Composable
fun Wallpaper(i: Int, files: List<File>) {
    if (i < BUILTIN.size) Box(Modifier.fillMaxSize().background(BUILTIN[i]))
    else {
        val f = files.getOrNull(i - BUILTIN.size) ?: return
        val bmp by produceState<ImageBitmap?>(null, f) { value = withContext(Dispatchers.IO) { decode(f) } }
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
fun BarItem(ctx: Context, pkg: String, sel: Boolean) {
    val s by animateFloatAsState(if (sel) 1.2f else 0.85f, spring(0.5f, 260f), label = "s")
    val a by animateFloatAsState(if (sel) 1f else 0.65f, label = "a")
    Column(Modifier.width(112.dp).scale(s).alpha(a), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(104.dp).then(if (sel) Modifier.glass(26.dp) else Modifier), contentAlignment = Alignment.Center) {
            if (pkg.isEmpty()) T("⚙", 44f)
            else remember(pkg) { appIcon(ctx, pkg) }?.let { Image(it, null, Modifier.size(84.dp)) }
        }
        Spacer(Modifier.height(8.dp))
        T(if (pkg.isEmpty()) "Настройки" else appLabel(ctx, pkg), 18f, a = if (sel) 1f else 0f)
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

@Composable
fun Settings(
    ctx: Context, pinned: List<String>, setPinned: (List<String>) -> Unit,
    hide: Int, setHide: (Int) -> Unit, cs: Float, setCs: (Float) -> Unit,
    auto: Int, setAuto: (Int) -> Unit, wall: Int, setWall: (Int) -> Unit, total: Int, onClose: () -> Unit
) {
    val all = remember { installed(ctx) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).padding(48.dp)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { T("Настройки лаунчера", 36f, FontWeight.Bold) }
            item { Item("Скрывать панель через: $hide с", { setHide(cycle(listOf(5, 8, 10, 15), hide)) }, Modifier.focusRequester(first)) }
            item { Item("Размер часов: ${(cs * 100).toInt()}%", { setCs(cycle(listOf(0.8f, 1f, 1.2f), cs)) }) }
            item { Item("Смена обоев: " + (if (auto == 0) "выкл" else "каждые $auto мин"), { setAuto(cycle(listOf(0, 1, 2, 5, 15), auto)) }) }
            item { Item("Обои: ${wall % total + 1} из $total (свои фото: Android/data/app.liquidtv.launcher/files/wallpapers)", { setWall((wall + 1) % total) }) }
            item { T("На панели", 24f, a = 0.7f) }
            itemsIndexed(pinned) { i, p ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(appLabel(ctx, p), 22f, m = Modifier.width(320.dp))
                    Item("Выше", { setPinned(pinned.swap(i, i - 1)) })
                    Item("Ниже", { setPinned(pinned.swap(i, i + 1)) })
                    Item("Убрать", { setPinned(pinned - p) })
                }
            }
            item { T("Доступные приложения (удаление с панели не удаляет приложение)", 24f, a = 0.7f) }
            items(all.filter { it.pkg !in pinned }) { a -> Item("+ ${a.label}", { setPinned(pinned + a.pkg) }) }
            item { Item("Готово", onClose) }
        }
    }
}
