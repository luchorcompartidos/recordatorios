package com.misrecordatorios.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.os.Bundle
import android.widget.Button
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

    /** Tachado en SMS (experimental, desactivado por defecto). Se carga al iniciar y al cambiarlo. */
    var tachadoSms = false
        private set
    fun cargarTachado(ctx: Context) { tachadoSms = prefs(ctx).getBoolean("tachado_sms", false) }
    fun guardarTachado(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("tachado_sms", v).apply(); tachadoSms = v }
    fun guardarSmsHabilitado(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("sms_on", v).apply()

    fun plantilla(ctx: Context, whatsapp: Boolean): String =
        prefs(ctx).getString(if (whatsapp) "pl_w" else "pl_s", PLANTILLA_DEFECTO) ?: PLANTILLA_DEFECTO
    fun guardarPlantilla(ctx: Context, whatsapp: Boolean, texto: String) =
        prefs(ctx).edit().putString(if (whatsapp) "pl_w" else "pl_s", texto).apply()

    // --- Borrado masivo del SMS de todos los contactos, con respaldo para restaurar ---
    fun hayRespaldoSms(ctx: Context) = prefs(ctx).getString("sms_respaldo", null) != null
    fun fechaRespaldoSms(ctx: Context) = prefs(ctx).getLong("sms_respaldo_fecha", 0)

    /** Guarda qué contactos tenían SMS, se lo quita a todos y apaga el envío general. */
    fun borrarSmsMasivo(ctx: Context) {
        val lista = todos(ctx)
        val respaldo = JSONObject()
        lista.forEach { respaldo.put(it.id.toString(), it.modo.sms) }
        prefs(ctx).edit().putString("sms_respaldo", respaldo.toString())
            .putLong("sms_respaldo_fecha", System.currentTimeMillis()).apply()
        lista.forEach { it.modo = sinSms(it.modo) }
        guardar(ctx, lista)
        guardarSmsHabilitado(ctx, false)
        Registro.anotar(ctx, "SMS borrado de todos los contactos (respaldo guardado)")
    }

    /** Devuelve a cada contacto el SMS que tenía antes del borrado masivo. No toca WhatsApp ni el interruptor. */
    fun restaurarSms(ctx: Context): Int {
        val respaldo = JSONObject(prefs(ctx).getString("sms_respaldo", null) ?: return 0)
        val lista = todos(ctx)
        var cambios = 0
        lista.forEach { c ->
            if (!respaldo.has(c.id.toString())) return@forEach   // contacto agregado después del borrado
            val nuevo = if (respaldo.getBoolean(c.id.toString())) conSms(c.modo) else sinSms(c.modo)
            if (nuevo != c.modo) { c.modo = nuevo; cambios++ }
        }
        guardar(ctx, lista)
        Registro.anotar(ctx, "Configuración de SMS de contactos restaurada ($cambios cambio/s)")
        return cambios
    }

    private fun sinSms(m: ModoEnvio) = when (m) { ModoEnvio.AMBOS -> ModoEnvio.WHATSAPP; ModoEnvio.SMS -> ModoEnvio.NINGUNO; else -> m }
    private fun conSms(m: ModoEnvio) = when (m) { ModoEnvio.WHATSAPP -> ModoEnvio.AMBOS; ModoEnvio.NINGUNO -> ModoEnvio.SMS; else -> m }

    fun exportar(ctx: Context): JSONObject = JSONObject()
        .put("lista", JSONArray(prefs(ctx).getString("l", "[]") ?: "[]"))
        .put("sms_on", smsHabilitado(ctx))
        .put("pl_w", plantilla(ctx, true))
        .put("pl_s", plantilla(ctx, false))
        .put("sms_respaldo", prefs(ctx).getString("sms_respaldo", null))
        .put("sms_respaldo_fecha", fechaRespaldoSms(ctx))
        .put("tachado_sms", prefs(ctx).getBoolean("tachado_sms", false))
        .put("susp", prefs(ctx).getLong("susp", 0))

    fun importar(ctx: Context, o: JSONObject) {
        prefs(ctx).edit()
            .putString("l", (o.optJSONArray("lista") ?: JSONArray()).toString())
            .putBoolean("sms_on", o.optBoolean("sms_on", true))
            .putString("pl_w", o.optString("pl_w", PLANTILLA_DEFECTO))
            .putString("pl_s", o.optString("pl_s", PLANTILLA_DEFECTO))
            .putString("sms_respaldo", o.optString("sms_respaldo").ifBlank { null })
            .putLong("sms_respaldo_fecha", o.optLong("sms_respaldo_fecha", 0))
            .putBoolean("tachado_sms", o.optBoolean("tachado_sms", false))
            .putLong("susp", o.optLong("susp", 0))
            .apply()
        cargarTachado(ctx)
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

    /** Texto de {estado} según el momento del aviso. */
    fun estado(r: Recordatorio, motivo: String, pospuesto: Boolean, anterior: Long, whatsapp: Boolean = false): String = when {
        // En SMS, sin llaves ni comillas (las llaves cuentan doble).
        pospuesto -> if (whatsapp) "{\"Original Vencido\"}" else "Original Vencido"
        motivo == EDITADO && anterior > 0 && anterior != r.proxima -> {
            val f: (Long) -> String = if (whatsapp) Formato::completo else Formato::sms
            val antes = "Antes: ${f(anterior)}"
            // En WhatsApp, ~texto~ se ve tachado (dato desestimado). En SMS no hay formato.
            val tachado = when {
                whatsapp -> "~$antes~"
                Contactos.tachadoSms -> tacharUnicode(antes)
                else -> antes
            }
            "$tachado → Nueva Fecha/Hora: ${f(r.proxima)}"
        }
        motivo == PREVIO -> "Comienza en 15 minutos"
        motivo == AHORA -> "Es ahora"
        else -> ""   // nuevo, o modificado sin cambio de fecha ni hora: queda vacío
    }

    /** Tachado para SMS (experimental): agrega el carácter combinante U+0336 después de cada letra. */
    fun tacharUnicode(t: String) = t.map { "$it\u0336" }.joinToString("")

    fun mensaje(ctx: Context, r: Recordatorio, motivo: String, pospuesto: Boolean, whatsapp: Boolean, anterior: Long = 0): String {
        val hora = if (pospuesto) System.currentTimeMillis() else r.proxima
        val campos = mapOf(
            "{motivo}" to motivo, "{titulo}" to r.titulo, "{nota}" to r.nota,
            "{hora}" to (if (whatsapp) Formato.completo(hora) else Formato.sms(hora)), "{estado}" to estado(r, motivo, pospuesto, anterior, whatsapp)
        )
        // Se respetan las líneas vacías o con espacios escritas en el texto; solo se quita una línea
        // cuando queda vacía porque un campo vino vacío (por ejemplo {nota} sin nota).
        return Contactos.plantilla(ctx, whatsapp).split("\n").mapNotNull { linea ->
            val tieneCampos = campos.keys.any { it in linea }
            var res = linea
            campos.forEach { (k, v) -> res = res.replace(k, v) }
            if (tieneCampos && res.isBlank()) null else res
        }.joinToString("\n").let {
            if (whatsapp) it else paraSms(it).let { t -> if (r.convertirSms) convertirGsm(t) else t }
        }
    }

    /** Ajustes seguros para que el SMS ocupe menos: "→" (fuerza Unicode) pasa a "->". */
    fun paraSms(t: String) = t.replace("→", "->")

    private fun esGsm(ch: String) = medir(ch)[3] != android.telephony.SmsMessage.ENCODING_16BIT

    /**
     * "Convertir caracteres": reemplaza solo los caracteres que fuerzan Unicode (según el propio Android)
     * por equivalentes estándar: letras sin tilde, comillas y guiones simples, "..." en vez de "…".
     * Los que no tengan equivalente quedan como están.
     */
    fun convertirGsm(t: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            val ch = String(Character.toChars(cp))
            i += Character.charCount(cp)
            if (esGsm(ch)) { sb.append(ch); continue }
            val reemplazo = when (ch) {
                "“", "”", "«", "»" -> "\""
                "‘", "’" -> "'"
                "–", "—" -> "-"
                "…" -> "..."
                "→" -> "->"
                "•", "·", "‣", "◦", "▪", "●" -> "-"   // viñetas
                "º", "°" -> "o"
                "ª" -> "a"
                "´", "`" -> "'"
                "\u00A0", "\t" -> " "   // espacio duro y tabulación
                "\u0336" -> ""   // marca de tachado
                else -> java.text.Normalizer.normalize(ch, java.text.Normalizer.Form.NFD)
                    .replace(Regex("\\p{M}+"), "")
            }
            sb.append(if (reemplazo.isNotEmpty() && reemplazo.all { esGsm(it.toString()) }) reemplazo
                      else if (reemplazo.isEmpty()) "" else ch)
        }
        return sb.toString()
    }

    /** Caracteres que siguen forzando Unicode (para mostrarlos al usuario). */
    fun caracteresUnicode(t: String): String {
        val lista = mutableListOf<String>()
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            val ch = String(Character.toChars(cp))
            i += Character.charCount(cp)
            if (!esGsm(ch) && ch !in lista) lista += ch
        }
        return lista.joinToString(" ")
    }

    /** ¿La conversión cambiaría el texto? */
    fun convertible(t: String) = convertirGsm(t) != t

    /** Cálculo oficial de Android: [cantidad de SMS, caracteres usados, restantes, 1 = GSM-7 / 3 = Unicode]. */
    fun medir(t: String): IntArray = android.telephony.SmsMessage.calculateLength(t, false)

    /** Descripción del tamaño: "2 SMS · 95 caracteres · Unicode (70 por SMS; 67 si se divide)". */
    fun descripcionTamanio(t: String): String {
        val m = medir(t)
        val unicode = m[3] == android.telephony.SmsMessage.ENCODING_16BIT
        val cod = if (unicode) "Unicode (70 por SMS; 67 si se divide)" else "estándar (160 por SMS; 153 si se divide)"
        val causa = if (unicode) "\nFuerzan Unicode: " + caracteresUnicode(t) else ""
        return "${m[0]} SMS · ${t.length} caracteres · codificación $cod$causa"
    }

    /** Contactos elegidos para el recordatorio (si nunca se eligieron: el grupo Default). */
    fun destinatarios(ctx: Context, r: Recordatorio): List<Contacto> {
        val sel = r.contactos ?: Contactos.defaults(ctx).map { it.id }.toSet()
        return Contactos.activos(ctx).filter { it.id in sel && it.id !in r.excluidos }
    }

    fun usaSms(ctx: Context, r: Recordatorio, c: Contacto) =
        c.modo.sms && Contactos.smsHabilitado(ctx) && !r.sinSms && !SuspensionSms.activa(ctx)

    /** Avisos programados: 15 minutos antes y en el momento (también al sonar una posposición). */
    fun enviarProgramado(ctx: Context, r: Recordatorio, motivo: String, pospuesto: Boolean) {
        val lista = destinatarios(ctx, r)
        if (lista.isEmpty()) return
        val omitido = (motivo == PREVIO && r.omitirSmsPrevio) || (motivo == AHORA && r.omitirSmsAhora)
        if (omitido) Registro.anotar(ctx, "#${r.id} SMS programado omitido ($motivo)")
        val conSms = if (omitido) emptyList() else lista.filter { usaSms(ctx, r, it) }
        if (conSms.isNotEmpty()) {
            val texto = mensaje(ctx, r, motivo, pospuesto, false)
            val partes = medir(texto)[0]
            if (partes > 1) Registro.anotar(ctx, "#${r.id} SMS programado de $partes partes ($motivo)")
            conSms.forEach { enviarSms(ctx, it, texto) }
        }
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

    const val EXTRA_ANTERIOR = "anterior"

    fun intentWhatsapp(ctx: Context, id: Int, motivo: String, pospuesto: Boolean, ids: List<Int>, anterior: Long = 0): Intent =
        Intent(ctx, EnviarWhatsappActivity::class.java)
            .putExtra(Programador.EXTRA_ID, id)
            .putExtra(EXTRA_MOTIVO, motivo)
            .putExtra(EXTRA_POSPUESTO, pospuesto)
            .putExtra(EXTRA_IDS, ids.toIntArray())
            .putExtra(EXTRA_ANTERIOR, anterior)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private val PAQUETES = listOf("com.whatsapp", "com.whatsapp.w4b", null)   // WhatsApp, WhatsApp Business, cualquiera

    /** Abre el chat del contacto con el mensaje ya escrito. */
    fun abrirWhatsapp(act: Activity, c: Contacto, texto: String): Boolean {
        val uri = Uri.parse("https://wa.me/${c.numeroWhatsapp()}?text=" + Uri.encode(texto))
        for (paquete in PAQUETES) {
            val ok = runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(paquete)) }.isSuccess
            if (ok) { Registro.anotar(act, "WhatsApp abierto para ${c.etiqueta}"); return true }
        }
        act.aviso("No se pudo abrir WhatsApp")
        return false
    }

    /** Comparte el mensaje con WhatsApp: ahí se eligen varios chats y se envía a todos de una vez. */
    fun compartirWhatsapp(act: Activity, texto: String) {
        val base = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto)
        for (paquete in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            if (runCatching { act.startActivity(Intent(base).setPackage(paquete)) }.isSuccess) return
        }
        runCatching { act.startActivity(Intent.createChooser(base, "Compartir con")) }
            .onFailure { act.aviso("No se pudo abrir WhatsApp") }
    }
}

