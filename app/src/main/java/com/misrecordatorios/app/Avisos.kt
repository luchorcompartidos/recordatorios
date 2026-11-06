package com.misrecordatorios.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import org.json.JSONObject
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Programa y cancela las alarmas del sistema (AlarmManager). */
object Programador {
    const val EXTRA_ID = "id"
    const val EXTRA_POSPUESTO = "pospuesto"
    const val EXTRA_CODIGO = "codigo"

    private fun pendiente(ctx: Context, id: Int, pospuesto: Boolean): PendingIntent {
        val i = Intent(ctx, AlarmaReceiver::class.java)
            .setAction("com.misrecordatorios.ALARMA")
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_POSPUESTO, pospuesto)
        val codigo = id * 2 + if (pospuesto) 1 else 0
        return PendingIntent.getBroadcast(ctx, codigo, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Recordatorios con alarma sonora: modo "reloj despertador" (nunca se demora por ahorro de batería).
     * El resto: alarma exacta que también funciona en reposo.
     */
    private fun alarmaEn(ctx: Context, momento: Long, pi: PendingIntent, despertador: Boolean): String {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return when {
            despertador && am.canScheduleExactAlarms() -> {
                val ver = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                am.setAlarmClock(AlarmManager.AlarmClockInfo(momento, ver), pi)
                "despertador"
            }
            am.canScheduleExactAlarms() -> {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, momento, pi)
                "exacta"
            }
            else -> {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, momento, pi)
                "aproximada (falta permiso de alarmas exactas)"
            }
        }
    }

    fun programar(ctx: Context, r: Recordatorio) {
        cancelar(ctx, r.id, incluirPospuesto = false)
        if (!r.activo) return
        val modo = alarmaEn(ctx, r.proxima, pendiente(ctx, r.id, false), r.alarma)
        Registro.anotar(ctx, "#${r.id} «${r.titulo}» programado para ${Formato.completo(r.proxima)} ($modo)")
    }

    /** Pospone según el código de [Posposicion]. Devuelve el momento elegido, o null si no se pudo. */
    /** Motivo del último fallo al posponer, para mostrarlo al usuario. */
    var motivo = ""

    fun posponer(ctx: Context, id: Int, codigo: String): Long? {
        val r = Repositorio.obtener(ctx, id)
        val momento = r?.let { Posposicion.momento(codigo, it) }
        motivo = when {
            r == null -> "no se encontró el recordatorio (#$id)"
            momento == null -> "opción no válida ($codigo)"
            momento <= System.currentTimeMillis() -> "el momento elegido (${Formato.completo(momento)}) ya pasó"
            else -> ""
        }
        if (motivo.isNotEmpty()) {
            Registro.anotar(ctx, "#$id NO se pudo posponer: $motivo")
            return null
        }
        r!!; momento!!
        Notificador.quitar(ctx, id)
        r.pospuestoHasta = momento
        Repositorio.guardar(ctx, r)
        val modo = alarmaEn(ctx, momento, pendiente(ctx, id, true), r.alarma)
        Registro.anotar(ctx, "#$id pospuesto hasta ${Formato.completo(momento)} ($modo)")
        return momento
    }

    fun cancelar(ctx: Context, id: Int, incluirPospuesto: Boolean = true) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pendiente(ctx, id, false))
        if (incluirPospuesto) am.cancel(pendiente(ctx, id, true))
    }

    fun cancelarTodo(ctx: Context) = Repositorio.todos(ctx).forEach { cancelar(ctx, it.id); Notificador.quitar(ctx, it.id) }

    fun borrar(ctx: Context, id: Int) {
        cancelar(ctx, id)
        Repositorio.borrar(ctx, id)
        Notificador.quitar(ctx, id)
    }

    /** Después de sonar: calcula la próxima vez o lo marca como vencido. */
    fun avanzar(ctx: Context, r: Recordatorio) {
        val siguiente = Calculo.siguiente(r, System.currentTimeMillis())
        if (siguiente == null) r.activo = false else r.proxima = siguiente
        Repositorio.guardar(ctx, r)
        programar(ctx, r)
    }

    /** Tras reiniciar el celular, cambiar la hora, actualizar la app o restaurar una copia. */
    fun reprogramarTodos(ctx: Context, avisarPerdidos: Boolean = true) {
        val ahora = System.currentTimeMillis()
        Repositorio.todos(ctx).forEach { r ->
            if (r.pospuestoHasta > 0) {
                if (r.pospuestoHasta <= ahora) {
                    r.pospuestoHasta = 0
                    Repositorio.guardar(ctx, r)
                    if (avisarPerdidos) {
                        Notificador.mostrar(ctx, r, comoAlarma = false, pospuesta = true)
                        Envios.alSonar(ctx, r, true)
                    }
                } else {
                    alarmaEn(ctx, r.pospuestoHasta, pendiente(ctx, r.id, true), r.alarma)
                }
            }
            if (!r.activo) return@forEach
            if (r.proxima <= ahora) {
                if (avisarPerdidos) {   // se perdió con el celular apagado
                    Notificador.mostrar(ctx, r, comoAlarma = false)
                    Envios.alSonar(ctx, r, false)
                }
                avanzar(ctx, r)
            } else {
                programar(ctx, r)
            }
        }
    }
}

