package com.misrecordatorios.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.content.res.Configuration
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import java.time.LocalDate
import java.time.LocalTime
import android.text.InputType
import android.view.ContextThemeWrapper
import android.widget.ScrollView
import java.time.ZonedDateTime

fun Activity.confirmarBorrado(r: Recordatorio, alTerminar: () -> Unit) {
    AlertDialog.Builder(this)
        .setTitle("Borrar recordatorio")
        .setMessage("¿Borrar «${r.titulo}»?")
        .setPositiveButton("Borrar") { _, _ -> Programador.borrar(this, r.id); alTerminar() }
        .setNegativeButton("Cancelar", null)
        .show()
}

fun Activity.temaDialogo(): Int {
    val nocturno = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    return if (nocturno) android.R.style.Theme_DeviceDefault_Dialog_Alert
           else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
}

/** Aplica la posposición y avisa hasta cuándo quedó. */
fun Activity.aplicarPosponer(id: Int, codigo: String): Boolean {
    val momento = Programador.posponer(this, id, codigo)
    val texto = if (momento != null) "Pospuesto hasta el " + Formato.completo(momento)
                else "No se pudo posponer: " + Programador.motivo
    Toast.makeText(this, texto, Toast.LENGTH_LONG).show()
    return momento != null
}

/** Set completo de opciones + "Personalizado…". [alCerrar] recibe true si se pospuso. */
fun Activity.elegirPosponer(id: Int, alCerrar: (Boolean) -> Unit) {
    val opciones = Posposicion.OPCIONES
    val textos = (opciones.map { it.texto } + "Personalizado…").toTypedArray()
    var pospuesto = false
    var sigue = false
    AlertDialog.Builder(this, temaDialogo())
        .setTitle("Posponer")
        .setItems(textos) { _, i ->
            if (i == opciones.size) {
                sigue = true
                elegirPersonalizado(id, alCerrar)
            } else {
                pospuesto = aplicarPosponer(id, opciones[i].codigo)
            }
        }
        .setNegativeButton("Cancelar", null)
        .setOnDismissListener { if (!sigue) alCerrar(pospuesto) }
        .show()
}

fun Activity.elegirPersonalizado(id: Int, alCerrar: (Boolean) -> Unit) {
    var sigue = false
    AlertDialog.Builder(this, temaDialogo())
        .setTitle("Personalizado")
        .setItems(arrayOf("Fecha y hora puntuales", "Plazo (número y unidad)")) { _, i ->
            sigue = true
            if (i == 0) elegirFechaHora(id, alCerrar) else elegirPlazo(id, alCerrar)
        }
        .setNegativeButton("Cancelar", null)
        .setOnDismissListener { if (!sigue) alCerrar(false) }
        .show()
}

private fun Activity.elegirFechaHora(id: Int, alCerrar: (Boolean) -> Unit) {
    val ahora = ZonedDateTime.now()
    var fechaElegida = false
    val dp = DatePickerDialog(this, temaDialogo(), { _, a, m, d ->
        fechaElegida = true
        // La hora arranca en la última usada en "Personalizado"; si es hoy y esa hora ya pasó, en la actual.
        val fecha = LocalDate.of(a, m + 1, d)
        val horaActual = LocalTime.now().withSecond(0).withNano(0)
        val ultima = Ajustes.ultimaHora(this)
        val inicial = if (ultima == null || (fecha == LocalDate.now() && !ultima.isAfter(horaActual))) horaActual else ultima
        fun pedirHora(desde: LocalTime) {
            elegirHora(desde, alCancelar = { alCerrar(false) }) { t ->
                val ms = ZonedDateTime.of(fecha, t, ahora.zone).toInstant().toEpochMilli()
                if (ms <= System.currentTimeMillis()) {
                    Toast.makeText(this, "Esa hora ya pasó. Elegí una hora futura.", Toast.LENGTH_LONG).show()
                    pedirHora(t)   // vuelve a mostrar el selector en lugar de fallar
                } else {
                    Ajustes.guardarUltimaHora(this, t)
                    alCerrar(aplicarPosponer(id, "t:$ms"))
                }
            }
        }
        pedirHora(inicial)
    }, ahora.year, ahora.monthValue - 1, ahora.dayOfMonth)
    dp.datePicker.minDate = System.currentTimeMillis() - 1000
    dp.setOnDismissListener { if (!fechaElegida) alCerrar(false) }
    dp.show()
}

