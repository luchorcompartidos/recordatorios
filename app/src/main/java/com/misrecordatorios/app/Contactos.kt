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
import android.graphics.Typeface
import android.net.Uri
import android.telephony.SmsManager
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Cómo se avisa a cada contacto. WhatsApp figura siempre primero. */
enum class ModoEnvio(val codigo: String, val texto: String) {
    NINGUNO("N", "Desactivado"),
    WHATSAPP("W", "Solo WhatsApp"),
    SMS("S", "Solo SMS"),
    AMBOS("A", "WhatsApp y SMS");

    val sms get() = this == SMS || this == AMBOS
    val whatsapp get() = this == WHATSAPP || this == AMBOS

    companion object {
        fun de(c: String) = entries.firstOrNull { it.codigo == c } ?: NINGUNO
    }
}

data class Contacto(var id: Int, var nombre: String, var numero: String, var modo: ModoEnvio, var porDefecto: Boolean) {
    val etiqueta get() = nombre.ifBlank { numero }
    /** WhatsApp pide formato internacional solo con dígitos (sin "+", espacios ni guiones). */
    fun numeroWhatsapp() = numero.filter { it.isDigit() }
}

object Contactos {
    const val PLANTILLA_DEFECTO = "{motivo}: {titulo}\n{estado}\n{nota}\nHora: {hora}"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("contactos", Context.MODE_PRIVATE)

    fun todos(ctx: Context): MutableList<Contacto> {
        val arr = JSONArray(prefs(ctx).getString("l", "[]") ?: "[]")
        val lista = MutableList(arr.length()) {
            val o = arr.getJSONObject(it)
            Contacto(o.optInt("id", 0), o.optString("nombre"), o.optString("numero"),
                ModoEnvio.de(o.optString("modo", "N")), o.optBoolean("def", false))
        }
        var max = lista.maxOfOrNull { it.id } ?: 0
        lista.forEach { if (it.id == 0) it.id = ++max }   // contactos de versiones anteriores
        return lista
    }

    fun guardar(ctx: Context, lista: List<Contacto>) {
        val arr = JSONArray()
        lista.forEach {
            arr.put(JSONObject().put("id", it.id).put("nombre", it.nombre).put("numero", it.numero)
                .put("modo", it.modo.codigo).put("def", it.porDefecto))
        }
        prefs(ctx).edit().putString("l", arr.toString()).apply()
    }

    fun nuevoId(ctx: Context) = (todos(ctx).maxOfOrNull { it.id } ?: 0) + 1
    fun activos(ctx: Context) = todos(ctx).filter { it.modo != ModoEnvio.NINGUNO && it.numero.isNotBlank() }
    fun defaults(ctx: Context) = activos(ctx).filter { it.porDefecto }

    fun smsHabilitado(ctx: Context) = prefs(ctx).getBoolean("sms_on", true)
    fun guardarSmsHabilitado(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("sms_on", v).apply()

    fun plantilla(ctx: Context, whatsapp: Boolean): String =
        prefs(ctx).getString(if (whatsapp) "pl_w" else "pl_s", PLANTILLA_DEFECTO) ?: PLANTILLA_DEFECTO
    fun guardarPlantilla(ctx: Context, whatsapp: Boolean, texto: String) =
        prefs(ctx).edit().putString(if (whatsapp) "pl_w" else "pl_s", texto).apply()

    fun exportar(ctx: Context): JSONObject = JSONObject()
        .put("lista", JSONArray(prefs(ctx).getString("l", "[]") ?: "[]"))
        .put("sms_on", smsHabilitado(ctx))
        .put("pl_w", plantilla(ctx, true))
        .put("pl_s", plantilla(ctx, false))

    fun importar(ctx: Context, o: JSONObject) {
        prefs(ctx).edit()
            .putString("l", (o.optJSONArray("lista") ?: JSONArray()).toString())
            .putBoolean("sms_on", o.optBoolean("sms_on", true))
            .putString("pl_w", o.optString("pl_w", PLANTILLA_DEFECTO))
            .putString("pl_s", o.optString("pl_s", PLANTILLA_DEFECTO))
            .apply()
    }
}

object Envios {
    private const val CANAL = "envios"
    const val EXTRA_POSPUESTO = "pospuesto"
    const val EXTRA_MOTIVO = "motivo"
    const val EXTRA_IDS = "ids"
    const val NUEVO = "Nuevo recordatorio"
    const val EDITADO = "Recordatorio modificado"
    const val PREVIO = "En 15 minutos"
    const val AHORA = "Ahora"

