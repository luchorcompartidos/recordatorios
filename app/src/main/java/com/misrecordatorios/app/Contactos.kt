package com.misrecordatorios.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import android.text.InputType
import android.view.ContextThemeWrapper
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Cómo se avisa a cada contacto cuando suena un recordatorio. */
enum class ModoEnvio(val codigo: String, val texto: String) {
    NINGUNO("N", "Desactivado"),
    SMS("S", "Solo SMS"),
    WHATSAPP("W", "Solo WhatsApp"),
    AMBOS("A", "SMS y WhatsApp");

    val sms get() = this == SMS || this == AMBOS
    val whatsapp get() = this == WHATSAPP || this == AMBOS

    companion object {
        fun de(c: String) = entries.firstOrNull { it.codigo == c } ?: NINGUNO
    }
}

data class Contacto(var nombre: String, var numero: String, var modo: ModoEnvio) {
    val etiqueta get() = nombre.ifBlank { numero }
    /** WhatsApp pide formato internacional solo con dígitos (sin "+", espacios ni guiones). */
    fun numeroWhatsapp() = numero.filter { it.isDigit() }
}

object Contactos {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("contactos", Context.MODE_PRIVATE)

    fun todos(ctx: Context): MutableList<Contacto> {
        val arr = JSONArray(prefs(ctx).getString("l", "[]") ?: "[]")
        return MutableList(arr.length()) {
            val o = arr.getJSONObject(it)
            Contacto(o.optString("nombre"), o.optString("numero"), ModoEnvio.de(o.optString("modo", "N")))
        }
    }

    fun guardar(ctx: Context, lista: List<Contacto>) {
        val arr = JSONArray()
        lista.forEach { arr.put(JSONObject().put("nombre", it.nombre).put("numero", it.numero).put("modo", it.modo.codigo)) }
        prefs(ctx).edit().putString("l", arr.toString()).apply()
    }

    fun activos(ctx: Context) = todos(ctx).filter { it.modo != ModoEnvio.NINGUNO && it.numero.isNotBlank() }

    /** Texto para el aviso al crear o editar: a quién se enviará y por qué medio. */
    fun resumen(ctx: Context): String? {
        val lista = activos(ctx)
        if (lista.isEmpty()) return null
        return lista.joinToString("\n") { "• ${it.etiqueta} (${it.numero}): ${it.modo.texto}" }
    }
}

object Envios {
    private const val CANAL = "envios"
    const val EXTRA_POSPUESTO = "pospuesto"

    fun mensaje(r: Recordatorio, pospuesto: Boolean): String {
        val sb = StringBuilder("Recordatorio: ").append(r.titulo)
        if (pospuesto) sb.append(" (Original vencido)")
        if (r.nota.isNotBlank()) sb.append("\n").append(r.nota)
        sb.append("\nHora: ").append(Formato.completo(System.currentTimeMillis()))
        return sb.toString()
    }

    /** Se llama cada vez que suena un recordatorio (también una posposición). */
    fun alSonar(ctx: Context, r: Recordatorio, pospuesto: Boolean) {
        val lista = Contactos.activos(ctx)
        if (lista.isEmpty()) return
        val texto = mensaje(r, pospuesto)
        lista.filter { it.modo.sms }.forEach { enviarSms(ctx, it, texto) }
        if (lista.any { it.modo.whatsapp }) notificarWhatsapp(ctx, r, pospuesto)
    }