private fun Activity.elegirPlazo(id: Int, alCerrar: (Boolean) -> Unit) {
    val c = ContextThemeWrapper(this, temaDialogo())
    val margen = (20 * resources.displayMetrics.density).toInt()
    val unidades = listOf("Minutos" to "m", "Horas" to "h", "Días" to "d",
        "Semanas" to "w", "Meses" to "M", "Años" to "y")
    val (ultimoN, ultimaU) = Ajustes.ultimoPlazo(this)
    val numero = EditText(c).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(ultimoN.toString())
        setSelectAllOnFocus(true)
    }
    val unidad = Spinner(c).apply {
        adapter = ArrayAdapter(c, android.R.layout.simple_spinner_dropdown_item, unidades.map { it.first })
        setSelection(unidades.indexOfFirst { it.second == ultimaU }.coerceAtLeast(0))
    }
    val caja = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(margen, margen / 2, margen, 0)
        addView(numero, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(unidad, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
    }
    var listo = false
    AlertDialog.Builder(this, temaDialogo())
        .setTitle("Posponer durante")
        .setView(caja)
        .setPositiveButton("Posponer") { _, _ ->
            listo = true
            val n = numero.text.toString().toIntOrNull()
            if (n == null || n <= 0 || n > 10_000) {
                Toast.makeText(this, "Ingresá un número entre 1 y 10000", Toast.LENGTH_LONG).show()
                alCerrar(false)
            } else {
                val u = unidades[unidad.selectedItemPosition].second
                Ajustes.guardarUltimoPlazo(this, n, u)
                alCerrar(aplicarPosponer(id, "$u:$n"))
            }
        }
        .setNegativeButton("Cancelar", null)
        .setOnDismissListener { if (!listo) alCerrar(false) }
        .show()
}

const val EXTRA_PERSONALIZADO = "personalizado"

/** Ventanita que se abre desde "Posponer…" (o "Posponer personalizado…") en la notificación. */
class PosponerActivity : Activity() {
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        val id = intent.getIntExtra(Programador.EXTRA_ID, -1)
        if (intent.getBooleanExtra(EXTRA_PERSONALIZADO, false)) elegirPersonalizado(id) { finish() }
        else elegirPosponer(id) { finish() }
    }
}

