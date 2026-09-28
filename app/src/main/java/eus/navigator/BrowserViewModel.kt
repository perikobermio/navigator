package eus.navigator

import android.app.Application
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Clip(val files: List<File>, val cut: Boolean)

class BrowserViewModel(app: Application) : AndroidViewModel(app) {
	private val prefs = app.getSharedPreferences("settings", 0)
	private val store = ShortcutStore(app)

	var roots by mutableStateOf(FileOps.roots(app))
		private set
	var dir by mutableStateOf(roots.first().dir)
		private set
	var entries by mutableStateOf(emptyList<Entry>())
		private set
	var loading by mutableStateOf(false)
		private set
	var busy by mutableStateOf<String?>(null)
		private set

	var selection by mutableStateOf(emptySet<File>())
		private set
	var clip by mutableStateOf<Clip?>(null)
		private set
	var query by mutableStateOf<String?>(null)

	var showHidden by mutableStateOf(prefs.getBoolean("hidden", false))
		private set
	var sort by mutableStateOf(SortBy.entries[prefs.getInt("sort", 0)])
		private set
	var descending by mutableStateOf(prefs.getBoolean("desc", false))
		private set

	var shortcuts by mutableStateOf(store.load())
		private set

	private var loadJob: Job? = null

	val visible: List<Entry>
		get() = query?.takeIf { it.isNotBlank() }
			?.let { q -> entries.filter { it.name.contains(q, ignoreCase = true) } }
			?: entries

	/** Volumen al que pertenece la carpeta actual (el más específico). */
	val currentRoot: StorageRoot?
		get() = roots.filter { FileOps.isInside(dir, it.dir) }.maxByOrNull { it.dir.path.length }

	fun refreshRoots() {
		roots = FileOps.roots(getApplication())
		refresh()
	}

	fun open(target: File) {
		if (!target.isDirectory) return toast("La carpeta no existe")
		dir = target
		selection = emptySet()
		query = null
		refresh()
	}

	fun goUp(): Boolean {
		val root = currentRoot ?: return false
		if (dir.canonicalPath == root.dir.canonicalPath) return false
		dir.parentFile?.let(::open) ?: return false
		return true
	}

	fun refresh() {
		loadJob?.cancel()
		val d = dir
		loadJob = viewModelScope.launch {
			loading = true
			entries = withContext(Dispatchers.IO) { FileOps.list(d, showHidden, sort, descending) }
			selection = selection.filter { it.exists() }.toSet()
			loading = false
		}
	}

	fun toggleHidden() {
		showHidden = !showHidden
		prefs.edit().putBoolean("hidden", showHidden).apply()
		refresh()
	}

	fun changeSort(by: SortBy) {
		// Pulsar el criterio activo invierte el sentido.
		if (by == sort) descending = !descending else { sort = by; descending = by != SortBy.NAME && by != SortBy.TYPE }
		prefs.edit().putInt("sort", sort.ordinal).putBoolean("desc", descending).apply()
		refresh()
	}

	// --- Selección ---

	fun toggle(file: File) {
		selection = if (file in selection) selection - file else selection + file
	}

	fun selectAll() {
		selection = visible.map { it.file }.toSet()
	}

	fun clearSelection() {
		selection = emptySet()
	}

	// --- Operaciones ---

	fun copySelection(cut: Boolean) {
		clip = Clip(selection.toList(), cut)
		selection = emptySet()
		toast("Elige destino y pulsa Pegar")
	}

	fun cancelClip() {
		clip = null
	}

	fun paste() {
		val c = clip ?: return
		val dest = dir
		run(if (c.cut) "Moviendo…" else "Copiando…") {
			c.files.forEach { if (c.cut) FileOps.move(it, dest) else FileOps.copy(it, dest) }
			clip = null
		}
	}

	fun delete(files: Collection<File>) {
		run("Borrando…") {
			val failed = files.filterNot { it.deleteRecursively() }
			if (failed.isNotEmpty()) error("No se pudieron borrar ${failed.size} elementos")
		}
		selection = emptySet()
	}

	fun rename(file: File, newName: String) {
		val name = newName.trim()
		if (!validName(name)) return toast("Nombre no válido")
		val target = File(file.parentFile, name)
		if (target.exists()) return toast("Ya existe «$name»")
		if (!file.renameTo(target)) return toast("No se pudo renombrar")
		shortcuts = shortcuts.map { if (it.path == file.path) it.copy(path = target.path) else it }.also(store::save)
		selection = emptySet()
		refresh()
	}

	fun createFolder(name: String) = create(name) { it.mkdir() }

	fun createFile(name: String) = create(name) { it.createNewFile() }

	private fun create(rawName: String, make: (File) -> Boolean) {
		val name = rawName.trim()
		if (!validName(name)) return toast("Nombre no válido")
		val f = File(dir, name)
		if (f.exists()) return toast("Ya existe «$name»")
		if (!runCatching { make(f) }.getOrDefault(false)) return toast("No se pudo crear")
		refresh()
	}

	private fun validName(name: String) = name.isNotEmpty() && name != "." && name != ".." && '/' !in name

	private fun run(label: String, block: suspend () -> Unit) {
		viewModelScope.launch {
			busy = label
			try {
				withContext(Dispatchers.IO) { block() }
			} catch (e: Exception) {
				toast(e.message ?: "Error")
			} finally {
				busy = null
				refresh()
			}
		}
	}

	// --- Accesos directos ---

	fun addShortcut(file: File, name: String = file.name.ifEmpty { file.path }) {
		if (shortcuts.any { it.path == file.path }) return toast("Ya está en accesos directos")
		shortcuts = (shortcuts + Shortcut(name, file.path)).also(store::save)
		toast("Acceso directo creado")
	}

	fun renameShortcut(s: Shortcut, name: String) {
		if (name.isBlank()) return
		shortcuts = shortcuts.map { if (it == s) it.copy(name = name.trim()) else it }.also(store::save)
	}

	fun removeShortcut(s: Shortcut) {
		shortcuts = (shortcuts - s).also(store::save)
	}

	fun moveShortcut(s: Shortcut, delta: Int) {
		val list = shortcuts.toMutableList()
		val i = list.indexOf(s)
		val j = i + delta
		if (i < 0 || j !in list.indices) return
		list[i] = list[j].also { list[j] = list[i] }
		shortcuts = list.also(store::save)
	}

	fun toast(msg: String) {
		viewModelScope.launch(Dispatchers.Main) {
			Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
		}
	}
}
