package com.misrecordatorios.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** Ítem de la pestaña Pendientes: algo para hacer que todavía no tiene fecha. */
data class Pendiente(val id: Int, var titulo: String, var nota: String, val creado: Long)

object Pendientes {
    const val EXTRA_PEND_ID = "pend_id"
    const val EXTRA_PEND_TITULO = "pend_titulo"
    const val EXTRA_PEND_NOTA = "pend_nota"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pendientes", Context.MODE_PRIVATE)

    @Synchronized
    fun todos(ctx: Context): MutableList<Pendiente> {
        val arr = JSONArray(prefs(ctx).getString("l", "[]") ?: "[]")
        return MutableList(arr.length()) {
            val o = arr.getJSONObject(it)
            Pendiente(o.getInt("id"), o.optString("titulo"), o.optString("nota"), o.optLong("creado"))
        }
    }

    @Synchronized
    private fun guardarTodos(ctx: Context, lista: List<Pendiente>) {
        val arr = JSONArray()
        lista.forEach {
            arr.put(JSONObject().put("id", it.id).put("titulo", it.titulo).put("nota", it.nota).put("creado", it.creado))
        }
        prefs(ctx).edit().putString("l", arr.toString()).commit()
        Copia.automatica(ctx)   // la copia de seguridad actual también incluye los pendientes
    }

    fun obtener(ctx: Context, id: Int) = todos(ctx).firstOrNull { it.id == id }

    /** Alta o modificación. Devuelve el pendiente guardado (con su id). */
    @Synchronized
    fun guardar(ctx: Context, id: Int, titulo: String, nota: String): Pendiente {
        val lista = todos(ctx)
        val i = lista.indexOfFirst { it.id == id }
        val p = if (i >= 0) lista[i].also { it.titulo = titulo; it.nota = nota }
                else Pendiente((lista.maxOfOrNull { it.id } ?: 0) + 1, titulo, nota, System.currentTimeMillis()).also { lista += it }
        guardarTodos(ctx, lista)
        return p
    }

    @Synchronized
    fun borrar(ctx: Context, id: Int) {
        val lista = todos(ctx)
        if (lista.removeAll { it.id == id }) {
            guardarTodos(ctx, lista)
            Registro.anotar(ctx, "Pendiente #$id eliminado")
        }
    }

    fun exportar(ctx: Context): JSONArray = JSONArray(prefs(ctx).getString("l", "[]") ?: "[]")
    fun importar(ctx: Context, arr: JSONArray) { prefs(ctx).edit().putString("l", arr.toString()).commit() }
}

/** Carga o edición de un Pendiente: Guardar, Crear Recordatorio y Borrar. */
class PendienteActivity : Activity() {
    private var id = -1
    private lateinit var etTitulo: EditText
    private lateinit var etNota: EditText

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        setContentView(R.layout.activity_pendiente)
        etTitulo = findViewById(R.id.etPendTitulo)
        etNota = findViewById(R.id.etPendNota)
        id = intent.getIntExtra(Pendientes.EXTRA_PEND_ID, -1)
        val p = Pendientes.obtener(this, id)
        if (p != null && estado == null) {
            etTitulo.setText(p.titulo)
            etNota.setText(p.nota)
        }
        title = if (p != null) "Editar pendiente" else "Nuevo pendiente"

        findViewById<Button>(R.id.btnPendGuardar).setOnClickListener {
            if (guardar() != null) finish()
        }
        findViewById<Button>(R.id.btnPendCrear).setOnClickListener {
            // Se guarda primero: si después se cancela el recordatorio, el pendiente no se pierde.
            val guardado = guardar() ?: return@setOnClickListener
            startActivity(Intent(this, EditarActivity::class.java)
                .putExtra(Pendientes.EXTRA_PEND_ID, guardado.id)
                .putExtra(Pendientes.EXTRA_PEND_TITULO, guardado.titulo)
                .putExtra(Pendientes.EXTRA_PEND_NOTA, guardado.nota))
            finish()
        }
        if (p != null) findViewById<Button>(R.id.btnPendBorrar).apply {
            visibility = View.VISIBLE
            setOnClickListener { confirmarBorrarPendiente(p) { finish() } }
        }
    }

    private fun guardar(): Pendiente? {
        val titulo = etTitulo.text.toString().trim()
        if (titulo.isEmpty()) {
            Toast.makeText(this, "Escribí un título", Toast.LENGTH_LONG).show()
            return null
        }
        val p = Pendientes.guardar(this, id, titulo, etNota.text.toString().trim())
        id = p.id
        return p
    }
}

fun Activity.confirmarBorrarPendiente(p: Pendiente, alTerminar: () -> Unit) {
    AlertDialog.Builder(this, temaDialogo())
        .setTitle("Borrar pendiente")
        .setMessage("¿Borrar «${p.titulo}»?")
        .setPositiveButton("Borrar") { _, _ -> Pendientes.borrar(this, p.id); alTerminar() }
        .setNegativeButton("Cancelar", null)
        .show()
}
