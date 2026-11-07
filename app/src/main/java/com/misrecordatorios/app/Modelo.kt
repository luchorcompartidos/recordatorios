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
    MENSUAL("M", "El día NN de cada mes"),
    NSEMANA("P", "El n.º día de la semana del mes"),
    ANUAL("A", "Anual");

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
    var activo: Boolean,            // false = vencido (no se repite y ya sonó)
    var alarma: Boolean = false,    // true = suena como alarma con pantalla completa
    var pospuestoHasta: Long = 0,   // > 0 = hay una posposición pendiente (sobrevive a reinicios)
    var contactos: MutableSet<Int>? = null,  // contactos a avisar; null = grupo Default
    var sinSms: Boolean = false,    // "Anular envío de SMS" para este recordatorio
    var historialSms: MutableList<String> = mutableListOf(),  // suspensiones/reanudaciones masivas
    var semanaMes: Int = 1,         // "El n.º …": 1 a 4, 5 = último
    var diaSemana: Int = 1,         // "El n.º …": 1 = lunes … 7 = domingo
    var limite: Int = 0,            // terminar después de X veces (0 = nunca)
    var ocurrencias: Int = 0,       // veces que ya sonó por su fecha (las posposiciones no cuentan)
    var excluidos: MutableSet<Int> = mutableSetOf()  // contactos a la vista pero sin recibir avisos
) {
    fun aJson(): JSONObject = JSONObject()
        .put("id", id).put("titulo", titulo).put("nota", nota)
        .put("proxima", proxima).put("rep", repeticion.codigo)
        .put("dias", JSONArray(dias.toList())).put("diaMes", diaMes)
        .put("activo", activo).put("alarma", alarma).put("posp", pospuestoHasta)
        .put("sinSms", sinSms)
        .put("histSms", JSONArray(historialSms))
        .put("semMes", semanaMes).put("diaSem", diaSemana)
        .put("limite", limite).put("ocurr", ocurrencias)
        .put("excl", JSONArray(excluidos.toList()))
        .apply { contactos?.let { put("contactos", JSONArray(it.toList())) } }

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
                activo = o.optBoolean("activo", true),
                alarma = o.optBoolean("alarma", false),
                pospuestoHasta = o.optLong("posp", 0),
                contactos = o.optJSONArray("contactos")?.let { a -> MutableList(a.length()) { a.getInt(it) }.toMutableSet() },
                sinSms = o.optBoolean("sinSms", false),
                historialSms = o.optJSONArray("histSms")?.let { a -> MutableList(a.length()) { a.getString(it) } } ?: mutableListOf(),
                semanaMes = o.optInt("semMes", 1),
                diaSemana = o.optInt("diaSem", 1),
                limite = o.optInt("limite", 0),
                ocurrencias = o.optInt("ocurr", 0),
                excluidos = o.optJSONArray("excl")?.let { a -> MutableList(a.length()) { a.getInt(it) }.toMutableSet() } ?: mutableSetOf()
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
        Copia.automatica(ctx)   // mantiene sincronizada la copia de seguridad actual
    }

    @Synchronized
    fun exportar(ctx: Context): JSONObject = JSONObject()
        .put("recordatorios", JSONArray(prefs(ctx).getString(CLAVE, "[]") ?: "[]"))
        .put("ultimo_id", prefs(ctx).getInt(CLAVE_ID, 0))

    @Synchronized
    fun importar(ctx: Context, obj: JSONObject) {
        val arr = obj.optJSONArray("recordatorios") ?: JSONArray()
        val ultimo = maxOf(obj.optInt("ultimo_id", 0), prefs(ctx).getInt(CLAVE_ID, 0))
        prefs(ctx).edit().putString(CLAVE, arr.toString()).putInt(CLAVE_ID, ultimo).commit()
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

/**
 * Opciones de posponer. Códigos: "m:N" minutos, "h:N" horas, "d:N" días, "w:N" semanas,
 * "M:N" meses, "y:N" años, "manana" = mañana a la hora del recordatorio, "t:ms" = momento puntual.
 */
data class OpcionPosponer(val codigo: String, val texto: String)

object Posposicion {
    const val POR_DEFECTO = "m:10"

    val OPCIONES: List<OpcionPosponer> =
        listOf(1, 2, 3, 4, 5, 10, 15, 30, 45, 55).map { OpcionPosponer("m:$it", if (it == 1) "1 minuto" else "$it minutos") } +
        listOf(1, 2, 3, 5, 12).map { OpcionPosponer("h:$it", if (it == 1) "1 hora" else "$it horas") } +
        listOf(
            OpcionPosponer("manana", "Mañana a la misma hora"),
            OpcionPosponer("w:1", "1 semana"),
            OpcionPosponer("M:1", "1 mes"),
            OpcionPosponer("y:1", "1 año")
        )

    const val PERSONALIZADO = "personalizado"

    /** Opciones elegibles como botón rápido: el set completo más "Personalizado…". */
    val OPCIONES_RAPIDO = OPCIONES + OpcionPosponer(PERSONALIZADO, "Personalizado…")

    fun texto(codigo: String) = OPCIONES_RAPIDO.firstOrNull { it.codigo == codigo }?.texto ?: "10 minutos"

    /** Texto para un botón: "Posponer 10 minutos", "Posponer personalizado…". */
    fun textoBoton(codigo: String) = "Posponer " + texto(codigo).replaceFirstChar { it.lowercase() }

    fun momento(codigo: String, r: Recordatorio, ahora: ZonedDateTime = ZonedDateTime.now()): Long? {
        if (codigo == "manana") {
            val hora = Calculo.zdt(r.proxima).toLocalTime().withSecond(0).withNano(0)
            return ahora.toLocalDate().plusDays(1).atTime(hora).atZone(ahora.zone).toInstant().toEpochMilli()
        }
        val partes = codigo.split(":")
        val n = partes.getOrNull(1)?.toLongOrNull() ?: return null
        val t = when (partes[0]) {
            "t" -> return n
            "m" -> ahora.plusMinutes(n)
            "h" -> ahora.plusHours(n)
            "d" -> ahora.plusDays(n)
            "w" -> ahora.plusWeeks(n)
            "M" -> ahora.plusMonths(n)
            "y" -> ahora.plusYears(n)
            else -> return null
        }
        return t.withNano(0).toInstant().toEpochMilli()
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
            Repeticion.NSEMANA -> {
                var mes = YearMonth.from(base)
                do {
                    mes = mes.plusMonths(1)
                    t = nEsimo(mes, r.semanaMes, r.diaSemana).atTime(base.toLocalTime()).atZone(zona())
                } while (ms(t) <= despuesDe)
            }
            Repeticion.ANUAL -> {
                var anio = base.year
                do {
                    anio++
                    val mes = YearMonth.of(anio, base.monthValue)
                    t = mes.atDay(min(r.diaMes, mes.lengthOfMonth())).atTime(base.toLocalTime()).atZone(zona())
                } while (ms(t) <= despuesDe)
            }
        }
        return ms(t)
    }

    /** "El n.º [día] del mes": n de 1 a 4, o 5 = último. */
    fun nEsimo(mes: YearMonth, n: Int, diaSemana: Int): java.time.LocalDate {
        val dow = java.time.DayOfWeek.of(diaSemana)
        return if (n >= 5) mes.atEndOfMonth().with(java.time.temporal.TemporalAdjusters.lastInMonth(dow))
               else mes.atDay(1).with(java.time.temporal.TemporalAdjusters.dayOfWeekInMonth(n, dow))
    }

    /** ¿Suena (o sonó) en algún momento entre [desde] y [hasta]? Considera las repeticiones. */
    fun ocurreEntre(r: Recordatorio, desde: Long, hasta: Long): Boolean {
        if (r.pospuestoHasta in desde..hasta) return true
        if (!r.activo) return r.proxima in desde..hasta
        var t = r.proxima
        var restantes = if (r.limite > 0) r.limite - r.ocurrencias else Int.MAX_VALUE
        var vueltas = 0
        while (t <= hasta && restantes > 0 && vueltas < 1000) {
            if (t >= desde) return true
            t = siguiente(r.copy(proxima = t), t) ?: return false
            restantes--; vueltas++
        }
        return false
    }

    /** Al guardar: ubica la primera ocurrencia válida (día elegido y en el futuro). */
    fun ajustarInicio(r: Recordatorio, ahora: Long) {
        if (r.repeticion == Repeticion.SEMANAL && r.dias.isNotEmpty()) {
            var t = zdt(r.proxima)
            while (t.dayOfWeek.value !in r.dias) t = t.plusDays(1)
            r.proxima = ms(t)
        }
        if (r.repeticion == Repeticion.NSEMANA) {
            // Primera vez: el n.º día elegido del mes de la fecha elegida (o del mes siguiente si ya pasó).
            val base = zdt(r.proxima)
            val fecha = nEsimo(YearMonth.from(base), r.semanaMes, r.diaSemana)
            r.proxima = ms(fecha.atTime(base.toLocalTime()).atZone(zona()))
        }
        if (r.repeticion != Repeticion.NINGUNA && r.proxima <= ahora) {
            siguiente(r, ahora)?.let { r.proxima = it }
        }
    }
}