object Ajustes {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE)
    fun alarmaPorDefecto(ctx: Context) = prefs(ctx).getBoolean("alarma_defecto", false)
    fun guardarAlarmaPorDefecto(ctx: Context, valor: Boolean) =
        prefs(ctx).edit().putBoolean("alarma_defecto", valor).apply()
    fun posponerRapido(ctx: Context): String =
        prefs(ctx).getString("posponer_rapido", Posposicion.POR_DEFECTO) ?: Posposicion.POR_DEFECTO
    fun guardarPosponerRapido(ctx: Context, codigo: String) =
        prefs(ctx).edit().putString("posponer_rapido", codigo).apply()

    // Últimos valores usados en "Personalizado".
    fun ultimoPlazo(ctx: Context): Pair<Int, String> =
        prefs(ctx).getInt("plazo_n", 1) to (prefs(ctx).getString("plazo_u", "m") ?: "m")
    fun guardarUltimoPlazo(ctx: Context, n: Int, unidad: String) =
        prefs(ctx).edit().putInt("plazo_n", n).putString("plazo_u", unidad).apply()
    fun ultimaHora(ctx: Context): LocalTime? =
        prefs(ctx).getInt("hora_min", -1).takeIf { it >= 0 }?.let { LocalTime.of(it / 60, it % 60) }
    fun guardarUltimaHora(ctx: Context, t: LocalTime) =
        prefs(ctx).edit().putInt("hora_min", t.hour * 60 + t.minute).apply()

    fun exportar(ctx: Context): JSONObject = JSONObject()
        .put("alarma_defecto", alarmaPorDefecto(ctx))
        .put("posponer_rapido", posponerRapido(ctx))

    fun importar(ctx: Context, o: JSONObject) {
        guardarAlarmaPorDefecto(ctx, o.optBoolean("alarma_defecto", false))
        guardarPosponerRapido(ctx, o.optString("posponer_rapido", Posposicion.POR_DEFECTO))
    }
}

object Notificador {
    private const val CANAL = "recordatorios"
    private const val CANAL_ALARMA = "alarmas_v1"
    private const val CANAL_MUDO = "alarmas_silenciadas_v1"
    const val ACCION_HECHO = "com.misrecordatorios.HECHO"
    const val ACCION_POSPONER = "com.misrecordatorios.POSPONER"
    const val ACCION_CALLAR = "com.misrecordatorios.CALLAR"

