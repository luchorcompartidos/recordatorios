package com.misrecordatorios.app

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.InputFilter
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Registro de los últimos eventos, para diagnosticar en el celular. */
object Registro {
    private const val MAX = 80
    private val formato = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")

    fun anotar(ctx: Context, texto: String) {
        val p = ctx.getSharedPreferences("registro", Context.MODE_PRIVATE)
        val arr = JSONArray(p.getString("l", "[]"))
        arr.put(Calculo.zdt(System.currentTimeMillis()).format(formato) + " · " + texto)
        val nuevo = JSONArray()
        for (i in maxOf(0, arr.length() - MAX) until arr.length()) nuevo.put(arr.getString(i))
        p.edit().putString("l", nuevo.toString()).apply()
    }

    fun texto(ctx: Context): String {
        val arr = JSONArray(ctx.getSharedPreferences("registro", Context.MODE_PRIVATE).getString("l", "[]"))
        if (arr.length() == 0) return "Todavía no hay eventos."
        return (arr.length() - 1 downTo 0).joinToString("\n") { arr.getString(it) }
    }
}

/**
 * Copias de seguridad en una carpeta elegida por el usuario (sobreviven a la desinstalación).
 * - Copia previa: se crea la primera vez y solo se reemplaza al crear una copia manual.
 * - Copia actual: se actualiza sola con cada cambio y también al crear una copia manual.
 */
object Copia {
    const val PREVIA = "recordatorios-copia-previa.json"
    const val ACTUAL = "recordatorios-copia-actual.json"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("copia", Context.MODE_PRIVATE)

    fun carpeta(ctx: Context): Uri? = prefs(ctx).getString("carpeta", null)?.let(Uri::parse)
    private fun lista(ctx: Context) = prefs(ctx).getBoolean("lista", false)
    private fun marcarLista(ctx: Context) = prefs(ctx).edit().putBoolean("lista", true).apply()
    fun ultimaManual(ctx: Context) = prefs(ctx).getLong("ultima_manual", 0)

    fun nombreCarpeta(ctx: Context): String? {
        val c = carpeta(ctx) ?: return null
        return DocumentsContract.getTreeDocumentId(c).substringAfter(':').ifBlank { "Almacenamiento principal" }
    }

    /** Guarda la carpeta elegida con permiso permanente. */
    fun configurar(ctx: Context, arbol: Uri): Boolean = runCatching {
        ctx.contentResolver.takePersistableUriPermission(arbol,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs(ctx).edit().putString("carpeta", arbol.toString()).putBoolean("lista", false).apply()
        true
    }.getOrDefault(false)

    private fun buscar(ctx: Context, arbol: Uri, nombre: String): Uri? {
        val hijos = DocumentsContract.buildChildDocumentsUriUsingTree(arbol, DocumentsContract.getTreeDocumentId(arbol))
        ctx.contentResolver.query(hijos, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == nombre) return DocumentsContract.buildDocumentUriUsingTree(arbol, c.getString(0))
            }
        }
        return null
    }

    private fun escribir(ctx: Context, nombre: String, texto: String): Boolean {
        val arbol = carpeta(ctx) ?: return false
        return runCatching {
            val uri = buscar(ctx, arbol, nombre) ?: DocumentsContract.createDocument(ctx.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(arbol, DocumentsContract.getTreeDocumentId(arbol)),
                "application/json", nombre)!!
            ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(texto.toByteArray()) }
            true
        }.getOrDefault(false)
    }

    private fun leer(ctx: Context, nombre: String): String? {
        val arbol = carpeta(ctx) ?: return null
        return runCatching {
            val uri = buscar(ctx, arbol, nombre)!!
            ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }

    /** Fecha de la copia, o null si no existe. */
    fun fecha(ctx: Context, nombre: String): Long? =
        leer(ctx, nombre)?.let { runCatching { JSONObject(it).optLong("actualizada") }.getOrNull() }

    private fun contenido(ctx: Context): String = JSONObject()
        .put("version", 1)
        .put("actualizada", System.currentTimeMillis())
        .put("ultima_manual", ultimaManual(ctx))
        .put("datos", Repositorio.exportar(ctx))
        .put("ajustes", Ajustes.exportar(ctx))
        .toString(1)

    /** Primera vez: crea la copia previa (si no existía) y la actual. */
    fun inicial(ctx: Context) {
        val texto = contenido(ctx)
        if (fecha(ctx, PREVIA) == null) escribir(ctx, PREVIA, texto)
        escribir(ctx, ACTUAL, texto)
        marcarLista(ctx)
        Registro.anotar(ctx, "Copias de seguridad iniciales creadas")
    }

    /** Se llama sola en cada cambio de los recordatorios. No toca la copia previa. */
    fun automatica(ctx: Context) {
        if (carpeta(ctx) == null || !lista(ctx)) return
        escribir(ctx, ACTUAL, contenido(ctx))
    }

    /** Manual: la copia actual vigente pasa a ser la previa y se crea una actual nueva. */
    fun manual(ctx: Context): Boolean {
        if (carpeta(ctx) == null) return false
        leer(ctx, ACTUAL)?.let { escribir(ctx, PREVIA, it) }
        prefs(ctx).edit().putLong("ultima_manual", System.currentTimeMillis()).apply()
        marcarLista(ctx)
        val ok = escribir(ctx, ACTUAL, contenido(ctx))
        Registro.anotar(ctx, if (ok) "Copia manual creada" else "Falló la copia manual")
        return ok
    }

    fun restaurar(ctx: Context, nombre: String): Boolean = runCatching {
        val obj = JSONObject(leer(ctx, nombre)!!)
        Programador.cancelarTodo(ctx)
        Repositorio.importar(ctx, obj.getJSONObject("datos"))
        obj.optJSONObject("ajustes")?.let { Ajustes.importar(ctx, it) }
        marcarLista(ctx)
        Programador.reprogramarTodos(ctx, avisarPerdidos = false)
        Registro.anotar(ctx, "Restaurado desde $nombre")
        true
    }.getOrDefault(false)
}