    fun mensaje(ctx: Context, r: Recordatorio, motivo: String, pospuesto: Boolean, whatsapp: Boolean): String {
        val hora = if (pospuesto) System.currentTimeMillis() else r.proxima
        return Contactos.plantilla(ctx, whatsapp)
            .replace("{motivo}", motivo)
            .replace("{titulo}", r.titulo)
            .replace("{nota}", r.nota)
            .replace("{hora}", Formato.completo(hora))
            .replace("{estado}", if (pospuesto) "{\"Original Vencido\"}" else "")
            .lines().filter { it.isNotBlank() }.joinToString("\n")
    }

    /** Contactos elegidos para el recordatorio (si nunca se eligieron: el grupo Default). */
    fun destinatarios(ctx: Context, r: Recordatorio): List<Contacto> {
        val sel = r.contactos ?: Contactos.defaults(ctx).map { it.id }.toSet()
        return Contactos.activos(ctx).filter { it.id in sel }
    }

    fun usaSms(ctx: Context, r: Recordatorio, c: Contacto) = c.modo.sms && Contactos.smsHabilitado(ctx) && !r.sinSms

    /** Avisos programados: 15 minutos antes y en el momento (también al sonar una posposición). */
    fun enviarProgramado(ctx: Context, r: Recordatorio, motivo: String, pospuesto: Boolean) {
        val lista = destinatarios(ctx, r)
        if (lista.isEmpty()) return
        lista.filter { usaSms(ctx, r, it) }.forEach { enviarSms(ctx, it, mensaje(ctx, r, motivo, pospuesto, false)) }
        val wa = lista.filter { it.modo.whatsapp }
        if (wa.isNotEmpty()) notificarWhatsapp(ctx, r, motivo, pospuesto, wa.map { it.id })
    }

    fun enviarSms(ctx: Context, c: Contacto, texto: String) {
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

    /** WhatsApp no permite enviar solo: queda una notificación que abre el mensaje listo para enviar. */
    private fun notificarWhatsapp(ctx: Context, r: Recordatorio, motivo: String, pospuesto: Boolean, ids: List<Int>) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Envíos por WhatsApp", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(ctx, r.id + 2_000_000, intentWhatsapp(ctx, r.id, motivo, pospuesto, ids),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Enviar por WhatsApp: ${r.titulo}")
            .setContentText("$motivo · tocá para enviarlo a ${ids.size} contacto(s).")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(r.id + 2_000_000, n)
        Registro.anotar(ctx, "#${r.id} WhatsApp listo para enviar ($motivo)")
    }

    fun intentWhatsapp(ctx: Context, id: Int, motivo: String, pospuesto: Boolean, ids: List<Int>): Intent =
        Intent(ctx, EnviarWhatsappActivity::class.java)
            .putExtra(Programador.EXTRA_ID, id)
            .putExtra(EXTRA_MOTIVO, motivo)
            .putExtra(EXTRA_POSPUESTO, pospuesto)
            .putExtra(EXTRA_IDS, ids.toIntArray())
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
        val motivo = intent.getStringExtra(Envios.EXTRA_MOTIVO) ?: Envios.AHORA
        val ids = intent.getIntArrayExtra(Envios.EXTRA_IDS)?.toSet()
        val lista = Contactos.activos(this).filter { it.modo.whatsapp && (ids == null || it.id in ids) }
        if (lista.isEmpty()) { aviso("No hay contactos con WhatsApp para este recordatorio"); finish(); return }
        val texto = Envios.mensaje(this, r, motivo, pospuesto, true)
        val textos = lista.mapIndexed { i, c -> (if (i in enviados) "✓ " else "") + "${c.etiqueta} (${c.numero})" }
        dialogo = AlertDialog.Builder(this, temaDialogo())
            .setTitle("Enviar por WhatsApp")
            .setItems(textos.toTypedArray()) { _, i ->
                enviados += i
                abriendo = true
                Envios.abrirWhatsapp(this, lista[i], texto)
            }
            .setPositiveButton("Listo", null)
            .setOnDismissListener { if (!abriendo) finish() }
            .show()
    }
}

