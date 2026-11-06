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
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Programa y cancela las alarmas del sistema (AlarmManager). */
object Programador {
    const val EXTRA_ID = "id"
    const val EXTRA_POSPUESTO = "pospuesto"
    const val MINUTOS_POSPONER = 10L

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

    fun posponer(ctx: Context, id: Int) {
        val momento = System.currentTimeMillis() + MINUTOS_POSPONER * 60_000
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
                Notificador.mostrar(ctx, r)   // se perdió mientras estaba apagado
                avanzar(ctx, r)
            } else {
                programar(ctx, r)
            }
        }
    }
}

object Notificador {
    private const val CANAL = "recordatorios"
    const val ACCION_HECHO = "com.misrecordatorios.HECHO"
    const val ACCION_POSPONER = "com.misrecordatorios.POSPONER"

    fun crearCanal(ctx: Context) {
        val canal = NotificationChannel(CANAL, "Recordatorios", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Avisos de tus recordatorios"; enableVibration(true) }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    private fun accion(ctx: Context, id: Int, accion: String, texto: String): Notification.Action {
        val i = Intent(ctx, AccionReceiver::class.java).setAction(accion).putExtra(Programador.EXTRA_ID, id)
        val pi = PendingIntent.getBroadcast(ctx, id, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_notif), texto, pi).build()
    }

    fun mostrar(ctx: Context, r: Recordatorio) {
        crearCanal(ctx)
        val abrir = PendingIntent.getActivity(ctx, r.id,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val texto = r.nota.ifBlank { "Recordatorio de las " + Formato.hora(r.proxima) }
        val n = Notification.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(r.titulo)
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .addAction(accion(ctx, r.id, ACCION_HECHO, "Hecho"))
            .addAction(accion(ctx, r.id, ACCION_POSPONER, "Posponer ${Programador.MINUTOS_POSPONER} min"))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(r.id, n)
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
        return (if (r.activo) "" else "Vencido: ") + completo(r.proxima) + rep
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
        ctx.getSystemService(NotificationManager::class.java).cancel(id)
        if (intent.action == Notificador.ACCION_POSPONER) Programador.posponer(ctx, id)
    }
}

class ArranqueReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Programador.reprogramarTodos(ctx)
}
