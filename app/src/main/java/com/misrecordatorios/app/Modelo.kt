package com.misrecordatorios.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.min

/** El "codigo" es lo que se guarda: no cambia aunque se renombre el enum. */
enum class Repeticion(val codigo: String, val etiqueta: String) {
    NINGUNA("N", "No se repite"),
    DIARIA("D", "Todos los días"),
    SEMANAL("S", "Semanal"),
    MENSUAL("M", "Mensual");

    companion object {
        fun desdeCodigo(c: String) = entries.firstOrNull { it.codigo == c } ?: NINGUNA
    }
}

data class Recordatorio(
    val id: Int,
    var titulo: String,
    var nota: String,
    var proxima: Long,              // próxima fecha y hora (epoch en ms)
    var repeticion: Repeticion,
    var dias: MutableSet<Int>,      // para semanal: 1 = lunes … 7 = domingo
    var diaMes: Int,                // para mensual: día del mes elegido
    var activo: Boolean             // false = vencido (no se repite y ya sonó)
) {
    fun aJson(): JSONObject = JSONObject()
        .put("id", id).put("titulo", titulo).put("nota", nota)
        .put("proxima", proxima).put("rep", repeticion.codigo)
        .put("dias", JSONArray(dias.toList())).put("diaMes", diaMes)
        .put("activo", activo)

    companion object {
        fun desdeJson(o: JSONObject): Recordatorio {
            val arr = o.optJSONArray("dias") ?: JSONArray()
            return Recordatorio(
                id = o.getInt("id"),
                titulo = o.optString("titulo"),
                nota = o.optString("nota"),
                proxima = o.getLong("proxima"),
                repeticion = Repeticion.desdeCodigo(o.optString("rep", "N")),
                dias = MutableList(arr.length()) { arr.getInt(it) }.toMutableSet(),
                diaMes = o.optInt("diaMes", 1),
                activo = o.optBoolean("activo", true)
            )
        }
    }
}

/** Guarda los recordatorios en el propio celular (sin internet ni servidor). */
object Repositorio {
    private const val PREFS = "datos"
    private const val CLAVE = "recordatorios"
    private const val CLAVE_ID = "ultimo_id"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun todos(ctx: Context): MutableList<Recordatorio> {
        val arr = JSONArray(prefs(ctx).getString(CLAVE, "[]") ?: "[]")
        return MutableList(arr.length()) { Recordatorio.desdeJson(arr.getJSONObject(it)) }
    }

    @Synchronized
    fun guardarTodos(ctx: Context, lista: List<Recordatorio>) {
        val arr = JSONArray()
        lista.forEach { arr.put(it.aJson()) }
        prefs(ctx).edit().putString(CLAVE, arr.toString()).commit()
    }

    fun obtener(ctx: Context, id: Int) = todos(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun guardar(ctx: Context, r: Recordatorio) {
        val lista = todos(ctx)
        val i = lista.indexOfFirst { it.id == r.id }
        if (i >= 0) lista[i] = r else lista.add(r)
        guardarTodos(ctx, lista)
    }

    @Synchronized
    fun borrar(ctx: Context, id: Int) = guardarTodos(ctx, todos(ctx).filter { it.id != id })

    @Synchronized
    fun nuevoId(ctx: Context): Int {
        val n = prefs(ctx).getInt(CLAVE_ID, 0) + 1
        prefs(ctx).edit().putInt(CLAVE_ID, n).commit()
        return n
    }
}

/** Cálculo de la próxima fecha según la repetición. */
object Calculo {
    private fun zona() = ZoneId.systemDefault()
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()
    fun zdt(ms: Long): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(zona())

    /** Próxima ocurrencia posterior a [despuesDe]; null si no se repite. */
    fun siguiente(r: Recordatorio, despuesDe: Long): Long? {
        val base = zdt(r.proxima)
        var t = base
        when (r.repeticion) {
            Repeticion.NINGUNA -> return null
            Repeticion.DIARIA -> {
                do { t = t.plusDays(1) } while (ms(t) <= despuesDe)
            }
            Repeticion.SEMANAL -> {
                if (r.dias.isEmpty()) return null
                do { t = t.plusDays(1) } while (t.dayOfWeek.value !in r.dias || ms(t) <= despuesDe)
            }
            Repeticion.MENSUAL -> {
                var mes = YearMonth.from(base)
                do {
                    mes = mes.plusMonths(1)
                    val dia = min(r.diaMes, mes.lengthOfMonth())
                    t = mes.atDay(dia).atTime(base.toLocalTime()).atZone(zona())
                } while (ms(t) <= despuesDe)
            }
        }
        return ms(t)
    }

    /** Al guardar: ubica la primera ocurrencia válida (día elegido y en el futuro). */
    fun ajustarInicio(r: Recordatorio, ahora: Long) {
        if (r.repeticion == Repeticion.SEMANAL && r.dias.isNotEmpty()) {
            var t = zdt(r.proxima)
            while (t.dayOfWeek.value !in r.dias) t = t.plusDays(1)
            r.proxima = ms(t)
        }
        if (r.repeticion != Repeticion.NINGUNA && r.proxima <= ahora) {
            siguiente(r, ahora)?.let { r.proxima = it }
        }
    }
}
