package app.liquidtv.launcher

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    // Красноярск by default; change here (city picker is a planned feature)
    var lat: String
        get() = sp.getString("lat", "56.01")!!
        set(v) = sp.edit().putString("lat", v).apply()
    var lon: String
        get() = sp.getString("lon", "92.87")!!
        set(v) = sp.edit().putString("lon", v).apply()
}

data class AppInfo(val pkg: String, val label: String)

val BUILTIN = listOf(
    Brush.verticalGradient(listOf(Color(0xFF1B1F4A), Color(0xFF3B2A68), Color(0xFFC8476B), Color(0xFFF2A65A))),
    Brush.verticalGradient(listOf(Color(0xFF07243A), Color(0xFF0B4A5A), Color(0xFF3FA3A0), Color(0xFF9BE0CC))),
    Brush.verticalGradient(listOf(Color(0xFF8FA7BF), Color(0xFFC9B9AD), Color(0xFFE9D7BD))),
    Brush.verticalGradient(listOf(Color(0xFF0B0F1E), Color(0xFF1E2A5A), Color(0xFF5B3F9A)))
)

fun installed(c: Context): List<AppInfo> {
    val pm = c.packageManager
    val m = LinkedHashMap<String, AppInfo>()
    for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0).forEach {
            val p = it.activityInfo.packageName
            if (p != c.packageName) m.getOrPut(p) { AppInfo(p, it.loadLabel(pm).toString()) }
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

fun wallFiles(c: Context): List<File> =
    c.getExternalFilesDir("wallpapers")?.listFiles()
        ?.filter { it.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp") }
        ?.sortedBy { it.name } ?: emptyList()

fun decode(f: File): ImageBitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var s = 1
    while (o.outWidth / (s * 2) >= 2000) s *= 2
    BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
}.getOrNull()

private fun wxDesc(c: Int) = when (c) {
    0 -> "Ясно"; 1, 2, 3 -> "Облачно"; 45, 48 -> "Туман"
    in 51..67 -> "Дождь"; in 71..77 -> "Снег"; in 80..82 -> "Ливень"
    in 85..86 -> "Снегопад"; else -> if (c >= 95) "Гроза" else "—"
}

private fun httpGet(u: String): String {
    val c = URL(u).openConnection() as HttpURLConnection
    c.connectTimeout = 10000; c.readTimeout = 10000
    c.setRequestProperty("User-Agent", "LiquidLauncher/0.2")
    return c.inputStream.bufferedReader().use { it.readText() }
}

fun weather(lat: String, lon: String): String? {
    try {
        val j = JSONObject(httpGet("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code")).getJSONObject("current")
        val t = Math.round(j.getDouble("temperature_2m")).toInt()
        return (if (t > 0) "+" else "") + t + "° " + wxDesc(j.getInt("weather_code"))
    } catch (e: Exception) { }
    // запасной источник
    return try {
        val r = httpGet("https://wttr.in/$lat,$lon?format=%t+%C&lang=ru").trim()
        if (r.length < 40 && r.contains("°")) r.replace("°C", "°") else null
    } catch (e: Exception) { null }
}
