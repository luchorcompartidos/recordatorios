package com.misrecordatorios.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Estándar de versiones de la caja de apps, versión Android.
 * El número ya va compilado en la app (versionName). Desde las Releases de GitHub se leen
 * la fecha, el Changelog y si hay una versión más alta, con el link para descargar el instalador.
 * Sin conexión se usa la última consulta guardada.
 */
object VersionGitHub {
    private const val REPO = "luchorcompartidos/recordatorios"
    private const val PREFS = "version_github"
    private const val MIN_ENTRE_CONSULTAS = 30 * 60_000L

    data class Rel(val tag: String, val fecha: String, val notas: String, val apk: String?)

    fun instalada(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

    private fun partes(v: String): List<Int> =
        (v.removePrefix("v").split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 } + listOf(0, 0, 0)).take(3)

    /** Positivo si a es más alta que b. */
    fun comparar(a: String, b: String): Int {
        val x = partes(a); val y = partes(b)
        for (i in 0..2) if (x[i] != y[i]) return x[i] - y[i]
        return 0
    }

    fun guardadas(ctx: Context): List<Rel> = leer(ctx.getSharedPreferences(PREFS, 0).getString("rels", null))

    private fun leer(json: String?): List<Rel> = runCatching {
        val a = JSONArray(json ?: return emptyList())
        (0 until a.length()).map { a.getJSONObject(it) }
            .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") }
            .map { o ->
                val assets = o.optJSONArray("assets") ?: JSONArray()
                val lista = (0 until assets.length()).map { assets.getJSONObject(it) }
                val apk = lista.firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url")
                val fechaArchivo = lista.map { it.optString("updated_at") }.filter { it.isNotEmpty() }.maxOrNull()
                Rel(o.optString("tag_name"), fechaArchivo ?: o.optString("published_at"), o.optString("body"), apk)
            }
            .sortedWith { r1, r2 -> comparar(r2.tag, r1.tag) }
    }.getOrDefault(emptyList())

    /** Hay una versión publicada más alta que la instalada (las compilaciones de prueba no avisan). */
    fun nueva(ctx: Context): Rel? {
        val inst = instalada(ctx)
        if (inst.firstOrNull()?.isDigit() != true) return null
        return guardadas(ctx).firstOrNull()?.takeIf { comparar(it.tag, inst) > 0 }
    }

    /** Consulta GitHub en segundo plano (como mucho cada 30 min, salvo forzar) y avisa en el hilo principal. */
    fun consultar(ctx: Context, forzar: Boolean = false, listo: () -> Unit) {
        val p = ctx.getSharedPreferences(PREFS, 0)
        if (!forzar && System.currentTimeMillis() - p.getLong("hora", 0) < MIN_ENTRE_CONSULTAS) { listo(); return }
        val app = ctx.applicationContext
        Thread {
            runCatching {
                val c = URL("https://api.github.com/repos/$REPO/releases?per_page=30").openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                if (c.responseCode == 200) {
                    val txt = c.inputStream.bufferedReader().use { it.readText() }
                    if (leer(txt).isNotEmpty())
                        app.getSharedPreferences(PREFS, 0).edit().putString("rels", txt).putLong("hora", System.currentTimeMillis()).apply()
                }
                c.disconnect()
            }
            Handler(Looper.getMainLooper()).post(listo)
        }.start()
    }

    fun fecha(iso: String): String = runCatching {
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(iso))
    }.getOrDefault(iso)

    fun descargar(act: Activity, r: Rel?) {
        val url = r?.apk ?: "https://github.com/$REPO/releases/latest"
        runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    /** Diálogo de versión: instalada, fecha en GitHub, aviso de versión nueva y acceso a los cambios. */
    fun dialogo(act: Activity, tema: Int) {
        val inst = instalada(act)
        val rels = guardadas(act)
        val esta = rels.firstOrNull { it.tag.removePrefix("v") == inst }
        val n = nueva(act)
        val msg = buildString {
            append("Versión instalada: $inst\n")
            append(when {
                esta != null -> "Publicada el ${fecha(esta.fecha)}"
                rels.isEmpty() -> "Fecha no disponible (requiere conexión)."
                else -> "Esta versión no figura en las Releases de GitHub."
            })
            if (n != null) append("\n\nHay una versión nueva: ${n.tag} (${fecha(n.fecha)}). Descargá el instalador y abrilo para actualizar.")
        }
        AlertDialog.Builder(act, tema)
            .setTitle("Versión")
            .setMessage(msg)
            .setPositiveButton(if (n != null) "Descargar ${n.tag}" else "Cerrar") { _, _ -> if (n != null) descargar(act, n) }
            .setNeutralButton("Cambios en GitHub") { _, _ -> changelog(act, tema) }
            .show()
    }

    fun changelog(act: Activity, tema: Int) {
        val inst = instalada(act)
        val rels = guardadas(act)
        val txt = if (rels.isEmpty()) "Sin datos todavía (requiere conexión)."
                  else rels.joinToString("\n\n") { r ->
                      r.tag + (if (r.tag.removePrefix("v") == inst) " (la instalada)" else "") + " · " + fecha(r.fecha) +
                          (if (r.notas.isNotBlank()) "\n" + r.notas.trim() else "")
                  }
        AlertDialog.Builder(act, tema)
            .setTitle("Changelog (Releases de GitHub)")
            .setMessage(txt)
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Ver en GitHub") { _, _ ->
                runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/$REPO/releases"))) }
            }
            .show()
    }
}