/** Pantalla de alarma: aparece sobre la pantalla bloqueada. */
class AlarmaActivity : Activity() {
    private var id = -1

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(R.layout.activity_alarma)
        cargar(intent)
    }

    override fun onNewIntent(nuevo: Intent) {
        super.onNewIntent(nuevo)
        setIntent(nuevo)
        cargar(nuevo)
    }

    private fun cargar(i: Intent) {
        id = i.getIntExtra(Programador.EXTRA_ID, -1)
        val r = Repositorio.obtener(this, id) ?: run { finish(); return }
        findViewById<TextView>(R.id.alHora).text = Formato.hora(System.currentTimeMillis())
        findViewById<TextView>(R.id.alTitulo).text = r.titulo
        val pospuesta = Notificador.esPospuesta(this, id)
        val nota = if (pospuesta) "Original vencido" + (if (r.nota.isBlank()) "" else "\n" + r.nota) else r.nota
        findViewById<TextView>(R.id.alNota).apply {
            text = nota
            visibility = if (nota.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.alWhatsapp).apply {
            visibility = if (Contactos.activos(this@AlarmaActivity).any { it.modo.whatsapp }) View.VISIBLE else View.GONE
            setOnClickListener { startActivity(Envios.intentWhatsapp(this@AlarmaActivity, id, pospuesta)) }
        }
        val callar = findViewById<Button>(R.id.alCallar)
        callar.isEnabled = true
        callar.text = "Callar"
        callar.setOnClickListener {
            Notificador.silenciar(this, id)
            callar.isEnabled = false
            callar.text = "Silenciada"
        }
        findViewById<Button>(R.id.alHecho).setOnClickListener {
            Notificador.quitar(this, id)
            finish()
        }
        val rapido = Ajustes.posponerRapido(this)
        findViewById<Button>(R.id.alPosponer5).apply {
            text = Posposicion.textoBoton(rapido)
            setOnClickListener {
                if (rapido == Posposicion.PERSONALIZADO) elegirPersonalizado(id) { pospuesto -> if (pospuesto) finish() }
                else if (aplicarPosponer(id, rapido)) finish()
            }
        }
        findViewById<Button>(R.id.alPosponerOtro).setOnClickListener {
            elegirPosponer(id) { pospuesto -> if (pospuesto) finish() }
        }
        findViewById<Button>(R.id.alCerrar).setOnClickListener {
            Notificador.silenciar(this, id)   // la notificación queda en la bandeja, sin sonido
            finish()
        }
    }
}

