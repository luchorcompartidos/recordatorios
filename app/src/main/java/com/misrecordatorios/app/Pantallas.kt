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
import java.time.ZonedDateTime

fun Activity.confirmarBorrado(r: Recordatorio, alTerminar: () -> Unit) {
    AlertDialog.Builder(this)
        .setTitle("Borrar recordatorio")
        .setMessage("¿Borrar «${r.titulo}»?")
        .setPositiveButton("Borrar") { _, _ -> Programador.borrar(this, r.id); alTerminar() }
        .setNegativeButton("Cancelar", null)
        .show()
}

/** Lista de 1, 2, 3, 4, 5 y 10 minutos. [alCerrar] recibe true si se pospuso. */
fun Activity.elegirPosponer(id: Int, alCerrar: (Boolean) -> Unit) {
    val nocturno = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val tema = if (nocturno) android.R.style.Theme_DeviceDefault_Dialog_Alert
               else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    val opciones = Programador.OPCIONES_POSPONER
    val textos = opciones.map { if (it == 1) "1 minuto" else "$it minutos" }.toTypedArray()
    var pospuesto = false
    AlertDialog.Builder(this, tema)
        .setTitle("Posponer")
        .setItems(textos) { _, i ->
            Programador.posponer(this, id, opciones[i])
            pospuesto = true
            Toast.makeText(this, "Pospuesto ${textos[i]}", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("Cancelar", null)
        .setOnDismissListener { alCerrar(pospuesto) }
        .show()
}

/** Ventanita que se abre desde "Posponer…" en la notificación. */
class PosponerActivity : Activity() {
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        elegirPosponer(intent.getIntExtra(Programador.EXTRA_ID, -1)) { finish() }
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
        findViewById<TextView>(R.id.alNota).apply {
            text = r.nota
            visibility = if (r.nota.isBlank()) View.GONE else View.VISIBLE
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
            getSystemService(NotificationManager::class.java).cancel(id)
            finish()
        }
        findViewById<Button>(R.id.alPosponer5).setOnClickListener {
            Programador.posponer(this, id, 5)
            finish()
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

/** Ajustes generales. */
class AjustesActivity : Activity() {
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        title = "Ajustes"
        val margen = (16 * resources.displayMetrics.density).toInt()
        val sw = Switch(this).apply {
            text = "Crear los recordatorios nuevos con alarma sonora"
            textSize = 16f
            isChecked = Ajustes.alarmaPorDefecto(this@AjustesActivity)
            setOnCheckedChangeListener { _, v -> Ajustes.guardarAlarmaPorDefecto(this@AjustesActivity, v) }
        }
        val ayuda = TextView(this).apply {
            text = "Solo define cómo aparece marcada la opción al crear un recordatorio. " +
                "Siempre se puede cambiar en cada uno."
            setPadding(0, margen / 2, 0, 0)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margen, margen, margen, margen)
            addView(sw)
            addView(ayuda)
        })
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
        val vencidos = todos.filter { !it.activo }.sortedByDescending { it.proxima }
        if (proximos.isNotEmpty()) { agregarTitulo("Próximos"); proximos.forEach { agregarItem(it) } }
        if (vencidos.isNotEmpty()) { agregarTitulo("Vencidos"); vencidos.forEach { agregarItem(it) } }
    }

    private fun agregarTitulo(texto: String) {
        val v = layoutInflater.inflate(R.layout.item_titulo, lista, false) as TextView
        v.text = texto
        lista.addView(v)
    }

    private fun agregarItem(r: Recordatorio) {
        val v = layoutInflater.inflate(R.layout.item_recordatorio, lista, false)
        v.findViewById<TextView>(R.id.titulo).text = r.titulo
        v.findViewById<TextView>(R.id.detalle).text = Formato.detalle(r)
        if (!r.activo) v.alpha = 0.55f
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
            spRep.setSelection(r.repeticion.ordinal)
            checks.forEach { it.isChecked = (it.tag as Int) in r.dias }
            cbAlarma.isChecked = r.alarma
            btnBorrar.visibility = View.VISIBLE
            btnBorrar.setOnClickListener { confirmarBorrado(r) { finish() } }
        } else {
            title = "Nuevo recordatorio"
            cbAlarma.isChecked = Ajustes.alarmaPorDefecto(this)
            fechaHora = ZonedDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
        }
        actualizarBotones()

        btnFecha.setOnClickListener {
            DatePickerDialog(this, { _, a, m, d ->
                fechaHora = ZonedDateTime.of(LocalDate.of(a, m + 1, d), fechaHora.toLocalTime(), fechaHora.zone)
                actualizarBotones()
            }, fechaHora.year, fechaHora.monthValue - 1, fechaHora.dayOfMonth).show()
        }
        btnHora.setOnClickListener {
            TimePickerDialog(this, { _, h, min ->
                fechaHora = fechaHora.withHour(h).withMinute(min).withSecond(0).withNano(0)
                actualizarBotones()
            }, fechaHora.hour, fechaHora.minute, true).show()
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
        getSystemService(NotificationManager::class.java).cancel(r.id)
        Repositorio.guardar(this, r)
        Programador.programar(this, r)
        aviso("Guardado para el " + Formato.completo(r.proxima))
        finish()
    }
}