const val PEDIDO_CARPETA = 77

fun Activity.aviso(texto: String) = Toast.makeText(this, texto, Toast.LENGTH_LONG).show()

@Suppress("DEPRECATION")
fun Activity.pedirCarpeta() {
    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION), PEDIDO_CARPETA)
}

/** Al abrir la app, si todavía no hay carpeta de copias, la pide. */
fun Activity.ofrecerCarpetaInicial() {
    if (Copia.carpeta(this) != null) return
    AlertDialog.Builder(this, temaDialogo())
        .setTitle("Copia de seguridad")
        .setMessage("Elegí una carpeta del celular (por ejemplo, Documentos) para guardar las copias. " +
            "Así vas a poder recuperar tus recordatorios aunque desinstales la app.")
        .setPositiveButton("Elegir carpeta") { _, _ -> pedirCarpeta() }
        .setNegativeButton("Más tarde", null)
        .show()
}

/** Después de elegir carpeta: si ya hay copias ahí, ofrece restaurarlas antes de reemplazar nada. */
fun Activity.carpetaElegida(uri: Uri, alTerminar: () -> Unit) {
    if (!Copia.configurar(this, uri)) { aviso("No se pudo usar esa carpeta"); return }
    val previa = Copia.fecha(this, Copia.PREVIA)
    val actual = Copia.fecha(this, Copia.ACTUAL)
    if (previa == null && actual == null) {
        Copia.inicial(this)
        aviso("Copia previa y copia actual creadas")
        alTerminar()
        return
    }
    val opciones = mutableListOf<Pair<String, () -> Unit>>()
    actual?.let { opciones += "Restaurar copia actual (${Formato.completo(it)})" to { restaurarCopia(Copia.ACTUAL, alTerminar) } }
    previa?.let { opciones += "Restaurar copia previa (${Formato.completo(it)})" to { restaurarCopia(Copia.PREVIA, alTerminar) } }
    opciones += "No restaurar (la copia actual se reemplaza; la previa se conserva)" to {
        Copia.inicial(this); alTerminar()
    }
    AlertDialog.Builder(this, temaDialogo())
        .setTitle("Se encontraron copias en esta carpeta")
        .setItems(opciones.map { it.first }.toTypedArray()) { _, i -> opciones[i].second() }
        .setCancelable(false)
        .show()
}

private fun Activity.restaurarCopia(nombre: String, alTerminar: () -> Unit) {
    aviso(if (Copia.restaurar(this, nombre)) "Recordatorios restaurados" else "No se pudo restaurar la copia")
    alTerminar()
}

/** Recordatorio de prueba para el minuto siguiente exacto. */
fun crearPrueba(ctx: Context): Recordatorio {
    val momento = ZonedDateTime.now().withSecond(0).withNano(0).plusMinutes(1)
    val r = Recordatorio(
        id = Repositorio.nuevoId(ctx),
        titulo = "Prueba",
        nota = "Recordatorio de prueba creado desde Ajustes",
        proxima = momento.toInstant().toEpochMilli(),
        repeticion = Repeticion.NINGUNA,
        dias = mutableSetOf(),
        diaMes = momento.dayOfMonth,
        activo = true,
        alarma = Ajustes.alarmaPorDefecto(ctx)
    )
    Repositorio.guardar(ctx, r)
    Programador.programar(ctx, r)
    return r
}