/** Pantalla principal: lista de recordatorios. */
class MainActivity : Activity() {
    private lateinit var lista: LinearLayout
    private lateinit var aviso: TextView

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        setContentView(R.layout.activity_main)
        lista = findViewById(R.id.lista)
        aviso = findViewById(R.id.aviso)
        findViewById<Button>(R.id.btnNuevo).setOnClickListener {
            startActivity(Intent(this, EditarActivity::class.java))
        }
        Notificador.crearCanales(this)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (estado == null) ofrecerCarpetaInicial()
    }

    @Deprecated("API de plataforma")
    override fun onActivityResult(codigo: Int, resultado: Int, datos: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(codigo, resultado, datos)
        if (codigo == PEDIDO_CARPETA && resultado == RESULT_OK) datos?.data?.let { carpetaElegida(it) { mostrarLista() } }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Ajustes")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) { startActivity(Intent(this, AjustesActivity::class.java)); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        revisarPermisos()
        mostrarLista()
    }

    override fun onRequestPermissionsResult(codigo: Int, permisos: Array<out String>, resultados: IntArray) {
        super.onRequestPermissionsResult(codigo, permisos, resultados)
        revisarPermisos()
    }

    private fun revisarPermisos() {
        val nm = getSystemService(NotificationManager::class.java)
        val am = getSystemService(AlarmManager::class.java)
        when {
            !nm.areNotificationsEnabled() -> mostrarAviso("Las notificaciones están desactivadas. Tocá acá para activarlas.") {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
            !am.canScheduleExactAlarms() -> mostrarAviso("Falta el permiso de alarmas exactas. Tocá acá para habilitarlo.") {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
            usaAlarmas() && !nm.canUseFullScreenIntent() ->
                mostrarAviso("Para que las alarmas aparezcan a pantalla completa falta un permiso. Tocá acá para habilitarlo.") {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                }
            else -> aviso.visibility = View.GONE
        }
    }

    private fun usaAlarmas() =
        Ajustes.alarmaPorDefecto(this) || Repositorio.todos(this).any { it.alarma && it.activo }

    private fun mostrarAviso(texto: String, alTocar: () -> Unit) {
        aviso.text = texto
        aviso.visibility = View.VISIBLE
        aviso.setOnClickListener { alTocar() }
    }

    private fun mostrarLista() {
        lista.removeAllViews()
        val todos = Repositorio.todos(this)
        if (todos.isEmpty()) {
            agregarTitulo("No hay recordatorios. Tocá «Nuevo recordatorio» para crear uno.")
            return
        }
        val proximos = todos.filter { it.activo }.sortedBy { it.proxima }
        val pospuestos = todos.filter { it.pospuestoHasta > 0 }.sortedBy { it.pospuestoHasta }
        val vencidos = todos.filter { !it.activo && it.pospuestoHasta == 0L }.sortedByDescending { it.proxima }
        if (proximos.isNotEmpty() || pospuestos.isNotEmpty()) agregarTitulo("Próximos")
        proximos.forEach { agregarItem(it) }
        if (pospuestos.isNotEmpty()) { agregarTitulo("   Pospuestos"); pospuestos.forEach { agregarItem(it, pospuesto = true) } }
        if (vencidos.isNotEmpty()) { agregarTitulo("Vencidos"); vencidos.forEach { agregarItem(it) } }
    }

    private fun agregarTitulo(texto: String) {
        val v = layoutInflater.inflate(R.layout.item_titulo, lista, false) as TextView
        v.text = texto
        lista.addView(v)
    }

    private fun agregarItem(r: Recordatorio, pospuesto: Boolean = false) {
        val v = layoutInflater.inflate(R.layout.item_recordatorio, lista, false)
        v.findViewById<TextView>(R.id.titulo).text =
            if (pospuesto) r.titulo + " {\"Original Vencido\"}" else r.titulo
        v.findViewById<TextView>(R.id.detalle).text =
            if (pospuesto) "Pospuesto hasta el " + Formato.completo(r.pospuestoHasta) else Formato.detalle(r)
        if (pospuesto) v.setPadding(v.paddingLeft * 3, v.paddingTop, v.paddingRight, v.paddingBottom)
        else if (!r.activo) v.alpha = 0.55f
        v.setOnClickListener {
            startActivity(Intent(this, EditarActivity::class.java).putExtra(Programador.EXTRA_ID, r.id))
        }
        v.setOnLongClickListener { confirmarBorrado(r) { mostrarLista() }; true }
        lista.addView(v)
    }
}

/** Crear o editar un recordatorio. */
class EditarActivity : Activity() {
    private var original: Recordatorio? = null
    private var fechaHora: ZonedDateTime = ZonedDateTime.now()
    private lateinit var etTitulo: EditText
    private lateinit var etNota: EditText
    private lateinit var btnFecha: Button
    private lateinit var btnHora: Button
    private lateinit var spRep: Spinner
    private lateinit var filaDias: LinearLayout
    private lateinit var cbAlarma: CheckBox
    private val checks = mutableListOf<CheckBox>()

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        setContentView(R.layout.activity_editar)
        etTitulo = findViewById(R.id.etTitulo)
        etNota = findViewById(R.id.etNota)
        btnFecha = findViewById(R.id.btnFecha)
        btnHora = findViewById(R.id.btnHora)
        spRep = findViewById(R.id.spRepeticion)
        filaDias = findViewById(R.id.filaDias)
        cbAlarma = findViewById(R.id.cbAlarma)
        val btnBorrar = findViewById<Button>(R.id.btnBorrar)

        listOf("L", "M", "X", "J", "V", "S", "D").forEachIndexed { i, letra ->
            val cb = CheckBox(this).apply { text = letra; tag = i + 1 }
            filaDias.addView(cb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            checks.add(cb)
        }

        spRep.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            Repeticion.entries.map { it.etiqueta })
        spRep.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = actualizarDias()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val id = intent.getIntExtra(Programador.EXTRA_ID, -1)
        original = if (id >= 0) Repositorio.obtener(this, id) else null
        val r = original
        if (r != null) {
            title = "Editar recordatorio"
            etTitulo.setText(r.titulo)
            etNota.setText(r.nota)
            fechaHora = Calculo.zdt(r.proxima)
            // Si es un vencido de un día anterior, la fecha arranca en hoy.
            if (!r.activo && fechaHora.toLocalDate().isBefore(LocalDate.now())) {
                fechaHora = ZonedDateTime.of(LocalDate.now(), fechaHora.toLocalTime(), fechaHora.zone)
            }
            spRep.setSelection(r.repeticion.ordinal)
            checks.forEach { it.isChecked = (it.tag as Int) in r.dias }
            cbAlarma.isChecked = r.alarma
            btnBorrar.visibility = View.VISIBLE
            btnBorrar.setOnClickListener { confirmarBorrado(r) { finish() } }
        } else {
            title = "Nuevo recordatorio"
            cbAlarma.isChecked = Ajustes.alarmaPorDefecto(this)
            fechaHora = ZonedDateTime.now().withSecond(0).withNano(0)
        }
        actualizarBotones()

        btnFecha.setOnClickListener {
            DatePickerDialog(this, { _, a, m, d ->
                fechaHora = ZonedDateTime.of(LocalDate.of(a, m + 1, d), fechaHora.toLocalTime(), fechaHora.zone)
                actualizarBotones()
            }, fechaHora.year, fechaHora.monthValue - 1, fechaHora.dayOfMonth).show()
        }
        btnHora.setOnClickListener {
            // El selector arranca en la hora actual.
            elegirHora(LocalTime.now().withSecond(0).withNano(0)) { t ->
                fechaHora = ZonedDateTime.of(fechaHora.toLocalDate(), t, fechaHora.zone)
                actualizarBotones()
            }
        }
        findViewById<Button>(R.id.btnGuardar).setOnClickListener { guardar() }
    }

    private fun repeticionElegida() = Repeticion.entries[spRep.selectedItemPosition]

    private fun actualizarBotones() {
        val ms = fechaHora.toInstant().toEpochMilli()
        btnFecha.text = Formato.fecha(ms)
        btnHora.text = Formato.hora(ms)
    }

    private fun actualizarDias() {
        val semanal = repeticionElegida() == Repeticion.SEMANAL
        filaDias.visibility = if (semanal) View.VISIBLE else View.GONE
        if (semanal && checks.none { it.isChecked }) checks[fechaHora.dayOfWeek.value - 1].isChecked = true
    }

    private fun aviso(texto: String) = Toast.makeText(this, texto, Toast.LENGTH_LONG).show()

    private fun guardar() {
        val titulo = etTitulo.text.toString().trim()
        if (titulo.isEmpty()) { aviso("Escribí un título"); return }
        val rep = repeticionElegida()
        val dias = checks.filter { it.isChecked }.map { it.tag as Int }.toMutableSet()
        if (rep == Repeticion.SEMANAL && dias.isEmpty()) { aviso("Elegí al menos un día"); return }
        val ms = fechaHora.toInstant().toEpochMilli()
        val ahora = System.currentTimeMillis()
        if (rep == Repeticion.NINGUNA && ms <= ahora) { aviso("Elegí una fecha y hora futura"); return }

        val r = Recordatorio(
            id = original?.id ?: Repositorio.nuevoId(this),
            titulo = titulo,
            nota = etNota.text.toString().trim(),
            proxima = ms,
            repeticion = rep,
            dias = if (rep == Repeticion.SEMANAL) dias else mutableSetOf(),
            diaMes = fechaHora.dayOfMonth,
            activo = true,
            alarma = cbAlarma.isChecked
        )
        Calculo.ajustarInicio(r, ahora)
        // Limpia lo anterior (alarma, posposición pendiente y notificación vieja) antes de reprogramar.
        Programador.cancelar(this, r.id)
        Notificador.quitar(this, r.id)
        Repositorio.guardar(this, r)
        Programador.programar(this, r)
        aviso("Guardado para el " + Formato.completo(r.proxima))
        val destinatarios = Contactos.resumen(this)
        if (destinatarios == null) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle("Aviso a contactos")
            .setMessage("Cuando suene este recordatorio se avisará a:\n\n$destinatarios\n\n" +
                "El SMS se envía solo; el WhatsApp queda listo para que lo envíes con un toque.")
            .setPositiveButton("Entendido", null)
            .setOnDismissListener { finish() }
            .show()
    }
}