private fun Activity.subtitulo(c: Context, t: String) = TextView(c).apply {
    text = t
    setTypeface(typeface, Typeface.BOLD)
    setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
}

/** Lista de contactos en Ajustes: casilla Default + toque para editar. */
fun Activity.armarContactos(contenedor: LinearLayout) {
    contenedor.removeAllViews()
    val lista = Contactos.todos(this)
    if (lista.isEmpty()) {
        contenedor.addView(TextView(this).apply { text = "No hay contactos cargados." })
        return
    }
    contenedor.addView(TextView(this).apply { text = "Casilla = forma parte del grupo Default."; textSize = 12f })
    val m = (6 * resources.displayMetrics.density).toInt()
    lista.forEachIndexed { i, c ->
        contenedor.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, m, 0, m)
            addView(CheckBox(this@armarContactos).apply {
                isChecked = c.porDefecto
                setOnCheckedChangeListener { _, v ->
                    val l = Contactos.todos(this@armarContactos)
                    l.firstOrNull { it.id == c.id }?.porDefecto = v
                    Contactos.guardar(this@armarContactos, l)
                }
            })
            addView(TextView(this@armarContactos).apply {
                text = "${c.etiqueta} · ${c.numero}\n${c.modo.texto}"
                textSize = 15f
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { editarContacto(contenedor, i) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
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
    val porDefecto = CheckBox(c).apply { text = "Forma parte del grupo Default"; isChecked = actual?.porDefecto ?: true }
    val caja = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(m, m / 2, m, 0)
        addView(nombre); addView(numero); addView(ayuda); addView(modo); addView(porDefecto)
    }
    val b = AlertDialog.Builder(this, temaDialogo())
        .setTitle(if (actual == null) "Agregar contacto" else "Editar contacto")
        .setView(caja)
        .setPositiveButton("Guardar") { _, _ ->
            if (numero.text.isBlank()) { aviso("Falta el número"); return@setPositiveButton }
            val nuevo = Contacto(actual?.id ?: Contactos.nuevoId(this), nombre.text.toString().trim(),
                numero.text.toString().trim(), ModoEnvio.entries[modo.selectedItemPosition], porDefecto.isChecked)
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

/** Texto personalizado del aviso (uno para WhatsApp y otro para SMS). */
fun Activity.editarPlantilla(whatsapp: Boolean) {
    val c = ContextThemeWrapper(this, temaDialogo())
    val m = (20 * resources.displayMetrics.density).toInt()
    val campo = EditText(c).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 4
        setText(Contactos.plantilla(this@editarPlantilla, whatsapp))
    }
    val ayuda = TextView(c).apply {
        textSize = 12f
        text = "Campos disponibles:\n{motivo} Nuevo / Modificado / En 15 minutos / Ahora\n{titulo} {nota} {hora}\n" +
            "{estado} {\"Original Vencido\"} si fue pospuesto\nLas líneas que queden vacías se omiten."
    }
    val caja = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(m, m / 2, m, 0)
        addView(campo); addView(ayuda)
    }
    AlertDialog.Builder(this, temaDialogo())
        .setTitle(if (whatsapp) "Texto del aviso por WhatsApp" else "Texto del aviso por SMS")
        .setView(caja)
        .setPositiveButton("Guardar") { _, _ -> Contactos.guardarPlantilla(this, whatsapp, campo.text.toString()) }
        .setNeutralButton("Restablecer") { _, _ ->
            Contactos.guardarPlantilla(this, whatsapp, Contactos.PLANTILLA_DEFECTO)
            aviso("Texto restablecido")
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

/** Ventana "Contactos a avisar" de un recordatorio, con botón para marcar/desmarcar el grupo Default. */
fun Activity.elegirContactos(seleccion: MutableSet<Int>, alCambiar: () -> Unit) {
    val activos = Contactos.activos(this).sortedByDescending { it.porDefecto }
    if (activos.isEmpty()) { aviso("No hay contactos habilitados. Cargalos en Ajustes → Avisos a contactos."); return }
    val marcados = BooleanArray(activos.size) { activos[it].id in seleccion }
    val textos = activos.map { (if (it.porDefecto) "★ " else "") + "${it.etiqueta} — ${it.modo.texto}" }.toTypedArray()
    val dialogo = AlertDialog.Builder(this, temaDialogo())
        .setTitle("Contactos a avisar (★ = Default)")
        .setMultiChoiceItems(textos, marcados) { _, i, v -> marcados[i] = v }
        .setPositiveButton("Aceptar") { _, _ ->
            seleccion.clear()
            activos.forEachIndexed { i, c -> if (marcados[i]) seleccion += c.id }
            alCambiar()
        }
        .setNeutralButton("Default ✓/✗", null)
        .setNegativeButton("Cancelar", null)
        .create()
    dialogo.setOnShowListener {
        dialogo.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val indices = activos.indices.filter { activos[it].porDefecto }
            if (indices.isEmpty()) { aviso("No hay contactos en el grupo Default"); return@setOnClickListener }
            val nuevo = !indices.all { marcados[it] }   // si estaban todos marcados, desmarca; si no, marca
            indices.forEach { marcados[it] = nuevo; dialogo.listView.setItemChecked(it, nuevo) }
        }
    }
    dialogo.show()
}

/**
 * Al crear o editar: confirma a quién avisar. Nada se envía sin tocar un botón de Enviar.
 * "Anular envío de SMS" queda guardado en el recordatorio y también afecta a los avisos programados.
 */
fun Activity.confirmarAvisos(r: Recordatorio, motivo: String, alTerminar: () -> Unit) {
    val activos = Contactos.activos(this)
    if (activos.isEmpty()) { alTerminar(); return }
    val sel = Envios.destinatarios(this, r).map { it.id }.toSet()
    val ordenados = activos.sortedWith(compareByDescending<Contacto> { it.id in sel }.thenByDescending { it.porDefecto })
    val c = ContextThemeWrapper(this, temaDialogo())
    val m = (20 * resources.displayMetrics.density).toInt()
    val caja = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(m, 0, m, 0) }
    if (sel.isNotEmpty()) caja.addView(subtitulo(c, "Contactos de este recordatorio (★ = Default)"))
    val checks = mutableListOf<Pair<Contacto, CheckBox>>()
    var cabeceraOtros = false
    ordenados.forEach { k ->
        if (k.id !in sel && !cabeceraOtros) { caja.addView(subtitulo(c, "Otros contactos habilitados")); cabeceraOtros = true }
        val cb = CheckBox(c).apply {
            text = (if (k.porDefecto) "★ " else "") + "${k.etiqueta} — ${k.modo.texto}"
            isChecked = k.id in sel
        }
        caja.addView(cb)
        checks += k to cb
    }
    val anular = CheckBox(c).apply { text = "Anular envío de SMS"; isChecked = r.sinSms }
    val leyenda = TextView(c).apply {
        text = "Se enviarán sólo por WhatsApp"
        setTypeface(typeface, Typeface.BOLD)
        visibility = if (r.sinSms) View.VISIBLE else View.GONE
    }
    anular.setOnCheckedChangeListener { _, v -> leyenda.visibility = if (v) View.VISIBLE else View.GONE }
    caja.addView(subtitulo(c, "Opciones"))
    caja.addView(anular)
    caja.addView(leyenda)
    if (!Contactos.smsHabilitado(this)) caja.addView(TextView(c).apply { text = "(El envío de SMS está deshabilitado en Ajustes.)"; textSize = 12f })

    var decidido = false
    fun guardarAnulacion() {
        if (r.sinSms != anular.isChecked) {
            r.sinSms = anular.isChecked
            Repositorio.guardar(this, r)
            Registro.anotar(this, "#${r.id} SMS " + if (r.sinSms) "anulado" else "reactivado")
        }
    }
    fun enviar(lista: List<Contacto>) {
        decidido = true
        guardarAnulacion()
        if (lista.isEmpty()) { aviso("No se eligió ningún contacto"); alTerminar(); return }
        val conSms = lista.filter { Envios.usaSms(this, r, it) }
        conSms.forEach { Envios.enviarSms(this, it, Envios.mensaje(this, r, motivo, false, false)) }
        if (conSms.isNotEmpty()) aviso("SMS enviado a ${conSms.size} contacto(s)")
        val wa = lista.filter { it.modo.whatsapp }
        if (wa.isNotEmpty()) startActivity(Envios.intentWhatsapp(this, r.id, motivo, false, wa.map { it.id }))
        alTerminar()
    }
    AlertDialog.Builder(this, temaDialogo())
        .setTitle("Avisar a contactos")
        .setView(ScrollView(c).apply { addView(caja) })
        .setPositiveButton("Enviar a marcados") { _, _ -> enviar(checks.filter { it.second.isChecked }.map { it.first }) }
        .setNeutralButton("Enviar a Default") { _, _ -> enviar(activos.filter { it.porDefecto }) }
        .setNegativeButton("Abortar") { _, _ -> decidido = true; guardarAnulacion(); alTerminar() }
        .setOnDismissListener { if (!decidido) alTerminar() }
        .show()
}

/** Historial de cambios (ChangeLog). Se agrega una entrada con cada versión nueva. */
object Cambios {
    private val versiones = listOf(
        "1.6" to listOf(
            "Notas en la lista: hasta 3 líneas con «Mostrar más...», colapsar por recordatorio y botones generales Mostrar/Ocultar notas.",
            "{\"Original Vencido\"} pasa a ser la primera línea de la nota (lista y notificación).",
            "Títulos de sección más destacados que la subsección Pospuestos.",
            "Las notificaciones quedan en la bandeja hasta tocar Hecho o deslizarlas; al posponer queda «Pospuesto hasta…».",
            "Contactos: grupo Default, contactos por recordatorio, aviso al crear, al editar, 15 minutos antes y en el momento.",
            "Confirmación antes de enviar (Enviar a marcados / a Default / Abortar) y «Anular envío de SMS» por recordatorio.",
            "Interruptor general de SMS y textos personalizables para WhatsApp y SMS.",
            "Corrección: el botón de WhatsApp de la pantalla completa ahora pide desbloquear y abre la lista."
        ),
        "1.5" to listOf(
            "ChangeLog en Ajustes.",
            "Avisos a contactos al sonar: SMS automático y/o WhatsApp con un toque, configurables por número.",
            "Subsección «Pospuestos» dentro de Próximos.",
            "Revisión de Posponer: mensajes con la causa exacta y registro de cada fallo."
        ),
        "1.4" to listOf(
            "«Personalizado» elegible como botón rápido; recuerda los últimos valores usados.",
            "Corrección: un aviso nuevo ya no queda oculto detrás de uno anterior.",
            "Recordatorios con alarma: modo «reloj despertador».",
            "Selector de hora con flechas ▲▼, teclado y reloj del sistema sincronizados.",
            "Copia de seguridad: previa, actual automática y manual; restauración.",
            "Recordatorio de prueba y registro de eventos."
        ),
        "1.3" to listOf(
            "Set completo de Posponer y opción Personalizado.",
            "Botón rápido configurable (por defecto, 10 minutos).",
            "Las posposiciones se conservan aunque se reinicie el celular."
        ),
        "1.2" to listOf(
            "Opción «Sonar como alarma»: sonido en bucle y pantalla completa.",
            "Botón «Callar».",
            "Ajustes: alarma sonora por defecto."
        ),
        "1.1" to listOf(
            "Corrección: la notificación de un recordatorio re-guardado aparece como nueva.",
            "Botones Hecho, Posponer rápido y Posponer…."
        ),
        "1.0" to listOf(
            "Versión inicial: crear, editar y borrar recordatorios con repetición diaria, semanal y mensual.",
            "Notificación con Hecho y Posponer 10 min; reprogramación al reiniciar el celular."
        )
    )

    fun texto(): String = versiones.joinToString("\n\n") { (v, items) ->
        "Versión $v\n" + items.joinToString("\n") { "• $it" }
    }
}
