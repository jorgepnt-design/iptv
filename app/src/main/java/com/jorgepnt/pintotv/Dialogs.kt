package com.jorgepnt.pintotv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

object Dialogs {
    /** Mehrfachauswahl – Knöpfe stehen OBEN, damit man nicht durch die ganze Liste scrollen muss */
    fun multiChoice(a: Activity, title: String, labels: Array<String>, checked: BooleanArray, onApply: () -> Unit) {
        val d = a.resources.displayMetrics.density
        val head = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (18 * d).toInt(), (24 * d).toInt(), (8 * d).toInt())
        }
        head.addView(TextView(a).apply { text = title; textSize = 20f; setTextColor(Color.WHITE) })
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (12 * d).toInt(), 0, 0) }
        fun btn(t: String) = Button(a).apply {
            text = t; isAllCaps = false; textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.button_bg)
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (10 * d).toInt() }
        }
        val bApply = btn("✓ Übernehmen"); val bAll = btn("Alle an"); val bNone = btn("Alle aus"); val bCancel = btn("Abbrechen")
        listOf(bApply, bAll, bNone, bCancel).forEach { row.addView(it) }
        head.addView(row)
        val dlg = AlertDialog.Builder(a)
            .setCustomTitle(head)
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .create()
        fun setAll(on: Boolean) {
            for (i in checked.indices) { checked[i] = on; dlg.listView.setItemChecked(i, on) }
            Toast.makeText(a, if (on) "Alle ausgewählt" else "Alle abgewählt", Toast.LENGTH_SHORT).show()
        }
        bAll.setOnClickListener { setAll(true) }
        bNone.setOnClickListener { setAll(false) }
        bCancel.setOnClickListener { dlg.dismiss() }
        bApply.setOnClickListener { dlg.dismiss(); onApply() }
        dlg.setOnShowListener { bApply.requestFocus() }
        dlg.show()
    }

    /** Gruppen/Kategorien verwalten: nach Ländern, einzeln, alle einblenden. counts = Anzahl Einträge je Gruppe */
    fun manageGroups(a: Activity, groups: List<String>, counts: (String) -> Int, hiddenNow: Set<String>, unit: String,
                     save: (Set<String>) -> Unit) {
        AlertDialog.Builder(a)
            .setTitle("Kategorien verwalten")
            .setItems(arrayOf("Nach Ländern auswählen", "Einzeln ein-/ausblenden", "Alle einblenden")) { _, w ->
                when (w) {
                    0 -> {
                        val byCountry = LinkedHashMap<String, MutableList<String>>()
                        groups.forEach { g -> byCountry.getOrPut(Countries.code(g)) { mutableListOf() }.add(g) }
                        val codes = byCountry.keys.sortedWith(compareBy({ it == "?" }, { -(byCountry[it]?.size ?: 0) }))
                        val checked = BooleanArray(codes.size) { i -> byCountry[codes[i]]!!.any { it !in hiddenNow } }
                        val labels = codes.map { c ->
                            "${Countries.label(c)}  ·  ${byCountry[c]!!.size} Kategorien, ${byCountry[c]!!.sumOf(counts)} $unit"
                        }.toTypedArray()
                        multiChoice(a, "Welche Länder anzeigen?", labels, checked) {
                            val hide = HashSet<String>()
                            codes.forEachIndexed { i, c -> if (!checked[i]) hide.addAll(byCountry[c]!!) }
                            if (hide.size == groups.size) { Toast.makeText(a, "Mindestens ein Land muss sichtbar bleiben", Toast.LENGTH_SHORT).show(); return@multiChoice }
                            save(hide)
                        }
                    }
                    1 -> {
                        val checked = BooleanArray(groups.size) { groups[it] !in hiddenNow }
                        multiChoice(a, "Sichtbare Kategorien (Haken = anzeigen)", groups.map { "$it  (${counts(it)})" }.toTypedArray(), checked) {
                            val hide = groups.filterIndexed { i, _ -> !checked[i] }.toSet()
                            if (hide.size == groups.size) { Toast.makeText(a, "Mindestens eine Kategorie muss sichtbar bleiben", Toast.LENGTH_SHORT).show(); return@multiChoice }
                            save(hide)
                        }
                    }
                    2 -> save(emptySet())
                }
            }.show()
    }
}
