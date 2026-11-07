package com.misrecordatorios.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.app.KeyguardManager
import android.widget.ImageView
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
    private var recId = -1   // id del recordatorio (no confundir con el id de una vista)

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
        recId = i.getIntExtra(Programador.EXTRA_ID, -1)
        val r = Repositorio.obtener(this, recId) ?: run { finish(); return }
        findViewById<TextView>(R.id.alHora).text = Formato.hora(System.currentTimeMillis())
        findViewById<TextView>(R.id.alTitulo).text = r.titulo
        val pospuesta = Notificador.esPospuesta(this, recId)
        val nota = if (pospuesta) "{\"Original Vencido\"}" + (if (r.nota.isBlank()) "" else "\n" + r.nota) else r.nota
        findViewById<TextView>(R.id.alNota).apply {
            text = nota
            visibility = if (nota.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.alWhatsapp).apply {
            val wa = Envios.destinatarios(this@AlarmaActivity, r).filter { it.modo.whatsapp }
            visibility = if (wa.isNotEmpty()) View.VISIBLE else View.GONE
            setOnClickListener {
                Registro.anotar(this@AlarmaActivity, "#$recId botón WhatsApp de la pantalla completa")
                Notificador.silenciar(this@AlarmaActivity, recId)
                startActivity(Envios.intentWhatsapp(this@AlarmaActivity, recId, Envios.AHORA, pospuesta, wa.map { it.id }))
            }
        }
        val callar = findViewById<Button>(R.id.alCallar)
        callar.isEnabled = true
        callar.text = "Callar"
        callar.setOnClickListener {
            Notificador.silenciar(this, recId)
            callar.isEnabled = false
            callar.text = "Silenciada"
        }
        findViewById<Button>(R.id.alHecho).setOnClickListener {
            Notificador.quitar(this, recId)
            finish()
        }
        val rapido = Ajustes.posponerRapido(this)
        findViewById<Button>(R.id.alPosponer5).apply {
            text = Posposicion.textoBoton(rapido)
            setOnClickListener {
                if (rapido == Posposicion.PERSONALIZADO) elegirPersonalizado(recId) { pospuesto -> if (pospuesto) finish() }
                else if (aplicarPosponer(recId, rapido)) finish()
            }
        }
        findViewById<Button>(R.id.alPosponerOtro).setOnClickListener {
            elegirPosponer(recId) { pospuesto -> if (pospuesto) finish() }
        }
        findViewById<Button>(R.id.alEditar).setOnClickListener {
            Notificador.silenciar(this, recId)
            conDesbloqueo {
                startActivity(Intent(this, EditarActivity::class.java).putExtra(Programador.EXTRA_ID, recId))
                finish()
            }
        }
        findViewById<Button>(R.id.alCerrar).setOnClickListener {
            Notificador.silenciar(this, recId)   // la notificación queda en la bandeja, sin sonido
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
        findViewById<Button>(R.id.btnSms).setOnClickListener {
            val suspendidos = SuspensionSms.activa(this)
            AlertDialog.Builder(this, temaDialogo())
                .setTitle(if (suspendidos) "Reanudar SMS" else "Suspender/Anular SMS")
                .setMessage(if (suspendidos)
                    "Se vuelven a permitir los SMS, pero se arranca de cero: los recordatorios afectados por la " +
                        "suspensión siguen SIN SMS. Para que uno vuelva a enviarlos, editalo y desmarcá «Anular envío de SMS». " +
                        "Los recordatorios nuevos usan SMS según cada contacto."
                else
                    "Se anulan los SMS de todos los recordatorios vigentes (los programados dejan de existir) y no se " +
                        "envía ningún SMS mientras dure la suspensión, tampoco al crear o editar. WhatsApp sigue igual.")
                .setPositiveButton(if (suspendidos) "Reanudar" else "Suspender/Anular") { _, _ ->
                    if (suspendidos) SuspensionSms.reanudar(this) else SuspensionSms.suspender(this)
                    actualizarSms()
                    mostrarLista()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        findViewById<Button>(R.id.btnFiltro).setOnClickListener { elegirFiltro() }
        findViewById<Button>(R.id.btnOrden).setOnClickListener { elegirOrden() }
        findViewById<Button>(R.id.btnMostrarNotas).setOnClickListener { guardarColapsadas(emptySet()); mostrarLista() }
        findViewById<Button>(R.id.btnOcultarNotas).setOnClickListener {
            val todos = Repositorio.todos(this)
            guardarColapsadas(todos.filter { textoNota(it, it.pospuestoHasta > 0).isNotEmpty() }.map { it.id.toString() }.toSet())
            expandidas.clear()
            mostrarLista()
        }
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

    private fun actualizarSms() {
        val suspendidos = SuspensionSms.activa(this)
        findViewById<TextView>(R.id.avisoSms).visibility = if (suspendidos) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnSms).text = if (suspendidos) "Reanudar SMS" else "Suspender/Anular SMS"
    }

    override fun onResume() {
        super.onResume()
        actualizarSms()
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
        actualizarFiltroYOrden()
        val rango = rangoFiltro()
        val visibles = if (rango == null) todos else todos.filter { Calculo.ocurreEntre(it, rango.first, rango.second) }
        if (visibles.isEmpty()) { agregarTitulo("No hay recordatorios para ese período."); return }
        val proximos = ordenar(visibles.filter { it.activo }) { it.proxima }
        val pospuestos = ordenar(visibles.filter { it.pospuestoHasta > 0 }) { it.pospuestoHasta }
        val vencidos = ordenar(visibles.filter { !it.activo && it.pospuestoHasta == 0L }) { it.proxima }
        if (proximos.isNotEmpty() || pospuestos.isNotEmpty()) agregarTitulo("Próximos")
        proximos.forEach { agregarItem(it) }
        if (pospuestos.isNotEmpty()) { agregarTitulo("Pospuestos", sub = true); pospuestos.forEach { agregarItem(it, pospuesto = true) } }
        if (vencidos.isNotEmpty()) { agregarTitulo("Vencidos"); vencidos.forEach { agregarItem(it) } }
    }

    // ---------- Filtro por fechas y orden (se recuerdan) ----------
    private fun vista() = getSharedPreferences("vista", MODE_PRIVATE)
    private val FILTROS = listOf("todos" to "Todos", "hoy" to "Hoy", "manana" to "Mañana",
        "semana" to "Próximos 7 días", "dia" to "Elegir día…")
    private val ORDENES = listOf("fecha_asc" to "Fecha (más próxima primero)", "fecha_desc" to "Fecha (más lejana primero)",
        "titulo_az" to "Título (A-Z)", "titulo_za" to "Título (Z-A)",
        "nuevos" to "Creación (más nuevos primero)", "antiguos" to "Creación (más antiguos primero)")

    /** Desde/hasta (en ms) del filtro elegido, o null si se ven todos. */
    private fun rangoFiltro(): Pair<Long, Long>? {
        val hoy = LocalDate.now()
        val (desde, dias) = when (vista().getString("filtro", "todos")) {
            "hoy" -> hoy to 1L
            "manana" -> hoy.plusDays(1) to 1L
            "semana" -> hoy to 7L
            "dia" -> LocalDate.ofEpochDay(vista().getLong("filtro_dia", hoy.toEpochDay())) to 1L
            else -> return null
        }
        val zona = java.time.ZoneId.systemDefault()
        return desde.atStartOfDay(zona).toInstant().toEpochMilli() to
            desde.plusDays(dias).atStartOfDay(zona).toInstant().toEpochMilli() - 1
    }

    private fun textoFiltro(): String {
        val f = vista().getString("filtro", "todos")
        return if (f == "dia") {
            val d = LocalDate.ofEpochDay(vista().getLong("filtro_dia", LocalDate.now().toEpochDay()))
            Formato.fecha(d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
        } else FILTROS.first { it.first == f }.second
    }

    private fun ordenar(l: List<Recordatorio>, fecha: (Recordatorio) -> Long): List<Recordatorio> =
        when (vista().getString("orden", "fecha_asc")) {
            "fecha_desc" -> l.sortedByDescending(fecha)
            "titulo_az" -> l.sortedBy { it.titulo.lowercase() }
            "titulo_za" -> l.sortedByDescending { it.titulo.lowercase() }
            "nuevos" -> l.sortedByDescending { it.id }
            "antiguos" -> l.sortedBy { it.id }
            else -> l.sortedBy(fecha)
        }

    private fun actualizarFiltroYOrden() {
        val f = vista().getString("filtro", "todos")
        findViewById<Button>(R.id.btnFiltro).text = "Filtro: " + textoFiltro() + " ▾"
        findViewById<Button>(R.id.btnOrden).text = "Orden ▾"
        findViewById<TextView>(R.id.avisoFiltro).apply {
            visibility = if (f == "todos") View.GONE else View.VISIBLE
            text = "Mostrando solo: ${textoFiltro()} · tocá para ver todos"
            setOnClickListener { vista().edit().putString("filtro", "todos").apply(); mostrarLista() }
        }
    }

    private fun elegirFiltro() {
        AlertDialog.Builder(this, temaDialogo())
            .setTitle("Filtrar por fecha")
            .setItems(FILTROS.map { it.second }.toTypedArray()) { _, i ->
                val clave = FILTROS[i].first
                if (clave == "dia") {
                    val hoy = LocalDate.now()
                    DatePickerDialog(this, temaDialogo(), { _, a, m, d ->
                        vista().edit().putString("filtro", "dia").putLong("filtro_dia", LocalDate.of(a, m + 1, d).toEpochDay()).apply()
                        mostrarLista()
                    }, hoy.year, hoy.monthValue - 1, hoy.dayOfMonth).show()
                } else {
                    vista().edit().putString("filtro", clave).apply()
                    mostrarLista()
                }
            }
            .show()
    }

    private fun elegirOrden() {
        val actual = ORDENES.indexOfFirst { it.first == vista().getString("orden", "fecha_asc") }
        AlertDialog.Builder(this, temaDialogo())
            .setTitle("Ordenar por")
            .setSingleChoiceItems(ORDENES.map { it.second }.toTypedArray(), actual) { d, i ->
                vista().edit().putString("orden", ORDENES[i].first).apply()
                d.dismiss()
                mostrarLista()
            }
            .show()
    }

    private fun agregarTitulo(texto: String, sub: Boolean = false) {
        val v = layoutInflater.inflate(R.layout.item_titulo, lista, false) as TextView
        v.text = texto
        if (sub) {
            v.textSize = 13f
            v.setPadding(v.paddingLeft * 3, v.paddingTop / 2, v.paddingRight, v.paddingBottom)
        } else {
            v.textSize = 19f
        }
        lista.addView(v)
    }

    // Notas: colapsadas (se recuerda) y "Mostrar más" (mientras la pantalla está abierta).
    private val expandidas = mutableSetOf<Int>()
    private fun colapsadas(): MutableSet<String> =
        getSharedPreferences("vista", MODE_PRIVATE).getStringSet("colapsadas", emptySet())!!.toMutableSet()
    private fun guardarColapsadas(c: Set<String>) =
        getSharedPreferences("vista", MODE_PRIVATE).edit().putStringSet("colapsadas", c).apply()

    private fun textoNota(r: Recordatorio, pospuesto: Boolean): String {
        val partes = mutableListOf<String>()
        if (pospuesto) partes += "{\"Original Vencido\"}"
        if (r.nota.isNotBlank()) partes += r.nota
        return partes.joinToString("\n")
    }

    private fun agregarItem(r: Recordatorio, pospuesto: Boolean = false) {
        val v = layoutInflater.inflate(R.layout.item_recordatorio, lista, false)
        v.findViewById<TextView>(R.id.titulo).text =
            r.titulo
        v.findViewById<TextView>(R.id.detalle).text =
            if (pospuesto) "Pospuesto hasta el " + Formato.completo(r.pospuestoHasta) else Formato.detalle(r)
        if (pospuesto) v.setPadding(v.paddingLeft * 3, v.paddingTop, v.paddingRight, v.paddingBottom)
        else if (!r.activo) v.alpha = 0.55f
        val nota = textoNota(r, pospuesto)
        if (nota.isNotEmpty()) {
            val clave = r.id.toString()
            val colapsada = clave in colapsadas()
            val tvNota = v.findViewById<TextView>(R.id.nota)
            val tvMas = v.findViewById<TextView>(R.id.masNota)
            val tvContiene = v.findViewById<TextView>(R.id.contieneNotas)
            v.findViewById<ImageView>(R.id.colapsar).apply {
                visibility = View.VISIBLE
                setImageResource(if (colapsada) android.R.drawable.arrow_down_float else android.R.drawable.arrow_up_float)
                contentDescription = if (colapsada) "Mostrar nota" else "Ocultar nota"
                setOnClickListener {
                    val c = colapsadas()
                    if (colapsada) c -= clave else c += clave
                    guardarColapsadas(c)
                    mostrarLista()
                }
            }
            if (colapsada) {
                tvContiene.visibility = View.VISIBLE
                tvContiene.setOnClickListener { guardarColapsadas(colapsadas() - clave); mostrarLista() }
            } else {
                tvNota.text = nota
                tvNota.visibility = View.VISIBLE
                val completa = r.id in expandidas
                tvNota.maxLines = if (completa) Int.MAX_VALUE else 3
                tvMas.setOnClickListener {
                    if (completa) expandidas -= r.id else expandidas += r.id
                    mostrarLista()
                }
                if (completa) {
                    tvMas.text = "Mostrar menos"
                    tvMas.visibility = View.VISIBLE
                } else tvNota.post {
                    val l = tvNota.layout ?: return@post
                    if (l.lineCount > 3 || l.getEllipsisCount(l.lineCount - 1) > 0) {
                        tvMas.text = "Mostrar más..."
                        tvMas.visibility = View.VISIBLE
                    }
                }
            }
        }
        v.setOnClickListener {
            startActivity(Intent(this, EditarActivity::class.java).putExtra(Programador.EXTRA_ID, r.id))
        }
        v.setOnLongClickListener {
            AlertDialog.Builder(this, temaDialogo())
                .setTitle(r.titulo)
                .setItems(arrayOf("Editar", "Replicar", "Borrar")) { _, i ->
                    when (i) {
                        0 -> startActivity(Intent(this, EditarActivity::class.java).putExtra(Programador.EXTRA_ID, r.id))
                        1 -> startActivity(Intent(this, EditarActivity::class.java).putExtra(EXTRA_REPLICAR, r.id))
                        else -> confirmarBorrado(r) { mostrarLista() }
                    }
                }
                .show()
            true
        }
        lista.addView(v)
    }
}

/** Crear o editar un recordatorio. */
const val EXTRA_REPLICAR = "replicar"

/** Crear, editar o replicar un recordatorio. */
class EditarActivity : Activity() {
    private var original: Recordatorio? = null
    private var replica = false
    private var fechaHora: ZonedDateTime = ZonedDateTime.now()
    private lateinit var etTitulo: EditText
    private lateinit var etNota: EditText
    private lateinit var btnFecha: Button
    private lateinit var btnHora: Button
    private lateinit var spRep: Spinner
    private lateinit var filaDias: LinearLayout
    private lateinit var filaNSemana: LinearLayout
    private lateinit var spOrdinal: Spinner
    private lateinit var spDiaSemana: Spinner
    private lateinit var filaTerminar: LinearLayout
    private lateinit var spTerminar: Spinner
    private lateinit var etVeces: EditText
    private lateinit var tvVeces: TextView
    private lateinit var cbAlarma: CheckBox
    private lateinit var btnContactos: Button
    private lateinit var listaAvisos: LinearLayout
    private val seleccion = mutableSetOf<Int>()
    private val excluidos = mutableSetOf<Int>()
    private val checks = mutableListOf<CheckBox>()

    private fun actualizarContactos() {
        val activos = Contactos.activos(this).map { it.id }.toSet()
        btnContactos.text = "Contactos a avisar (${seleccion.count { it in activos && it !in excluidos }})"
        armarListaAvisos()
    }

    /** Lista de contactos de avisos debajo de "Borrar": casilla = recibe avisos; "Eliminar" lo quita de la lista. */
    private fun armarListaAvisos() {
        listaAvisos.removeAllViews()
        val todos = Contactos.todos(this).associateBy { it.id }
        val activos = Contactos.activos(this).map { it.id }.toSet()
        val enLista = seleccion.mapNotNull { todos[it] }
        listaAvisos.addView(TextView(this).apply {
            text = "Contactos de avisos de este recordatorio"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 24, 0, 4)
        })
        if (enLista.isEmpty()) {
            listaAvisos.addView(TextView(this).apply { text = "Sin contactos. Agregalos con «Contactos a avisar»." })
            return
        }
        listaAvisos.addView(TextView(this).apply {
            text = "Desmarcado = queda en la lista pero no recibe avisos. Los cambios se aplican al Guardar."
            textSize = 12f
        })
        enLista.forEach { c ->
            listaAvisos.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(CheckBox(this@EditarActivity).apply {
                    text = "${c.etiqueta} — ${c.modo.texto}" + if (c.id !in activos) " (desactivado en Ajustes)" else ""
                    isChecked = c.id !in excluidos
                    setOnCheckedChangeListener { _, v ->
                        if (v) excluidos -= c.id else excluidos += c.id
                        val n = seleccion.count { it in activos && it !in excluidos }
                        btnContactos.text = "Contactos a avisar ($n)"
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(this@EditarActivity, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "Eliminar"
                    setOnClickListener {
                        AlertDialog.Builder(this@EditarActivity, temaDialogo())
                            .setTitle("Eliminar de los avisos")
                            .setMessage("¿Quitar definitivamente a «${c.etiqueta}» de la lista de avisos de este recordatorio?")
                            .setPositiveButton("Eliminar") { _, _ -> seleccion -= c.id; excluidos -= c.id; actualizarContactos() }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                })
            })
        }
    }

    private fun spinner(opciones: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@EditarActivity, android.R.layout.simple_spinner_dropdown_item, opciones)
    }

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
        btnContactos = findViewById(R.id.btnContactos)
        listaAvisos = findViewById(R.id.listaAvisos)
        val extra = findViewById<LinearLayout>(R.id.filaExtra)
        val btnBorrar = findViewById<Button>(R.id.btnBorrar)
        val btnReplicar = findViewById<Button>(R.id.btnReplicar)

        listOf("L", "M", "X", "J", "V", "S", "D").forEachIndexed { i, letra ->
            val cb = CheckBox(this).apply { text = letra; tag = i + 1 }
            filaDias.addView(cb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            checks.add(cb)
        }

        // "El n.º [día] del mes"
        spOrdinal = spinner(Formato.ORDINALES)
        spDiaSemana = spinner(Formato.DIAS_SEMANA)
        filaNSemana = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@EditarActivity).apply { text = "El" })
            addView(spOrdinal, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(spDiaSemana, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        // Terminar: nunca / después de X veces
        spTerminar = spinner(listOf("Nunca", "Después de X veces"))
        etVeces = EditText(this).apply { hint = "X"; inputType = InputType.TYPE_CLASS_NUMBER; visibility = View.GONE }
        tvVeces = TextView(this).apply { textSize = 12f; visibility = View.GONE }
        filaTerminar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@EditarActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(TextView(this@EditarActivity).apply { text = "Terminar:" })
                addView(spTerminar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
                addView(etVeces, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(tvVeces)
        }
        extra.addView(filaNSemana)
        extra.addView(filaTerminar)
        spTerminar.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                etVeces.visibility = if (pos == 1) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        spRep.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            Repeticion.entries.map { it.etiqueta })
        spRep.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = actualizarDias()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val idEditar = intent.getIntExtra(Programador.EXTRA_ID, -1)
        val idReplicar = intent.getIntExtra(EXTRA_REPLICAR, -1)
        original = if (idEditar >= 0) Repositorio.obtener(this, idEditar) else null
        val base = original ?: if (idReplicar >= 0) Repositorio.obtener(this, idReplicar) else null
        replica = original == null && base != null
        if (base != null) {
            etTitulo.setText(base.titulo)
            etNota.setText(base.nota)
            fechaHora = Calculo.zdt(base.proxima)
            if (replica) {
                // Réplica: conserva fecha y hora si son futuras; si no, arranca en la hora actual.
                if (base.proxima <= System.currentTimeMillis()) fechaHora = ZonedDateTime.now().withSecond(0).withNano(0)
            } else if (!base.activo && fechaHora.toLocalDate().isBefore(LocalDate.now())) {
                // Vencido de un día anterior: la fecha arranca en hoy.
                fechaHora = ZonedDateTime.of(LocalDate.now(), fechaHora.toLocalTime(), fechaHora.zone)
            }
            spRep.setSelection(base.repeticion.ordinal)
            checks.forEach { it.isChecked = (it.tag as Int) in base.dias }
            spOrdinal.setSelection((base.semanaMes - 1).coerceIn(0, 4))
            spDiaSemana.setSelection((base.diaSemana - 1).coerceIn(0, 6))
            if (base.limite > 0) { spTerminar.setSelection(1); etVeces.setText(base.limite.toString()) }
            cbAlarma.isChecked = base.alarma
            seleccion += base.contactos ?: Contactos.defaults(this).map { it.id }
            excluidos += base.excluidos
        } else {
            cbAlarma.isChecked = Ajustes.alarmaPorDefecto(this)
            seleccion += Contactos.defaults(this).map { it.id }
            fechaHora = ZonedDateTime.now().withSecond(0).withNano(0)
            spOrdinal.setSelection(((fechaHora.dayOfMonth - 1) / 7).coerceAtMost(3))
            spDiaSemana.setSelection(fechaHora.dayOfWeek.value - 1)
        }
        val r = original
        if (r != null) {
            title = "Editar recordatorio"
            if (r.limite > 0) { tvVeces.text = "Ya sonó ${r.ocurrencias} de ${r.limite} veces."; tvVeces.visibility = View.VISIBLE }
            if (r.sinSms || r.historialSms.isNotEmpty()) findViewById<TextView>(R.id.tvSmsAnulado).apply {
                visibility = View.VISIBLE
                text = (if (r.sinSms) "SMS anulado para este recordatorio (solo WhatsApp). Para reactivarlo, " +
                    "desmarcá «Anular envío de SMS» al guardar.\n" else "") +
                    (if (r.historialSms.isEmpty()) "" else "Historial de SMS:\n" + r.historialSms.joinToString("\n") { "• $it" })
            }
            btnBorrar.visibility = View.VISIBLE
            btnBorrar.setOnClickListener { confirmarBorrado(r) { finish() } }
            btnReplicar.visibility = View.VISIBLE
            btnReplicar.setOnClickListener {
                startActivity(Intent(this, EditarActivity::class.java).putExtra(EXTRA_REPLICAR, r.id))
                finish()
            }
        } else {
            title = if (replica) "Nuevo recordatorio (réplica)" else "Nuevo recordatorio"
        }
        actualizarBotones()
        actualizarContactos()
        btnContactos.setOnClickListener { elegirContactos(seleccion) { excluidos.retainAll(seleccion); actualizarContactos() } }

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
        val rep = repeticionElegida()
        val semanal = rep == Repeticion.SEMANAL
        filaDias.visibility = if (semanal) View.VISIBLE else View.GONE
        if (semanal && checks.none { it.isChecked }) checks[fechaHora.dayOfWeek.value - 1].isChecked = true
        filaNSemana.visibility = if (rep == Repeticion.NSEMANA) View.VISIBLE else View.GONE
        filaTerminar.visibility = if (rep == Repeticion.NINGUNA) View.GONE else View.VISIBLE
    }

    private fun aviso(texto: String) = Toast.makeText(this, texto, Toast.LENGTH_LONG).show()

    private fun guardar() {
        val titulo = etTitulo.text.toString().trim()
        if (titulo.isEmpty()) { aviso("Escribí un título"); return }
        val rep = repeticionElegida()
        val dias = checks.filter { it.isChecked }.map { it.tag as Int }.toMutableSet()
        if (rep == Repeticion.SEMANAL && dias.isEmpty()) { aviso("Elegí al menos un día"); return }
        var limite = 0
        if (rep != Repeticion.NINGUNA && spTerminar.selectedItemPosition == 1) {
            limite = etVeces.text.toString().toIntOrNull() ?: 0
            if (limite < 1) { aviso("Indicá después de cuántas veces termina"); return }
        }
        val ms = fechaHora.toInstant().toEpochMilli()
        val ahora = System.currentTimeMillis()
        if (rep == Repeticion.NINGUNA && ms <= ahora) { aviso("Elegí una fecha y hora futura"); return }
        val o = original
        val suspendidos = SuspensionSms.activa(this)

        val r = Recordatorio(
            id = o?.id ?: Repositorio.nuevoId(this),
            titulo = titulo,
            nota = etNota.text.toString().trim(),
            proxima = ms,
            repeticion = rep,
            dias = if (rep == Repeticion.SEMANAL) dias else mutableSetOf(),
            diaMes = fechaHora.dayOfMonth,
            activo = true,
            alarma = cbAlarma.isChecked,
            contactos = seleccion.toMutableSet(),
            sinSms = (o?.sinSms ?: false) || suspendidos,
            historialSms = (o?.historialSms ?: mutableListOf()).toMutableList().apply {
                if (o == null && suspendidos) add("Creado con SMS suspendidos/anulados el ${Formato.completo(ahora)}")
            },
            semanaMes = spOrdinal.selectedItemPosition + 1,
            diaSemana = spDiaSemana.selectedItemPosition + 1,
            limite = limite,
            // Si se reactiva un vencido, el conteo vuelve a cero.
            ocurrencias = if (o != null && o.activo) o.ocurrencias else 0,
            excluidos = excluidos.filter { it in seleccion }.toMutableSet()
        )
        Calculo.ajustarInicio(r, ahora)
        // Cancela las alarmas anteriores (incluida una posposición pendiente). La notificación vieja
        // queda en la bandeja hasta que suene la nueva.
        Programador.cancelar(this, r.id)
        Repositorio.guardar(this, r)
        Programador.programar(this, r)
        aviso("Guardado para el " + Formato.completo(r.proxima))
        confirmarAvisos(r, if (o == null) Envios.NUEVO else Envios.EDITADO) { finish() }
    }
}