    fun crearCanales(ctx: Context) {
        val normal = NotificationChannel(CANAL, "Recordatorios", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Avisos de tus recordatorios"; enableVibration(true) }
        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val alarma = NotificationChannel(CANAL_ALARMA, "Alarmas", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Recordatorios que suenan como alarma"
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), audio)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 600)
        }
        val mudo = NotificationChannel(CANAL_MUDO, "Alarmas silenciadas", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Alarma ya silenciada con «Callar»"
            setSound(null, null)
            enableVibration(false)
        }
        ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannels(listOf(normal, alarma, mudo))
    }

    /**
     * Quita las notificaciones de un recordatorio. Cada aviso usa una etiqueta propia, así un aviso
     * nuevo nunca queda "pegado" a uno anterior (por ejemplo, uno pospuesto con el reloj de Android).
     */
    fun quitar(ctx: Context, id: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.activeNotifications.filter { it.id == id }.forEach { nm.cancel(it.tag, it.id) }
        nm.cancel(id)
    }

    /** true si la notificación visible de ese recordatorio viene de una posposición. */
    fun esPospuesta(ctx: Context, id: Int) = ctx.getSystemService(NotificationManager::class.java)
        .activeNotifications.firstOrNull { it.id == id }?.notification?.extras?.getBoolean("pospuesta") == true

    /** Deja la alarma en la bandeja y en pantalla, pero sin sonido. */
    fun silenciar(ctx: Context, id: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.activeNotifications.none { it.id == id }) return
        val r = Repositorio.obtener(ctx, id) ?: return
        mostrar(ctx, r, comoAlarma = true, silenciada = true, pospuesta = esPospuesta(ctx, id))
    }

    private fun pantallaAlarma(ctx: Context, id: Int) = PendingIntent.getActivity(ctx, id,
        Intent(ctx, AlarmaActivity::class.java).putExtra(Programador.EXTRA_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun boton(ctx: Context, texto: String, pi: PendingIntent) =
        Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_notif), texto, pi).build()

    private fun accion(ctx: Context, id: Int, accion: String, codigo: String = ""): PendingIntent {
        val i = Intent(ctx, AccionReceiver::class.java).setAction(accion)
            .putExtra(Programador.EXTRA_ID, id).putExtra(Programador.EXTRA_CODIGO, codigo)
        return PendingIntent.getBroadcast(ctx, id, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun mostrar(ctx: Context, r: Recordatorio, comoAlarma: Boolean = r.alarma, silenciada: Boolean = false,
                pospuesta: Boolean = false) {
        crearCanales(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // Si quedó una notificación anterior del mismo recordatorio, se quita para que la nueva
        // aparezca como nueva (con sonido, emergente y arriba) y no como una actualización silenciosa.
        val etiquetaPrevia = nm.activeNotifications.firstOrNull { it.id == r.id }?.tag
        if (!silenciada) quitar(ctx, r.id)
        val etiqueta = if (silenciada && etiquetaPrevia != null) etiquetaPrevia else "n${System.currentTimeMillis()}"
        val elegir = PendingIntent.getActivity(ctx, r.id,
            Intent(ctx, PosponerActivity::class.java).putExtra(Programador.EXTRA_ID, r.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val abrir = PendingIntent.getActivity(ctx, r.id,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val base = r.nota.ifBlank { "Recordatorio de las " + Formato.hora(r.proxima) }
        val texto = if (pospuesta) "Original vencido · $base" else base
        val rapido = Ajustes.posponerRapido(ctx)
        val piRapido = if (rapido == Posposicion.PERSONALIZADO)
            PendingIntent.getActivity(ctx, r.id + 1_000_000,
                Intent(ctx, PosponerActivity::class.java).putExtra(Programador.EXTRA_ID, r.id)
                    .putExtra(EXTRA_PERSONALIZADO, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        else accion(ctx, r.id, ACCION_POSPONER, rapido)
        val canal = when {
            !comoAlarma -> CANAL
            silenciada -> CANAL_MUDO
            else -> CANAL_ALARMA
        }
        val b = Notification.Builder(ctx, canal)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(r.titulo)
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(true)
            .setAutoCancel(true)
            .addExtras(android.os.Bundle().apply { putBoolean("pospuesta", pospuesta) })
            .addAction(boton(ctx, "Hecho", accion(ctx, r.id, ACCION_HECHO)))

        if (comoAlarma) {
            // Pantalla completa: con el celular bloqueado abre la pantalla de alarma;
            // desbloqueado, la notificación emergente queda fija hasta que se haga una acción.
            val pantalla = pantallaAlarma(ctx, r.id)
            b.setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(pantalla)
                .setFullScreenIntent(pantalla, true)
                .setOnlyAlertOnce(silenciada)
                .addAction(boton(ctx, "Posponer…", elegir))
            if (!silenciada) b.addAction(boton(ctx, "Callar", accion(ctx, r.id, ACCION_CALLAR)))
            else b.addAction(boton(ctx, Posposicion.textoBoton(rapido), piRapido))
        } else {
            b.setCategory(Notification.CATEGORY_REMINDER)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(abrir)
                .addAction(boton(ctx, Posposicion.textoBoton(rapido), piRapido))
                .addAction(boton(ctx, "Posponer…", elegir))
        }
        val n = b.build()
        // Sonido en bucle hasta que se toque Hecho, Posponer o Callar (o se abra la bandeja).
        if (comoAlarma && !silenciada) n.flags = n.flags or Notification.FLAG_INSISTENT
        nm.notify(etiqueta, r.id, n)
        if (!silenciada) Registro.anotar(ctx, "#${r.id} notificación mostrada (" +
            (if (comoAlarma) "alarma" else "común") + ", notificaciones " +
            (if (nm.areNotificationsEnabled()) "activadas" else "DESACTIVADAS") + ")")
    }
}

object Formato {
    private val loc = Locale.forLanguageTag("es-AR")
    private val fFecha = DateTimeFormatter.ofPattern("EEE d MMM yyyy", loc)
    private val fHora = DateTimeFormatter.ofPattern("HH:mm", loc)
    private val fCompleto = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", loc)
    private val dias = listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom")

    fun fecha(ms: Long): String = Calculo.zdt(ms).format(fFecha)
    fun hora(ms: Long): String = Calculo.zdt(ms).format(fHora)
    fun completo(ms: Long): String = Calculo.zdt(ms).format(fCompleto)

    fun detalle(r: Recordatorio): String {
        val rep = when (r.repeticion) {
            Repeticion.NINGUNA -> ""
            Repeticion.DIARIA -> " · Todos los días"
            Repeticion.SEMANAL -> " · " + r.dias.sorted().joinToString(", ") { dias[it - 1] }
            Repeticion.MENSUAL -> " · Mensual (día ${r.diaMes})"
        }
        return (if (r.activo) "" else "Vencido: ") + completo(r.proxima) + rep +
            (if (r.alarma) " · Alarma" else "")
    }
}

/** Suena la alarma: muestra la notificación y programa la próxima. */
class AlarmaReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val r = Repositorio.obtener(ctx, intent.getIntExtra(Programador.EXTRA_ID, -1)) ?: return
        val pospuesto = intent.getBooleanExtra(Programador.EXTRA_POSPUESTO, false)
        if (pospuesto) {
            if (r.pospuestoHasta == 0L) return   // la posposición se canceló (por ejemplo, al editar)
            r.pospuestoHasta = 0
            Repositorio.guardar(ctx, r)
            Registro.anotar(ctx, "#${r.id} sonó la posposición")
            Notificador.mostrar(ctx, r, pospuesta = true)
            Envios.alSonar(ctx, r, true)
            return
        }
        // Ignora alarmas viejas (por ejemplo, si el recordatorio se editó).
        if (!r.activo || r.proxima > System.currentTimeMillis() + 60_000) {
            Registro.anotar(ctx, "#${r.id} alarma ignorada (activo=${r.activo}, próxima=${Formato.completo(r.proxima)})")
            return
        }
        Registro.anotar(ctx, "#${r.id} sonó la alarma")
        Notificador.mostrar(ctx, r)
        Envios.alSonar(ctx, r, false)
        Programador.avanzar(ctx, r)
    }
}

/** Botones "Hecho" y "Posponer" de la notificación. */
class AccionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(Programador.EXTRA_ID, -1)
        when (intent.action) {
            Notificador.ACCION_CALLAR -> Notificador.silenciar(ctx, id)
            Notificador.ACCION_POSPONER ->
                Programador.posponer(ctx, id,
                    intent.getStringExtra(Programador.EXTRA_CODIGO) ?: Posposicion.POR_DEFECTO)
            else -> Notificador.quitar(ctx, id)
        }
    }
}

class ArranqueReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Programador.reprogramarTodos(ctx)
}