/** Repite la acción mientras se mantiene presionado el botón. */
private fun repetirAlMantener(v: View, accion: () -> Unit) {
    val h = Handler(Looper.getMainLooper())
    val repetir = object : Runnable {
        override fun run() { accion(); h.postDelayed(this, 110) }
    }
    v.setOnTouchListener { vista, e ->
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { accion(); h.postDelayed(repetir, 450) }
            MotionEvent.ACTION_UP -> { h.removeCallbacks(repetir); vista.performClick() }
            MotionEvent.ACTION_CANCEL -> h.removeCallbacks(repetir)
        }
        true
    }
}

/** Selector de hora con flechas ▲▼, teclado y acceso al reloj del sistema (sincronizados). */
fun Activity.elegirHora(inicial: LocalTime, alCancelar: () -> Unit = {}, alElegir: (LocalTime) -> Unit) {
    val c = ContextThemeWrapper(this, temaDialogo())
    val dp = resources.displayMetrics.density
    fun dos(v: Int) = "%02d".format(v)
    fun campo(valor: Int) = EditText(c).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(2))
        gravity = Gravity.CENTER
        textSize = 34f
        imeOptions = EditorInfo.IME_ACTION_NEXT
        setSelectAllOnFocus(true)
        setText(dos(valor))
    }
    val hh = campo(inicial.hour)
    val mm = campo(inicial.minute)
    fun leer(e: EditText, max: Int) = (e.text.toString().toIntOrNull() ?: 0).coerceIn(0, max)
    fun mover(e: EditText, max: Int, delta: Int) { e.setText(dos((leer(e, max) + delta + max + 1) % (max + 1))) }
    fun columna(e: EditText, max: Int) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val arriba = Button(c).apply { text = "▲" }
        val abajo = Button(c).apply { text = "▼" }
        repetirAlMantener(arriba) { mover(e, max, +1) }
        repetirAlMantener(abajo) { mover(e, max, -1) }
        addView(arriba)
        addView(e, LinearLayout.LayoutParams((96 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(abajo)
    }
    val fila = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(0, (12 * dp).toInt(), 0, 0)
        addView(columna(hh, 23))
        addView(TextView(c).apply { text = ":"; textSize = 34f; setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0) })
        addView(columna(mm, 59))
    }
    var aceptado = false
    val dialogo = AlertDialog.Builder(this, temaDialogo())
        .setTitle("Hora")
        .setView(fila)
        .setPositiveButton("Aceptar") { _, _ -> aceptado = true; alElegir(LocalTime.of(leer(hh, 23), leer(mm, 59))) }
        .setNeutralButton("Ver reloj", null)
        .setNegativeButton("Cancelar", null)
        .create()
    dialogo.setOnShowListener {
        dialogo.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            TimePickerDialog(this, temaDialogo(), { _, h, m -> hh.setText(dos(h)); mm.setText(dos(m)) },
                leer(hh, 23), leer(mm, 59), true).show()
        }
    }
    dialogo.setOnDismissListener { if (!aceptado) alCancelar() }
    dialogo.show()
}

