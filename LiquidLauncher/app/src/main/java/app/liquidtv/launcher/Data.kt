package app.liquidtv.launcher

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.os.Environment
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    var apps: List<String>
        get() = sp.getString("apps", "")!!.split(",").filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("apps", v.joinToString(",")).apply()
    var hideSec: Int
        get() = sp.getInt("hide", 8)
        set(v) = sp.edit().putInt("hide", v).apply()
    var clockScale: Float
        get() = sp.getFloat("cs", 1f)
        set(v) = sp.edit().putFloat("cs", v).apply()
    var wall: Int
        get() = sp.getInt("wall", 0)
        set(v) = sp.edit().putInt("wall", v).apply()
    var autoMin: Int
        get() = sp.getInt("auto", 2)
        set(v) = sp.edit().putInt("auto", v).apply()
    var blur: Boolean
        get() = sp.getBoolean("blur", true)
        set(v) = sp.edit().putBoolean("blur", v).apply()
    var hdmi: Boolean
        get() = sp.getBoolean("hdmi", true)
        set(v) = sp.edit().putBoolean("hdmi", v).apply()
    var autoInk: Boolean
        get() = sp.getBoolean("ink", true)
        set(v) = sp.edit().putBoolean("ink", v).apply()
    var dimMin: Int
        get() = sp.getInt("dim", 30)
        set(v) = sp.edit().putInt("dim", v).apply()
    var night: Int
        get() = sp.getInt("night", 0)
        set(v) = sp.edit().putInt("night", v).apply()
    // Красноярск
    var lat: String
        get() = sp.getString("lat", "56.01")!!
        set(v) = sp.edit().putString("lat", v).apply()
    var lon: String
        get() = sp.getString("lon", "92.87")!!
        set(v) = sp.edit().putString("lon", v).apply()
}

data class AppInfo(val pkg: String, val label: String)

fun installed(c: Context): List<AppInfo> {
    val pm = c.packageManager
    val m = LinkedHashMap<String, AppInfo>()
    for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0).forEach {
            val p = it.activityInfo.packageName
            if (p != c.packageName) m.getOrPut(p) { AppInfo(p, it.loadLabel(pm).toString()) }
        }
    }
    // дополнительно: любое приложение, у которого есть запуск (например, YouTube на нестандартных ТВ)
    runCatching {
        pm.getInstalledApplications(0).forEach { ai ->
            val p = ai.packageName
            if (p != c.packageName && !m.containsKey(p) &&
                (pm.getLeanbackLaunchIntentForPackage(p) != null || pm.getLaunchIntentForPackage(p) != null)
            ) m[p] = AppInfo(p, ai.loadLabel(pm).toString())
        }
    }
    return m.values.sortedBy { it.label.lowercase() }
}

fun openApp(c: Context, pkg: String) {
    val pm = c.packageManager
    val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return
    c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun appLabel(c: Context, pkg: String): String = runCatching {
    c.packageManager.getApplicationInfo(pkg, 0).loadLabel(c.packageManager).toString()
}.getOrDefault(pkg)

fun appIcon(c: Context, pkg: String): ImageBitmap? = runCatching {
    c.packageManager.getApplicationIcon(pkg).toBitmap(256, 256).asImageBitmap()
}.getOrNull()

// ---- Источники сигнала (HDMI и т.п.)
fun tvInputs(c: Context): List<Pair<String, String>> = runCatching {
    val tm = c.getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
    tm.tvInputList.filter { it.isPassthroughInput }.map {
        it.id to (it.loadCustomLabel(c)?.toString() ?: it.loadLabel(c)?.toString() ?: "HDMI")
    }
}.getOrDefault(emptyList())

fun openInput(c: Context, id: String) {
    runCatching {
        c.startActivity(
            Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

// ---- Обои: приложение, Pictures/Wallpapers, флешки (папка Wallpapers)
fun wallFiles(c: Context): List<File> {
    val dirs = mutableListOf<File?>(
        c.getExternalFilesDir("wallpapers"),
        File(Environment.getExternalStorageDirectory(), "Pictures/Wallpapers")
    )
    File("/storage").listFiles()?.forEach {
        dirs += File(it, "Wallpapers"); dirs += File(it, "Pictures/Wallpapers")
    }
    return dirs.filterNotNull()
        .flatMap { it.listFiles()?.toList() ?: emptyList() }
        .filter { it.isFile && it.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp") }
        .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }
        .sortedBy { it.name }
}

fun decode(f: File): ImageBitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var s = 1
    while (o.outWidth / (s * 2) >= 2000) s *= 2
    BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
}.getOrNull()

private val bmpCache = LruCache<String, ImageBitmap>(2)

fun decodeCached(f: File): ImageBitmap? {
    synchronized(bmpCache) { bmpCache.get(f.path)?.let { return it } }
    val b = decode(f) ?: return null
    synchronized(bmpCache) { bmpCache.put(f.path, b) }
    return b
}

// средняя яркость верхней части обоев (0..1) для автоцвета часов
fun lumOf(f: File): Float = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var s = 1
    while (o.outWidth / (s * 2) >= 160) s *= 2
    val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        ?: return@runCatching 0f
    var sum = 0.0
    var cnt = 0
    val h = (b.height * 0.35f).toInt().coerceAtLeast(1)
    for (y in 0 until h step 2) for (x in 0 until b.width step 2) {
        val p = b.getPixel(x, y)
        sum += 0.299 * ((p shr 16) and 255) + 0.587 * ((p shr 8) and 255) + 0.114 * (p and 255)
        cnt++
    }
    b.recycle()
    (sum / cnt / 255.0).toFloat()
}.getOrDefault(0f)

// ---- Погода
private fun wxDesc(c: Int) = when (c) {
    0 -> "Ясно"; 1, 2, 3 -> "Облачно"; 45, 48 -> "Туман"
    in 51..67 -> "Дождь"; in 71..77 -> "Снег"; in 80..82 -> "Ливень"
    in 85..86 -> "Снегопад"; else -> if (c >= 95) "Гроза" else "—"
}

private fun httpGet(u: String): String {
    val c = URL(u).openConnection() as HttpURLConnection
    c.connectTimeout = 10000; c.readTimeout = 10000
    c.setRequestProperty("User-Agent", "LiquidLauncher/0.3")
    return c.inputStream.bufferedReader().use { it.readText() }
}

fun weather(lat: String, lon: String): String? {
    try {
        val j = JSONObject(httpGet("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code")).getJSONObject("current")
        val t = Math.round(j.getDouble("temperature_2m")).toInt()
        return (if (t > 0) "+" else "") + t + "° " + wxDesc(j.getInt("weather_code"))
    } catch (e: Exception) { }
    return try {
        val r = httpGet("https://wttr.in/$lat,$lon?format=%t+%C&lang=ru").trim()
        if (r.length < 40 && r.contains("°")) r.replace("°C", "°") else null
    } catch (e: Exception) { null }
}