/**
 * Pantalla de envío por WhatsApp: casillas por contacto, envío "uno por uno" que se retoma al volver
 * (desde Recientes o desde la notificación de progreso) y opción de elegir varios chats dentro de WhatsApp.
 */
class EnviarWhatsappActivity : Activity() {
    private var r: Recordatorio? = null
    private var motivo = Envios.AHORA
    private var pospuesto = false
    private var contactos = listOf<Contacto>()
    private val marcados = mutableSetOf<Int>()
    private val enviados = mutableSetOf<Int>()
    private var enCola = false
    private lateinit var raiz: LinearLayout

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        setShowWhenLocked(true)
        title = "Enviar por WhatsApp"
        raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val m = (16 * resources.displayMetrics.density).toInt()
            setPadding(m, m, m, m)
        }
        setContentView(ScrollView(this).apply { addView(raiz) })
        cargar(intent, nuevo = estado == null)
        estado?.let {
            it.getIntArray("marcados")?.let { a -> marcados.clear(); marcados += a.toList() }
            it.getIntArray("enviados")?.let { a -> enviados += a.toList() }
            enCola = it.getBoolean("enCola")
        }
        Registro.anotar(this, "Pantalla de envío por WhatsApp abierta")
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        if (i.getBooleanExtra(EXTRA_CONTINUAR, false)) return   // volver desde la notificación: se conserva el progreso
        setIntent(i)
        cargar(i, nuevo = true)
    }

    override fun onResume() {
        super.onResume()
        armar()
    }

    override fun onSaveInstanceState(estado: Bundle) {
        super.onSaveInstanceState(estado)
        estado.putIntArray("marcados", marcados.toIntArray())
        estado.putIntArray("enviados", enviados.toIntArray())
        estado.putBoolean("enCola", enCola)
    }

    private fun cargar(i: Intent, nuevo: Boolean) {
        r = Repositorio.obtener(this, i.getIntExtra(Programador.EXTRA_ID, -1))
        if (r == null) { aviso("No se encontró el recordatorio"); finish(); return }
        motivo = i.getStringExtra(Envios.EXTRA_MOTIVO) ?: Envios.AHORA
        pospuesto = i.getBooleanExtra(Envios.EXTRA_POSPUESTO, false)
        val ids = i.getIntArrayExtra(Envios.EXTRA_IDS)?.toSet()
        contactos = Contactos.activos(this).filter { it.modo.whatsapp && (ids == null || it.id in ids) }
        if (nuevo) {
            marcados.clear(); marcados += contactos.map { it.id }
            enviados.clear(); enCola = false
        }
    }

    private fun texto() = Envios.mensaje(this, r!!, motivo, pospuesto, true,
        intent.getLongExtra(Envios.EXTRA_ANTERIOR, 0))
    private fun pendientes() = contactos.filter { it.id in marcados && it.id !in enviados }

    private fun armar() {
        val rec = r ?: return
        raiz.removeAllViews()
        fun txt(t: String, tam: Float = 15f, negrita: Boolean = false) = raiz.addView(TextView(this).apply {
            text = t; textSize = tam
            if (negrita) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 6, 0, 6)
        })
        fun btn(t: String, accion: () -> Unit) = raiz.addView(Button(this).apply { text = t; setOnClickListener { accion() } })

        txt(rec.titulo, 20f, true)
        txt("Mensaje:", 13f, true)
        txt(texto())
        if (contactos.isEmpty()) { txt("No hay contactos con WhatsApp para este recordatorio."); btn("Cerrar") { finish() }; return }

        txt("Contactos (${enviados.size} de ${contactos.count { it.id in marcados }} abiertos en WhatsApp):", 13f, true)
        contactos.forEach { c ->
            raiz.addView(CheckBox(this).apply {
                text = "${c.etiqueta} (${c.numero})" + if (c.id in enviados) "  ✓ abierto" else ""
                isChecked = c.id in marcados
                setOnCheckedChangeListener { _, v -> if (v) marcados += c.id else marcados -= c.id; armar() }
            })
        }
        val faltan = pendientes()
        if (enCola && faltan.isNotEmpty()) {
            btn("Continuar: enviar a ${faltan.first().etiqueta}") { enviarSiguiente() }
        } else {
            btn("Enviar a seleccionados (uno por uno)") {
                if (pendientes().isEmpty()) enviados.clear()
                enCola = true
                enviarSiguiente()
            }
        }
        btn("Elegir varios chats dentro de WhatsApp (un solo envío)") {
            conDesbloqueo { Envios.compartirWhatsapp(this, texto()) }
        }
        txt("«Uno por uno» abre cada chat con el mensaje escrito: tocá Enviar en WhatsApp y volvé acá " +
            "(desde Recientes o desde la notificación) para seguir con el próximo. «Elegir varios» abre WhatsApp " +
            "para que marques los chats y envíes el mismo mensaje a todos de una vez.", 12f)
        btn("Terminar") { terminar() }
    }

    private fun enviarSiguiente() {
        val siguiente = pendientes().firstOrNull()
        if (siguiente == null) {
            enCola = false
            quitarProgreso()
            aviso("Listo: se abrió WhatsApp para todos los seleccionados")
            armar()
            return
        }
        conDesbloqueo {
            if (Envios.abrirWhatsapp(this, siguiente, texto())) {
                enviados += siguiente.id
                mostrarProgreso()
            }
        }
    }

    /** Notificación para volver a esta pantalla y seguir con el próximo contacto. */
    private fun mostrarProgreso() {
        val rec = r ?: return
        val faltan = pendientes().size
        if (faltan == 0) { quitarProgreso(); return }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("envios", "Envíos por WhatsApp", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(this, rec.id + 3_000_000,
            Intent(this, EnviarWhatsappActivity::class.java).putExtra(EXTRA_CONTINUAR, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(ID_PROGRESO, Notification.Builder(this, "envios")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("WhatsApp: faltan $faltan contacto(s)")
            .setContentText("Tocá para seguir con «${rec.titulo}».")
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .build())
    }

    /** Vuelve a la lista de recordatorios y cierra esta pantalla (también de Recientes). */
    private fun terminar() {
        quitarProgreso()
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finishAndRemoveTask()
    }

    private fun quitarProgreso() = getSystemService(NotificationManager::class.java).cancel(ID_PROGRESO)

    companion object {
        const val EXTRA_CONTINUAR = "continuar"
        private const val ID_PROGRESO = 3_999_999
    }
}

/** Ejecuta la acción; si el celular está bloqueado, primero pide desbloquear. */
fun Activity.conDesbloqueo(accion: () -> Unit) {
    val km = getSystemService(KeyguardManager::class.java)
    if (!km.isKeyguardLocked) { accion(); return }
    km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
        override fun onDismissSucceeded() { accion() }
        override fun onDismissCancelled() { aviso("Desbloqueá el celular para continuar") }
        override fun onDismissError() { aviso("Desbloqueá el celular para continuar") }
    })
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
            // Sin alinear por la línea base: si no, el texto de 2 líneas baja y se corta la segunda.
            isBaselineAligned = false
            gravity = android.view.Gravity.CENTER_VERTICAL
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
        setSelection((actual?.modo ?: ModoEnvio.WHATSAPP).ordinal)
    }
    val porDefecto = CheckBox(c).apply { text = "Forma parte del grupo Default"; isChecked = actual?.porDefecto ?: false }
    val resultadoWa = TextView(c).apply { textSize = 13f; visibility = View.GONE }
    val validar = android.widget.Button(c).apply {
        text = "Validar en WA y recuperar"
        setOnClickListener {
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 3)
                aviso("Concedé el permiso de contactos y volvé a tocar «Validar en WA»")
                return@setOnClickListener
            }
            val res = Agenda.buscar(this@editarContacto, numero.text.toString())
            resultadoWa.visibility = View.VISIBLE
            resultadoWa.text = when {
                res == null -> "No se encontró ese número en la agenda del celular (WhatsApp tampoco tendrá nombre)."
                res.tieneWhatsapp -> "✓ Tiene WhatsApp · nombre recuperado: ${res.nombre}"
                else -> "Está en la agenda como «${res.nombre}», pero no figura en WhatsApp."
            }
            if (res != null && res.nombre.isNotBlank()) nombre.setText(res.nombre)
        }
    }
    val caja = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(m, m / 2, m, 0)
        addView(nombre); addView(numero); addView(validar); addView(resultadoWa); addView(ayuda); addView(modo); addView(porDefecto)
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
        AlertDialog.Builder(this, temaDialogo())
            .setTitle("Borrar contacto")
            .setMessage("¿Eliminar definitivamente a «${actual.etiqueta}» (${actual.numero})? " +
                "Deja de recibir avisos de todos los recordatorios.")
            .setPositiveButton("Eliminar") { _, _ ->
                lista.removeAt(indice)
                Contactos.guardar(this, lista)
                armarContactos(contenedor)
                aviso("Contacto eliminado")
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
            "{estado} cambio de fecha («Antes: … → Nueva Fecha/Hora: …»), «Comienza en 15 minutos», «Es ahora» " +
            "o {\"Original Vencido\"} si fue pospuesto\nLas líneas vacías o con espacios se respetan; solo se " +
            "omite una línea si queda vacía porque un campo vino vacío."
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
fun Activity.confirmarAvisos(r: Recordatorio, motivo: String, omitirPorDefecto: Boolean = false, anterior: Long = 0,
                             alTerminar: () -> Unit) {
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
    val suspendidos = SuspensionSms.activa(this)
    val anular = CheckBox(c).apply {
        text = "Anular envío de SMS"
        isChecked = r.sinSms || suspendidos
        isEnabled = !suspendidos   // con la suspensión activa no se puede optar por SMS
    }
    val leyenda = TextView(c).apply {
        text = "Se enviarán sólo por WhatsApp"
        setTypeface(typeface, Typeface.BOLD)
        visibility = if (r.sinSms) View.VISIBLE else View.GONE
    }
    // Solo para el SMS de este momento: no se guarda y no toca los programados.
    val bloqueados = suspendidos || !Contactos.smsHabilitado(this)
    val omitir = CheckBox(c).apply {
        text = when {
            motivo == Envios.NUEVO -> "Omitir SMS para este envío"
            omitirPorDefecto -> "OMITIR notificación vía SMS acerca de esta Modificación"
            else -> "OMITIR notificación vía SMS acerca de esta Modificación de Fecha/Hora"
        }
        isChecked = omitirPorDefecto
    }
    val notaOmitir = TextView(c).apply { textSize = 12f }
    fun refrescarOmitir() {
        val sinEfecto = anular.isChecked || bloqueados
        omitir.isEnabled = !sinEfecto
        notaOmitir.text = when {
            sinEfecto -> "(No se envía SMS en este momento.)"
            omitir.isChecked -> "No se envía SMS ahora. Los SMS programados (15 min antes y en el momento) quedan activos."
            else -> "Se envía SMS ahora. Los SMS programados (15 min antes y en el momento) quedan activos."
        }
    }
    anular.setOnCheckedChangeListener { _, v -> leyenda.visibility = if (v) View.VISIBLE else View.GONE; refrescarOmitir() }
    omitir.setOnCheckedChangeListener { _, _ -> refrescarOmitir() }
    refrescarOmitir()
    caja.addView(subtitulo(c, "Opciones"))
    caja.addView(anular)
    caja.addView(leyenda)
    caja.addView(omitir)
    caja.addView(notaOmitir)

    // --- SMS programados: validación de tamaño y omisión (anticipatorio / hora exacta) ---
    val haySmsProgramado = !bloqueados && Envios.destinatarios(this, r).any { it.modo.sms }
    val cbTodos = CheckBox(c).apply { text = "OMITIR SMS PROGRAMADOS (anticipatorio y hora exacta)" }
    val cbPrevio = CheckBox(c).apply { text = "Omitir SMS anticipatorio (15 min antes)"; isChecked = r.omitirSmsPrevio }
    val tvPrevio = TextView(c).apply { textSize = 12f }
    val cbAhora = CheckBox(c).apply { text = "Omitir SMS de la hora exacta del evento"; isChecked = r.omitirSmsAhora }
    val tvAhora = TextView(c).apply { textSize = 12f }
    val btnConvertir = android.widget.Button(c).apply { text = "Convertir caracteres"; visibility = View.GONE }
    var autoPrevio = false
    var autoAhora = false
    var validando = false
    fun validarProgramados(autoMarcar: Boolean) {
        if (!haySmsProgramado) return
        validando = true
        val pPrevio = Envios.medir(Envios.mensaje(this, r, Envios.PREVIO, false, false))[0]
        val pAhora = Envios.medir(Envios.mensaje(this, r, Envios.AHORA, false, false))[0]
        if (autoMarcar) {
            // Se dividirían: se marcan para omitir. Si después de convertir entran en 1 SMS, se desmarcan los auto-marcados.
            if (pPrevio > 1 && !cbPrevio.isChecked) { cbPrevio.isChecked = true; autoPrevio = true }
            if (pAhora > 1 && !cbAhora.isChecked) { cbAhora.isChecked = true; autoAhora = true }
            if (pPrevio <= 1 && autoPrevio) { cbPrevio.isChecked = false; autoPrevio = false }
            if (pAhora <= 1 && autoAhora) { cbAhora.isChecked = false; autoAhora = false }
        }
        cbTodos.isChecked = cbPrevio.isChecked && cbAhora.isChecked
        fun etiqueta(p: Int, omitido: Boolean) = (if (p > 1) "⚠ Ocuparía $p SMS" else "Ocuparía 1 SMS") +
            (if (omitido) " · se omitirá" else " · se enviará")
        tvPrevio.text = etiqueta(pPrevio, cbPrevio.isChecked)
        tvAhora.text = etiqueta(pAhora, cbAhora.isChecked)
        val textoAhora = Envios.mensaje(this, r, Envios.AHORA, false, false)
        val textoPrevio = Envios.mensaje(this, r, Envios.PREVIO, false, false)
        btnConvertir.visibility = if ((pPrevio > 1 || pAhora > 1) &&
            (Envios.convertible(textoAhora) || Envios.convertible(textoPrevio))) View.VISIBLE else View.GONE
        validando = false
    }
    if (haySmsProgramado) {
        caja.addView(subtitulo(c, "SMS programados"))
        caja.addView(cbTodos)
        caja.addView(cbPrevio); caja.addView(tvPrevio)
        caja.addView(cbAhora); caja.addView(tvAhora)
        caja.addView(btnConvertir)
        // Cada cambio vuelve a validar el tamaño.
        cbTodos.setOnClickListener { cbPrevio.isChecked = cbTodos.isChecked; cbAhora.isChecked = cbTodos.isChecked; validarProgramados(false) }
        cbPrevio.setOnCheckedChangeListener { _, _ -> if (!validando) { autoPrevio = false; validarProgramados(false) } }
        cbAhora.setOnCheckedChangeListener { _, _ -> if (!validando) { autoAhora = false; validarProgramados(false) } }
        btnConvertir.setOnClickListener {
            r.convertirSms = true
            aviso("Caracteres convertidos para los SMS de este recordatorio")
            validarProgramados(true)
        }
        validarProgramados(true)
    }

    if (suspendidos) caja.addView(TextView(c).apply {
        text = "Envíos SMS se encuentran SUSPENDIDOS/ANULADOS"
        setTypeface(typeface, Typeface.BOLD)
    })
    if (!Contactos.smsHabilitado(this)) caja.addView(TextView(c).apply { text = "(El envío de SMS está deshabilitado en Ajustes.)"; textSize = 12f })

    var decidido = false
    fun guardarAnulacion() {
        if (r.sinSms != anular.isChecked) Registro.anotar(this, "#${r.id} SMS " + if (anular.isChecked) "anulado" else "reactivado")
        r.sinSms = anular.isChecked
        if (haySmsProgramado) { r.omitirSmsPrevio = cbPrevio.isChecked; r.omitirSmsAhora = cbAhora.isChecked }
        Repositorio.guardar(this, r)
    }
    fun enviar(lista: List<Contacto>) {
        decidido = true
        guardarAnulacion()
        if (lista.isEmpty()) { aviso("No se eligió ningún contacto"); alTerminar(); return }
        val conSms = if (omitir.isChecked) emptyList() else lista.filter { Envios.usaSms(this, r, it) }
        if (omitir.isChecked && !anular.isChecked) Registro.anotar(this, "#${r.id} SMS omitido para este envío")
        val wa = lista.filter { it.modo.whatsapp }
        val seguirConWhatsapp = {
            if (wa.isNotEmpty()) startActivity(Envios.intentWhatsapp(this, r.id, motivo, false, wa.map { it.id }, anterior))
            alTerminar()
        }
        if (conSms.isEmpty()) { seguirConWhatsapp(); return }
        // Antes de enviar: si el SMS se divide en más de una parte, se pide confirmar, editar o no enviar.
        controlarTamanioSms(Envios.mensaje(this, r, motivo, false, false, anterior), conSms.size) { texto ->
            if (texto != null) {
                conSms.forEach { Envios.enviarSms(this, it, texto) }
                aviso("SMS enviado a ${conSms.size} contacto(s)")
            } else Registro.anotar(this, "#${r.id} SMS no enviado (elegido por el usuario)")
            seguirConWhatsapp()
        }
    }
    AlertDialog.Builder(this, temaDialogo())
        .setTitle("Avisar a contactos")
        .setView(ScrollView(c).apply { addView(caja) })
        .setPositiveButton("Enviar a marcados") { _, _ -> enviar(checks.filter { it.second.isChecked }.map { it.first }) }
        .setNeutralButton("Enviar a Default") { _, _ -> enviar(activos.filter { it.porDefecto }) }
        .setNegativeButton("Abortar") { _, _ -> decidido = true; guardarAnulacion(); alTerminar() }
        .setOnDismissListener { if (!decidido) { guardarAnulacion(); alTerminar() } }
        .show()
}

/** Historial de cambios (ChangeLog). Se agrega una entrada con cada versión nueva. */
object Cambios {
    private val versiones = listOf(
        "2.3.1" to listOf(
            "Corrección: «Convertir caracteres» ahora convierte viñetas (•) y otros símbolos comunes.",
            "El tamaño del SMS indica qué caracteres siguen forzando Unicode."
        ),
        "2.3" to listOf(
            "SMS: fecha corta «07/11/26 14:00» y «Original Vencido» sin llaves ni comillas.",
            "Al guardar: validación del tamaño de los SMS programados; casillas «OMITIR SMS PROGRAMADOS», «Omitir SMS " +
                "anticipatorio» y «Omitir SMS de la hora exacta» (se marcan solas si se dividirían).",
            "Botón «Convertir caracteres» cuando un SMS ocuparía más de 1 SMS (quita tildes y símbolos que fuerzan Unicode)."
        ),
        "2.2.4" to listOf(
            "SMS: «→» se reemplaza por «->» para ahorrar caracteres.",
            "Antes de enviar un SMS que se dividiría en más de una parte: aviso con opción de enviar igual, editarlo o no enviarlo.",
            "El SMS de prueba muestra cuántos SMS ocupa."
        ),
        "2.2.3" to listOf(
            "Ajustes: «Tachado en SMS (experimental)», desactivado por defecto, y «Enviar SMS de prueba» con confirmación."
        ),
        "2.2.2" to listOf("WhatsApp: «Antes: [fecha anterior]» se muestra tachado."),
        "2.2.1" to listOf(
            "Campo {estado}: «Antes: … → Nueva Fecha/Hora: …» al cambiar fecha u hora, «Comienza en 15 minutos», " +
                "«Es ahora» y {\"Original Vencido\"} si fue pospuesto."
        ),
        "2.2" to listOf(
            "Textos de aviso: se respetan las líneas vacías o con espacios.",
            "Secciones nuevas: Cumplidos (con subsección Vencidos) y Archivados; secciones y subsecciones colapsables.",
            "Selección múltiple en la lista: Mover a Archivados y Restaurar desde Archivados.",
            "«Dar por Cumplida»: cumple la ocurrencia más próxima; si se repite, sigue desde la siguiente.",
            "«Hecho» en la notificación deja el recordatorio destacado como «Revisar».",
            "Edición: texto «OMITIR notificación vía SMS acerca de esta Modificación de Fecha/Hora»."
        ),
        "2.1" to listOf(
            "Crear/editar: control «Envío de SMS: Activo / Inactivo» (equivale a «Anular envío de SMS»); se bloquea si los SMS " +
                "están deshabilitados en Ajustes (con acceso directo) o suspendidos desde la lista.",
            "Confirmación al guardar: nueva casilla «Omitir SMS para esta edición» (solo el envío del momento). Marcada por " +
                "defecto si no cambió la fecha, la hora ni la repetición.",
            "Ajustes: acceso directo que resalta «Envío de SMS habilitado»; al volver se retoma el recordatorio en curso."
        ),
        "2.0" to listOf(
            "Replicar recordatorios (botón en la edición y en el menú al mantener presionado en la lista).",
            "Repeticiones nuevas: Anual y «El 1.º/2.º/3.º/4.º/último [día] del mes»; «Mensual» pasa a llamarse «El día NN de cada mes».",
            "Terminar después de X veces (las posposiciones no cuentan).",
            "Lista: filtro por fecha (Hoy, Mañana, Próximos 7 días o un día elegido) y orden por fecha, título o creación.",
            "En cada recordatorio: lista de contactos de avisos con casilla (desmarcado = sin avisos) y opción Eliminar con confirmación."
        ),
        "1.9" to listOf(
            "Contactos: botón «Validar en WA y recuperar» (busca el número en la agenda, trae el nombre e indica si tiene WhatsApp).",
            "La copia de seguridad guarda también los últimos valores de «Personalizado» y la fecha del respaldo de SMS."
        ),
        "1.8.1" to listOf("Confirmación antes de eliminar un contacto."),
        "1.8" to listOf(
            "Lista principal: botón «Suspender/Anular SMS» con aviso fijo. Anula los SMS de todos los recordatorios " +
                "vigentes, impide cualquier envío y deja la marca en el historial de cada uno.",
            "«Reanudar SMS»: arranca de cero; los recordatorios afectados siguen sin SMS hasta reactivarlos a mano.",
            "Ajustes: «Borrar SMS de todos los contactos» (con respaldo) y «Restaurar configuración de SMS»."
        ),
        "1.7.1" to listOf(
            "Corrección: en la pantalla completa, «Enviar por WhatsApp», «Posponer» y «Editar» usaban un identificador " +
                "equivocado («No se encontró el recordatorio»).",
            "Corrección: en Ajustes, la segunda línea de cada contacto ya no se ve cortada.",
            "«Terminar» en la pantalla de WhatsApp vuelve a la lista de recordatorios."
        ),
        "1.7" to listOf(
            "Al agregar un contacto: grupo Default desmarcado y medio «Solo WhatsApp» por defecto.",
            "Nueva pantalla «Enviar por WhatsApp»: casillas por contacto, envío uno por uno que se retoma al volver " +
                "(Recientes o notificación de progreso) y opción de elegir varios chats dentro de WhatsApp en un solo envío.",
            "WhatsApp se abre directo, sin pasar por el navegador.",
            "Pantalla completa: corregido el botón «Enviar por WhatsApp» y agregado el botón «Editar»."
        ),
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

/**
 * Suspensión/anulación masiva de SMS desde la lista principal.
 * Al suspender: todos los recordatorios vigentes quedan con "Anular envío de SMS" (sus SMS dejan de existir).
 * Al reanudar: se arranca de cero; esos recordatorios siguen sin SMS hasta que el usuario los reactive.
 */
object SuspensionSms {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("contactos", Context.MODE_PRIVATE)
    fun activa(ctx: Context) = desde(ctx) > 0
    fun desde(ctx: Context) = prefs(ctx).getLong("susp", 0)

    fun suspender(ctx: Context) {
        val ahora = System.currentTimeMillis()
        prefs(ctx).edit().putLong("susp", ahora).apply()
        val lista = Repositorio.todos(ctx)
        lista.filter { it.activo || it.pospuestoHasta > 0 }.forEach {
            it.sinSms = true
            it.historialSms += "SMS suspendido/anulado masivamente el ${Formato.completo(ahora)}"
        }
        Repositorio.guardarTodos(ctx, lista)
        Registro.anotar(ctx, "SMS SUSPENDIDOS/ANULADOS para todos los recordatorios")
    }

    fun reanudar(ctx: Context) {
        val ahora = System.currentTimeMillis()
        prefs(ctx).edit().putLong("susp", 0).apply()
        val lista = Repositorio.todos(ctx)
        lista.filter { it.historialSms.lastOrNull()?.startsWith("SMS suspendido") == true ||
                       it.historialSms.lastOrNull()?.startsWith("Creado con SMS") == true }.forEach {
            it.historialSms += "Envíos SMS reanudados el ${Formato.completo(ahora)} (sigue sin SMS hasta reactivarlo)"
        }
        Repositorio.guardarTodos(ctx, lista)
        Registro.anotar(ctx, "SMS reanudados (los recordatorios vigentes siguen sin SMS)")
    }
}

/**
 * Búsqueda en la agenda del celular: WhatsApp muestra los nombres de la agenda y marca con su propia
 * cuenta ("com.whatsapp") a los contactos que usan WhatsApp.
 */
object Agenda {
    data class Resultado(val nombre: String, val tieneWhatsapp: Boolean)

    fun buscar(ctx: Context, numero: String): Resultado? {
        val digitos = numero.filter { it.isDigit() }
        if (digitos.length < 6) return null
        // Variantes del número: como se escribió, con "+", solo dígitos y los últimos 10 dígitos.
        val variantes = listOf(numero.trim(), "+$digitos", digitos, digitos.takeLast(10)).distinct()
        for (v in variantes) {
            val uri = Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(v))
            val encontrado = runCatching {
                ctx.contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup._ID,
                    android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getLong(0) to (c.getString(1) ?: "") else null
                }
            }.getOrNull() ?: continue
            val (idContacto, nombre) = encontrado
            Registro.anotar(ctx, "Validación WA: «$nombre» encontrado en la agenda")
            return Resultado(nombre, tieneWhatsapp(ctx, idContacto))
        }
        return null
    }

    private fun tieneWhatsapp(ctx: Context, idContacto: Long): Boolean = runCatching {
        val rc = android.provider.ContactsContract.RawContacts.CONTENT_URI
        ctx.contentResolver.query(rc, arrayOf(android.provider.ContactsContract.RawContacts._ID),
            "${android.provider.ContactsContract.RawContacts.CONTACT_ID} = ? AND " +
                "${android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE} IN ('com.whatsapp', 'com.whatsapp.w4b')",
            arrayOf(idContacto.toString()), null)?.use { it.count > 0 } ?: false
    }.getOrDefault(false)
}

/** Envía un SMS real de prueba (con confirmación) para ver cómo llega el tachado. */
fun Activity.enviarSmsPrueba() {
    if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 4)
        aviso("Concedé el permiso de SMS y volvé a tocar «Enviar SMS de prueba»")
        return
    }
    val lista = Contactos.todos(this).filter { it.numero.isNotBlank() }
    if (lista.isEmpty()) { aviso("Primero agregá tu número como contacto"); return }
    val ahora = System.currentTimeMillis()
    val antes = "Antes: ${Formato.completo(ahora)}"
    val texto = "Prueba de la app Recordatorios\n" +
        (if (Contactos.tachadoSms) Envios.tacharUnicode(antes) else antes) +
        " -> Nueva Fecha/Hora: ${Formato.completo(ahora + 3_600_000)}\n" +
        "Tachado en SMS: " + if (Contactos.tachadoSms) "ACTIVADO" else "desactivado"
    AlertDialog.Builder(this, temaDialogo())
        .setTitle("¿A qué número enviar la prueba?")
        .setItems(lista.map { "${it.etiqueta} (${it.numero})" }.toTypedArray()) { _, i ->
            val c = lista[i]
            AlertDialog.Builder(this, temaDialogo())
                .setTitle("Confirmar envío real")
                .setMessage("Se enviará un SMS REAL a ${c.etiqueta} (${c.numero}). Puede tener costo según tu plan.\n\n" +
                    "Tamaño: ${Envios.descripcionTamanio(texto)}\n\nTexto:\n$texto")
                .setPositiveButton("Enviar") { _, _ ->
                    Envios.enviarSms(this, c, texto)
                    aviso("SMS de prueba enviado a ${c.etiqueta}. Revisá el registro de eventos si no llega.")
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

/**
 * Si el SMS ocupa más de una parte, muestra el aviso: Enviar igual / Editar SMS / No enviar SMS.
 * [alDecidir] recibe el texto a enviar, o null si no se envía.
 */
fun Activity.controlarTamanioSms(texto: String, destinatarios: Int, alDecidir: (String?) -> Unit) {
    val partes = Envios.medir(texto)[0]
    if (partes <= 1) { alDecidir(texto); return }
    var decidido = false
    val c = ContextThemeWrapper(this, temaDialogo())
    val m = (20 * resources.displayMetrics.density).toInt()
    val caja = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(m, m / 2, m, 0)
        addView(TextView(c).apply {
            text = "Tamaño: ${Envios.descripcionTamanio(texto)}\n" +
                "Destinatarios por SMS: $destinatarios (cada uno recibe $partes SMS).\n\nTexto:\n$texto"
        })
    }
    var dialogo: AlertDialog? = null
    if (Envios.convertible(texto)) caja.addView(android.widget.Button(c).apply {
        text = "Convertir caracteres"
        setOnClickListener {
            decidido = true
            dialogo?.dismiss()
            controlarTamanioSms(Envios.convertirGsm(texto), destinatarios, alDecidir)   // vuelve a validar
        }
    })
    dialogo = AlertDialog.Builder(this, temaDialogo())
        .setTitle("El SMS se dividiría en $partes partes")
        .setView(ScrollView(c).apply { addView(caja) })
        .setPositiveButton("Enviar igual") { _, _ -> decidido = true; alDecidir(texto) }
        .setNeutralButton("Editar SMS") { _, _ -> decidido = true; editarSms(texto, destinatarios, alDecidir) }
        .setNegativeButton("No enviar SMS") { _, _ -> decidido = true; alDecidir(null) }
        .setOnDismissListener { if (!decidido) alDecidir(null) }
        .show()
}

/** Editor del SMS con contador en vivo de caracteres y cantidad de SMS. Solo afecta a este envío. */
private fun Activity.editarSms(texto: String, destinatarios: Int, alDecidir: (String?) -> Unit) {
    val c = ContextThemeWrapper(this, temaDialogo())
    val m = (20 * resources.displayMetrics.density).toInt()
    val contador = TextView(c).apply { textSize = 13f }
    val campo = EditText(c).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 4
        setText(texto)
    }
    fun refrescar() {
        val t = campo.text.toString()
        val partes = Envios.medir(t)[0]
        contador.text = Envios.descripcionTamanio(t) + if (partes <= 1) "  ✓ entra en 1 SMS" else ""
    }
    campo.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) = refrescar()
    })
    refrescar()
    val caja = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(m, m / 2, m, 0)
        addView(campo); addView(contador)
        addView(TextView(c).apply {
            text = "Solo cambia este envío; no modifica el texto del aviso en Ajustes."
            textSize = 12f
        })
    }
    var decidido = false
    AlertDialog.Builder(this, temaDialogo())
        .setTitle("Editar SMS")
        .setView(ScrollView(c).apply { addView(caja) })
        .setPositiveButton("Enviar") { _, _ ->
            decidido = true
            val nuevo = campo.text.toString()
            if (nuevo.isBlank()) { aviso("El SMS quedó vacío: no se envía"); alDecidir(null) }
            else controlarTamanioSms(nuevo, destinatarios, alDecidir)   // si sigue largo, vuelve a avisar
        }
        .setNegativeButton("No enviar SMS") { _, _ -> decidido = true; alDecidir(null) }
        .setOnDismissListener { if (!decidido) alDecidir(null) }
        .show()
}