/** Ajustes generales. */
class AjustesActivity : Activity() {
    private lateinit var estadoCopia: TextView

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        title = "Ajustes"
        val m = (16 * resources.displayMetrics.density).toInt()
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(m, 0, m, m)
        }
        fun seccion(t: String) = raiz.addView(TextView(this).apply {
            text = t
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, m * 2, 0, m / 2)
        })
        fun texto(t: String) = TextView(this).also { it.text = t; raiz.addView(it) }
        fun boton(t: String, accion: (Button) -> Unit) =
            Button(this).also { b -> b.text = t; b.setOnClickListener { accion(b) }; raiz.addView(b) }

        seccion("ALARMA SONORA")
        raiz.addView(Switch(this).apply {
            text = "Crear los recordatorios nuevos con alarma sonora"
            textSize = 16f
            isChecked = Ajustes.alarmaPorDefecto(this@AjustesActivity)
            setOnCheckedChangeListener { _, v -> Ajustes.guardarAlarmaPorDefecto(this@AjustesActivity, v) }
        })
        texto("Solo define cómo aparece marcada la opción al crear un recordatorio. Siempre se puede cambiar en cada uno.")

        seccion("POSPONER")
        texto("Botón rápido en las notificaciones:")
        boton(Posposicion.texto(Ajustes.posponerRapido(this))) { b ->
            val ops = Posposicion.OPCIONES_RAPIDO
            val actual = ops.indexOfFirst { it.codigo == Ajustes.posponerRapido(this) }
            AlertDialog.Builder(this, temaDialogo())
                .setTitle("Posponer rápido")
                .setSingleChoiceItems(ops.map { it.texto }.toTypedArray(), actual) { d, i ->
                    Ajustes.guardarPosponerRapido(this, ops[i].codigo)
                    b.text = ops[i].texto
                    d.dismiss()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        seccion("AVISOS A CONTACTOS")
        texto("Cuando suena un recordatorio se avisa a estos números. Tocá un contacto para editarlo o elegir el medio: " +
            "SMS (se envía solo), WhatsApp (queda listo para enviar con un toque) o ambos.")
        val contenedorContactos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        raiz.addView(contenedorContactos)
        armarContactos(contenedorContactos)
        boton("Agregar contacto") { editarContacto(contenedorContactos, -1) }

        seccion("COPIA DE SEGURIDAD")
        estadoCopia = texto("")
        boton("Crear copia de seguridad") {
            if (Copia.carpeta(this) == null) pedirCarpeta()
            else {
                aviso(if (Copia.manual(this)) "Copia creada. La anterior quedó como copia previa." else "No se pudo crear la copia")
                refrescarCopia()
            }
        }
        boton("Restaurar desde copia…") { elegirRestauracion() }
        boton("Cambiar carpeta") { pedirCarpeta() }
        refrescarCopia()

        seccion("PRUEBAS")
        boton("Crear recordatorio de prueba") {
            val r = crearPrueba(this)
            aviso("Recordatorio de prueba programado para las " + Formato.hora(r.proxima))
        }
        boton("Ver registro de eventos") {
            AlertDialog.Builder(this, temaDialogo())
                .setTitle("Registro de eventos")
                .setMessage(Registro.texto(this))
                .setPositiveButton("Cerrar", null)
                .show()
        }

        seccion("ACERCA DE")
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        texto("Versión instalada: $version")
        boton("Ver cambios (ChangeLog)") {
            AlertDialog.Builder(this, temaDialogo())
                .setTitle("Historial de cambios")
                .setMessage(Cambios.texto())
                .setPositiveButton("Cerrar", null)
                .show()
        }

        setContentView(ScrollView(this).apply { addView(raiz) })
    }

    private fun refrescarCopia() {
        val carpeta = Copia.nombreCarpeta(this)
        if (carpeta == null) { estadoCopia.text = "Todavía no elegiste una carpeta para las copias."; return }
        fun f(ms: Long?) = if (ms == null || ms == 0L) "no existe" else Formato.completo(ms)
        estadoCopia.text = "Carpeta: $carpeta\n" +
            "Copia previa: ${f(Copia.fecha(this, Copia.PREVIA))}\n" +
            "Copia actual: ${f(Copia.fecha(this, Copia.ACTUAL))} (se actualiza sola)\n" +
            "Última copia manual: ${f(Copia.ultimaManual(this))}"
    }

    private fun elegirRestauracion() {
        if (Copia.carpeta(this) == null) { pedirCarpeta(); return }
        val opciones = listOf(Copia.ACTUAL to "Copia actual", Copia.PREVIA to "Copia previa")
            .mapNotNull { (n, t) -> Copia.fecha(this, n)?.let { Triple(n, t, it) } }
        if (opciones.isEmpty()) { aviso("No hay copias en la carpeta elegida"); return }
        AlertDialog.Builder(this, temaDialogo())
            .setTitle("Restaurar")
            .setItems(opciones.map { "${it.second} (${Formato.completo(it.third)})" }.toTypedArray()) { _, i ->
                AlertDialog.Builder(this, temaDialogo())
                    .setMessage("Esto reemplaza los recordatorios que hay ahora en la app. ¿Continuar?")
                    .setPositiveButton("Restaurar") { _, _ ->
                        aviso(if (Copia.restaurar(this, opciones[i].first)) "Recordatorios restaurados" else "No se pudo restaurar")
                        refrescarCopia()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    @Deprecated("API de plataforma")
    override fun onActivityResult(codigo: Int, resultado: Int, datos: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(codigo, resultado, datos)
        if (codigo == PEDIDO_CARPETA && resultado == RESULT_OK) datos?.data?.let { carpetaElegida(it) { refrescarCopia() } }
    }
}