    private fun enviarSms(ctx: Context, c: Contacto, texto: String) {
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Registro.anotar(ctx, "SMS a ${c.etiqueta} NO enviado: falta el permiso de SMS")
            return
        }
        runCatching {
            val sms = ctx.getSystemService(SmsManager::class.java)
            sms.sendMultipartTextMessage(c.numero, null, sms.divideMessage(texto), null, null)
        }.onSuccess { Registro.anotar(ctx, "SMS enviado a ${c.etiqueta}") }
            .onFailure { Registro.anotar(ctx, "Falló el SMS a ${c.etiqueta}: ${it.message}") }
    }

    /** WhatsApp no permite enviar solo: se deja una notificación que abre el mensaje listo para enviar. */
    private fun notificarWhatsapp(ctx: Context, r: Recordatorio, pospuesto: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Envíos por WhatsApp", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(ctx, r.id + 2_000_000, intentWhatsapp(ctx, r.id, pospuesto),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cantidad = Contactos.activos(ctx).count { it.modo.whatsapp }
        val n = Notification.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Enviar por WhatsApp: ${r.titulo}")
            .setContentText("Tocá para enviarlo a $cantidad contacto(s).")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(r.id + 2_000_000, n)
        Registro.anotar(ctx, "#${r.id} aviso de WhatsApp listo para enviar")
    }

    fun intentWhatsapp(ctx: Context, id: Int, pospuesto: Boolean): Intent =
        Intent(ctx, EnviarWhatsappActivity::class.java)
            .putExtra(Programador.EXTRA_ID, id)
            .putExtra(EXTRA_POSPUESTO, pospuesto)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun abrirWhatsapp(act: Activity, c: Contacto, texto: String) {
        val uri = Uri.parse("https://wa.me/${c.numeroWhatsapp()}?text=" + Uri.encode(texto))
        runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { act.aviso("No se pudo abrir WhatsApp") }
    }
}

/** Lista de contactos de WhatsApp: cada toque abre WhatsApp con el mensaje escrito. */
class EnviarWhatsappActivity : Activity() {
    private val enviados = mutableSetOf<Int>()
    private var dialogo: AlertDialog? = null
    private var abriendo = false

    override fun onResume() {
        super.onResume()
        abriendo = false
        mostrar()
    }

    override fun onPause() {
        super.onPause()
        dialogo?.setOnDismissListener(null)
        dialogo?.dismiss()
    }

    private fun mostrar() {
        val r = Repositorio.obtener(this, intent.getIntExtra(Programador.EXTRA_ID, -1)) ?: run { finish(); return }
        val pospuesto = intent.getBooleanExtra(Envios.EXTRA_POSPUESTO, false)
        val lista = Contactos.activos(this).filter { it.modo.whatsapp }
        if (lista.isEmpty()) { aviso("No hay contactos con WhatsApp en Ajustes"); finish(); return }
        val textos = lista.mapIndexed { i, c -> (if (i in enviados) "✓ " else "") + "${c.etiqueta} (${c.numero})" }
        dialogo = AlertDialog.Builder(this, temaDialogo())
            .setTitle("Enviar «${r.titulo}» por WhatsApp")
            .setItems(textos.toTypedArray()) { _, i ->
                enviados += i
                abriendo = true
                Envios.abrirWhatsapp(this, lista[i], Envios.mensaje(r, pospuesto))
            }
            .setPositiveButton("Listo", null)
            .setOnDismissListener { if (!abriendo) finish() }
            .show()
    }
}

/** Arma la lista de contactos dentro de Ajustes. */
fun Activity.armarContactos(contenedor: LinearLayout) {
    contenedor.removeAllViews()
    val lista = Contactos.todos(this)
    if (lista.isEmpty()) {
        contenedor.addView(TextView(this).apply { text = "No hay contactos cargados." })
    }
    val m = (8 * resources.displayMetrics.density).toInt()
    lista.forEachIndexed { i, c ->
        contenedor.addView(TextView(this).apply {
            text = "${c.etiqueta} · ${c.numero}\n${c.modo.texto}"
            textSize = 15f
            setPadding(0, m, 0, m)
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { editarContacto(contenedor, i) }
        })
    }
}

