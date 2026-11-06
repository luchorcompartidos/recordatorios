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
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Programa y cancela las alarmas del sistema (AlarmManager). */
object Programador {
    const val EXTRA_ID = "id"
    const val EXTRA_POSPUESTO = "pospuesto"
    const val EXTRA_MINUTOS = "minutos"
    val OPCIONES_POSPONER = listOf(1, 2, 3, 4, 5, 10)

    private fun pendiente(ctx: Context, id: Int, pospuesto: Boolean): PendingIntent {
        val i = Intent(ctx, AlarmaReceiver::class.java)
            .setAction("com.misrecordatorios.ALARMA")
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_POSPUESTO, pospuesto)
        val codigo = id * 2 + if (pospuesto) 1 else 0
        return PendingIntent.getBroadcast(ctx, codigo, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun alarmaEn(ctx: Context, momento: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, momento, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, momento, pi) // respaldo, menos preciso
        }
    }

    fun programar(ctx: Context, r: Recordatorio) {
        cancelar(ctx, r.id, incluirPospuesto = false)
        if (r.activo) alarmaEn(ctx, r.proxima, pendiente(ctx, r.id, false))
    }

    fun posponer(ctx: Context, id: Int, minutos: Int) {
        ctx.getSystemService(NotificationManager::class.java).cancel(id)
        val momento = System.currentTimeMillis() + minutos * 60_000L
        alarmaEn(ctx, momento, pendiente(ctx, id, true))
    }

    fun cancelar(ctx: Context, id: Int, incluirPospuesto: Boolean = true) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pendiente(ctx, id, false))
        if (incluirPospuesto) am.cancel(pendiente(ctx, id, true))
    }

    fun borrar(ctx: Context, id: Int) {
        cancelar(ctx, id)
        Repositorio.borrar(ctx, id)
        ctx.getSystemService(NotificationManager::class.java).cancel(id)
    }

    /** Después de sonar: calcula la próxima vez o lo marca como vencido. */
    fun avanzar(ctx: Context, r: Recordatorio) {
        val siguiente = Calculo.siguiente(r, System.currentTimeMillis())
        if (siguiente == null) r.activo = false else r.proxima = siguiente
        Repositorio.guardar(ctx, r)
        programar(ctx, r)
    }

    /** Tras reiniciar el celular, cambiar la hora o actualizar la app. */
    fun reprogramarTodos(ctx: Context) {
        val ahora = System.currentTimeMillis()
        Repositorio.todos(ctx).filter { it.activo }.forEach { r ->
            if (r.proxima <= ahora) {
                Notificador.mostrar(ctx, r, comoAlarma = false)   // se perdió mientras estaba apagado
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

    /** Deja la alarma en la bandeja y en pantalla, pero sin sonido. */
    fun silenciar(ctx: Context, id: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.activeNotifications.none { it.id == id }) return
        val r = Repositorio.obtener(ctx, id) ?: return
        mostrar(ctx, r, comoAlarma = true, silenciada = true)
    }

    private fun pantallaAlarma(ctx: Context, id: Int) = PendingIntent.getActivity(ctx, id,
        Intent(ctx, AlarmaActivity::class.java).putExtra(Programador.EXTRA_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun boton(ctx: Context, texto: String, pi: PendingIntent) =
        Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_notif), texto, pi).build()

    private fun accion(ctx: Context, id: Int, accion: String, minutos: Int = 0): PendingIntent {
        val i = Intent(ctx, AccionReceiver::class.java).setAction(accion)
            .putExtra(Programador.EXTRA_ID, id).putExtra(Programador.EXTRA_MINUTOS, minutos)
        return PendingIntent.getBroadcast(ctx, id, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun mostrar(ctx: Context, r: Recordatorio, comoAlarma: Boolean = r.alarma, silenciada: Boolean = false) {
        crearCanales(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // Si quedó una notificación anterior del mismo recordatorio, se quita para que la nueva
        // aparezca como nueva (con sonido, emergente y arriba) y no como una actualización silenciosa.
        if (!silenciada) nm.cancel(r.id)
        val elegir = PendingIntent.getActivity(ctx, r.id,
            Intent(ctx, PosponerActivity::class.java).putExtra(Programador.EXTRA_ID, r.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val abrir = PendingIntent.getActivity(ctx, r.id,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val texto = r.nota.ifBlank { "Recordatorio de las " + Formato.hora(r.proxima) }
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
            else b.addAction(boton(ctx, "Posponer 5 min", accion(ctx, r.id, ACCION_POSPONER, 5)))
        } else {
            b.setCategory(Notification.CATEGORY_REMINDER)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(abrir)
                .addAction(boton(ctx, "Posponer 5 min", accion(ctx, r.id, ACCION_POSPONER, 5)))
                .addAction(boton(ctx, "Posponer…", elegir))
        }
        val n = b.build()
        // Sonido en bucle hasta que se toque Hecho, Posponer o Callar (o se abra la bandeja).
        if (comoAlarma && !silenciada) n.flags = n.flags or Notification.FLAG_INSISTENT
        nm.notify(r.id, n)
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
            Notificador.mostrar(ctx, r)
            return
        }
        // Ignora alarmas viejas (por ejemplo, si el recordatorio se editó).
        if (!r.activo || r.proxima > System.currentTimeMillis() + 60_000) return
        Notificador.mostrar(ctx, r)
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
                Programador.posponer(ctx, id, intent.getIntExtra(Programador.EXTRA_MINUTOS, 5))
            else -> ctx.getSystemService(NotificationManager::class.java).cancel(id)
        }
    }
}

class ArranqueReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Programador.reprogramarTodos(ctx)
}
