package eus.navigator

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import org.json.JSONArray
import org.json.JSONObject

data class Shortcut(val name: String, val path: String)

/** Accesos directos guardados en SharedPreferences como JSON. */
class ShortcutStore(context: Context) {
	private val prefs = context.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)

	fun load(): List<Shortcut> {
		val json = JSONArray(prefs.getString("list", "[]"))
		return (0 until json.length()).map {
			val o = json.getJSONObject(it)
			Shortcut(o.getString("name"), o.getString("path"))
		}
	}

	fun save(list: List<Shortcut>) {
		val json = JSONArray()
		list.forEach { json.put(JSONObject().put("name", it.name).put("path", it.path)) }
		prefs.edit().putString("list", json.toString()).apply()
	}
}

const val EXTRA_PATH = "eus.navigator.PATH"

/** Pide al launcher que ancle el acceso directo en la pantalla de inicio. */
fun pinToHome(context: Context, shortcut: Shortcut) {
	if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
		Toast.makeText(context, "El launcher no admite accesos directos", Toast.LENGTH_SHORT).show()
		return
	}
	val intent = Intent(context, MainActivity::class.java)
		.setAction(Intent.ACTION_VIEW)
		.putExtra(EXTRA_PATH, shortcut.path)
	val info = ShortcutInfoCompat.Builder(context, "path:${shortcut.path}")
		.setShortLabel(shortcut.name)
		.setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
		.setIntent(intent)
		.build()
	ShortcutManagerCompat.requestPinShortcut(context, info, null)
}