/** Alta (indice = -1) o edición de un contacto. */
fun Activity.editarContacto(contenedor: LinearLayout, indice: Int) {
    val lista = Contactos.todos(this)
    val actual = lista.getOrNull(indice)
    val c = ContextThemeWrapper(this, temaDialogo())
    val m = (20 * resources.displayMetrics.density).toInt()
    val nombre = EditText(c).apply { hint = "Nombre (opcional)"; setText(actual?.nombre ?: "") }
    val numero = EditText(c).apply {
        hint = "Número, ej.: 5491122334455"
        inputType = InputType.TYPE_CLASS_PHONE
        setText(actual?.numero ?: "")
    }
    val ayuda = TextView(c).apply {
        text = "Para WhatsApp usá el formato internacional: código de país + número (en Argentina, 549 + característica + número)."
        textSize = 12f
    }
    val modo = Spinner(c).apply {
        adapter = ArrayAdapter(c, android.R.layout.simple_spinner_dropdown_item, ModoEnvio.entries.map { it.texto })
        setSelection((actual?.modo ?: ModoEnvio.AMBOS).ordinal)
    }
    val caja = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(m, m / 2, m, 0)
        addView(nombre); addView(numero); addView(ayuda); addView(modo)
    }
    val b = AlertDialog.Builder(this, temaDialogo())
        .setTitle(if (actual == null) "Agregar contacto" else "Editar contacto")
        .setView(caja)
        .setPositiveButton("Guardar") { _, _ ->
            if (numero.text.isBlank()) { aviso("Falta el número"); return@setPositiveButton }
            val nuevo = Contacto(nombre.text.toString().trim(), numero.text.toString().trim(),
                ModoEnvio.entries[modo.selectedItemPosition])
            if (actual == null) lista.add(nuevo) else lista[indice] = nuevo
            Contactos.guardar(this, lista)
            if (nuevo.modo.sms && checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 2)
            }
            armarContactos(contenedor)
        }
        .setNegativeButton("Cancelar", null)
    if (actual != null) b.setNeutralButton("Borrar") { _, _ ->
        lista.removeAt(indice)
        Contactos.guardar(this, lista)
        armarContactos(contenedor)
    }
    b.show()
}

/** Historial de cambios (ChangeLog). Se agrega una entrada con cada versión nueva. */
object Cambios {
    private val versiones = listOf(
        "1.5" to listOf(
            "ChangeLog en Ajustes.",
            "Avisos a contactos al sonar: SMS automático y/o WhatsApp con un toque, configurables por número.",
            "Aviso al crear o editar un recordatorio indicando a quién se enviará.",
            "Subsección «Pospuestos» dentro de Próximos; leyenda «Original vencido».",
            "Revisión de Posponer: mensajes con la causa exacta y registro de cada fallo.",
            "Personalizado → Fecha y hora: si la última hora usada ya pasó hoy, arranca en la hora actual."
        ),
        "1.4" to listOf(
            "«Personalizado» elegible como botón rápido; recuerda los últimos valores usados.",
            "Corrección: un aviso nuevo ya no queda oculto detrás de uno anterior.",
            "Recordatorios con alarma: modo «reloj despertador» (sin demoras por ahorro de batería).",
            "Selector de hora con flechas ▲▼, teclado y reloj del sistema sincronizados.",
            "Copia de seguridad: copia previa, copia actual automática y copia manual; restauración.",
            "Recordatorio de prueba y registro de eventos en Ajustes.",
            "Al editar un vencido de un día anterior, la fecha arranca en hoy."
        ),
        "1.3" to listOf(
            "Set completo de Posponer: minutos, horas, mañana, 1 semana, 1 mes, 1 año y Personalizado.",
            "Botón rápido configurable en Ajustes (por defecto, 10 minutos).",
            "Fecha y hora iniciales en el momento actual.",
            "Las posposiciones se conservan aunque se reinicie el celular."
        ),
        "1.2" to listOf(
            "Opción «Sonar como alarma»: sonido en bucle y pantalla completa.",
            "Botón «Callar» que silencia sin cerrar la alarma.",
            "Ajustes: alarma sonora por defecto (desmarcada)."
        ),
        "1.1" to listOf(
            "Corrección: la notificación de un recordatorio re-guardado ahora aparece como nueva.",
            "Botones Hecho, Posponer rápido y Posponer… (1 a 10 minutos).",
            "Notificación visible completa en la pantalla de bloqueo."
        ),
        "1.0" to listOf(
            "Versión inicial: crear, editar y borrar recordatorios.",
            "Repetición diaria, semanal y mensual.",
            "Notificación con Hecho y Posponer 10 min.",
            "Reprogramación al reiniciar el celular y permisos guiados."
        )
    )

    fun texto(): String = versiones.joinToString("\n\n") { (v, items) ->
        "Versión $v\n" + items.joinToString("\n") { "• $it" }
    }
}
