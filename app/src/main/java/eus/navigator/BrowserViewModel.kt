package eus.navigator

import android.app.Application
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Clip(val items: List<Loc>, val cut: Boolean)

/** Foto del progreso de una transferencia para la interfaz. totalBytes < 0 mientras se mide. */
data class TransferState(
	val title: String,
	val current: String?,
	val bytes: Long,
	val totalBytes: Long,
	val files: Int,
	val totalFiles: Int,
	/** Bytes por segundo de media desde el inicio. */
	val speed: Long,
	val cancelling: Boolean,
)

class BrowserViewModel(app: Application) : AndroidViewModel(app) {
	private val prefs = app.getSharedPreferences("settings", 0)
	private val store = ShortcutStore(app)
	private val serverStore = ServerStore(app)

	var roots by mutableStateOf(FileOps.roots(app))
		private set
	var dir by mutableStateOf<Loc>(LocalLoc(roots.first().dir))
		private set
	var entries by mutableStateOf(emptyList<Entry>())
		private set
	var loading by mutableStateOf(false)
		private set
	var loadError by mutableStateOf<String?>(null)
		private set
	var busy by mutableStateOf<String?>(null)
		private set
	var transferState by mutableStateOf<TransferState?>(null)
		private set
	private var progress: Progress? = null

	var selection by mutableStateOf(emptySet<Loc>())
		private set
	var clip by mutableStateOf<Clip?>(null)
		private set
	var query by mutableStateOf<String?>(null)

	var showHidden by mutableStateOf(prefs.getBoolean("hidden", false))
		private set
	var sort by mutableStateOf(SortBy.entries[prefs.getInt("sortBy", SortBy.DATE.ordinal)])
		private set
	var descending by mutableStateOf(prefs.getBoolean("sortDesc", true))
		private set
	var grid by mutableStateOf(prefs.getBoolean("grid", false))
		private set

	var shortcuts by mutableStateOf(store.load())
		private set
	var servers by mutableStateOf(serverStore.load())
		private set

	/** Ubicación que se abre al iniciar: "path:<ruta>" para un acceso directo o "sftp:<id>" para un servidor. */
	var start by mutableStateOf(prefs.getString("start", null))
		private set

	private var loadJob: Job? = null
	private var connectJob: Job? = null

	init {
		Sftp.init(app)
		Thumbnails.init(app)
		openStart()
	}

	override fun onCleared() {
		Sftp.closeAll()
	}

	val visible: List<Entry>
		get() = query?.takeIf { it.isNotBlank() }
			?.let { q -> entries.filter { it.name.contains(q, ignoreCase = true) } }
			?: entries

	val selectedEntries: List<Entry>
		get() = entries.filter { it.loc in selection }

	/** Volumen al que pertenece la carpeta actual (el más específico), si es local. */
	val currentRoot: StorageRoot?
		get() = (dir as? LocalLoc)?.let { d -> roots.filter { FileOps.isInside(d.file, it.dir) }.maxByOrNull { it.dir.path.length } }

	/** Raíz de la ubicación actual (volumen local o "/" del servidor) y su nombre visible. */
	val root: Pair<String, Loc>?
		get() = when (val d = dir) {
			is LocalLoc -> currentRoot?.let { it.name to LocalLoc(it.dir) }
			is RemoteLoc -> d.server.name to RemoteLoc(d.server, "/")
		}

	val atRoot: Boolean
		get() = root?.second?.path?.let { it == dir.path } ?: true

	fun refreshRoots() {
		roots = FileOps.roots(getApplication())
		refresh()
	}

	fun open(target: Loc) {
		connectJob?.cancel()
		show(target)
	}

	private fun show(target: Loc) {
		if (target is LocalLoc && !target.file.isDirectory) return toast("La carpeta no existe")
		dir = target
		selection = emptySet()
		query = null
		refresh()
	}

	fun goUp(): Boolean {
		if (atRoot) return false
		dir.parent?.let(::open) ?: return false
		return true
	}

	fun refresh() {
		loadJob?.cancel()
		val d = dir
		loadJob = viewModelScope.launch {
			loading = true
			try {
				entries = withContext(Dispatchers.IO) { Fs.list(d, showHidden, sort, descending) }
				loadError = null
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				entries = emptyList()
				loadError = e.message ?: "Error"
			}
			selection = selection.filter { s -> entries.any { it.loc == s } }.toSet()
			loading = false
		}
	}

	fun toggleHidden() {
		showHidden = !showHidden
		prefs.edit().putBoolean("hidden", showHidden).apply()
		refresh()
	}

	fun toggleGrid() {
		grid = !grid
		prefs.edit().putBoolean("grid", grid).apply()
	}

	fun changeSort(by: SortBy) {
		// Pulsar el criterio activo invierte el sentido.
		if (by == sort) descending = !descending else { sort = by; descending = by != SortBy.NAME && by != SortBy.TYPE }
		// Claves nuevas ("sortBy"/"sortDesc") para que el nuevo orden por defecto, fecha descendente, se aplique a todos.
		prefs.edit().putInt("sortBy", sort.ordinal).putBoolean("sortDesc", descending).apply()
		refresh()
	}

	// --- Selección ---

	fun toggle(loc: Loc) {
		selection = if (loc in selection) selection - loc else selection + loc
	}

	fun selectAll() {
		selection = visible.map { it.loc }.toSet()
	}

	fun clearSelection() {
		selection = emptySet()
	}

	// --- Operaciones ---

	fun copySelection(cut: Boolean) = copyToClip(selection.toList(), cut)

	fun copyToClip(items: List<Loc>, cut: Boolean) {
		clip = Clip(items, cut)
		selection = emptySet()
		toast("Elige destino y pulsa Pegar")
	}

	fun cancelClip() {
		clip = null
	}

	fun paste() {
		val c = clip ?: return
		val dest = dir
		transfer(c.items, cut = c.cut) { dest }
		clip = null
	}

	/** Envía [items] a la carpeta de un acceso directo. */
	fun sendTo(items: List<Loc>, s: Shortcut, cut: Boolean) = transfer(items, cut, s.name) { LocalLoc(File(s.path)) }

	/** Envía [items] a la ruta inicial de un servidor. */
	fun sendTo(items: List<Loc>, s: Server, cut: Boolean) =
		transfer(items, cut, s.name) { RemoteLoc(s, Sftp.realPath(s, s.path)) }

	private fun transfer(items: List<Loc>, cut: Boolean, destName: String? = null, dest: () -> Loc) {
		selection = emptySet()
		runTransfer(if (cut) "Moviendo" else "Copiando") { p ->
			val target = dest()
			Fs.measure(items, p)
			items.forEach { if (cut) Fs.move(it, target, p) else Fs.copy(it, target, p) }
			if (destName != null) toast("${items.size} ${if (items.size == 1) "elemento" else "elementos"} a «$destName»")
		}
	}

	fun cancelTransfer() {
		progress?.cancelled = true
	}

	/** Como [run], pero con progreso detallado y cancelable. */
	private fun runTransfer(title: String, reload: Boolean = true, block: suspend (Progress) -> Unit) {
		val p = Progress()
		progress = p
		viewModelScope.launch {
			val started = System.nanoTime()
			// La interfaz se actualiza por muestreo, no en cada bloque copiado.
			val ticker = launch {
				while (true) {
					val secs = (System.nanoTime() - started) / 1e9
					transferState = TransferState(
						title, p.current, p.bytes, p.totalBytes, p.files, p.totalFiles,
						speed = if (secs > 0.5) (p.bytes / secs).toLong() else 0, cancelling = p.cancelled,
					)
					delay(250)
				}
			}
			try {
				withContext(Dispatchers.IO) { block(p) }
			} catch (e: Exception) {
				toast(e.message ?: "Error")
			} finally {
				ticker.cancel()
				transferState = null
				progress = null
				if (reload) refresh()
			}
		}
	}

	fun delete(items: Collection<Loc>) {
		run("Borrando…") {
			var failed = 0
			items.forEach { runCatching { Fs.delete(it) }.onFailure { failed++ } }
			if (failed > 0) error("No se pudieron borrar $failed elementos")
		}
		selection = emptySet()
	}

	fun rename(loc: Loc, newName: String) {
		val name = newName.trim()
		if (!validName(name)) return toast("Nombre no válido")
		val target = loc.parent?.child(name) ?: return
		selection = emptySet()
		run("Renombrando…") {
			if (Fs.exists(target)) error("Ya existe «$name»")
			Fs.rename(loc, target)
			if (loc is LocalLoc) {
				shortcuts = shortcuts.map { if (it.path == loc.path) it.copy(path = target.path) else it }.also(store::save)
				if (start == "path:${loc.path}") saveStart("path:${target.path}")
			}
		}
	}

	fun createFolder(name: String) = create(name, Fs::mkdir)

	fun createFile(name: String) = create(name, Fs::createFile)

	private fun create(rawName: String, make: (Loc) -> Unit) {
		val name = rawName.trim()
		if (!validName(name)) return toast("Nombre no válido")
		val target = dir.child(name)
		run("Creando…") {
			if (Fs.exists(target)) error("Ya existe «$name»")
			make(target)
		}
	}

	/** Descarga (si hace falta) los archivos y se los pasa a [then] en el hilo principal, p. ej. para abrirlos. */
	fun withLocalFiles(items: List<Entry>, then: (List<File>) -> Unit) {
		val files = items.filter { !it.isDir }
		if (files.isEmpty()) return toast("Solo se pueden usar archivos, no carpetas")
		if (files.all { it.loc is LocalLoc }) return then(files.map { (it.loc as LocalLoc).file })
		runTransfer("Descargando", reload = false) { p ->
			p.totalFiles = files.size
			p.totalBytes = files.sumOf { it.size }
			val local = files.map { Fs.localCopy(getApplication(), it.loc, p, it.size, it.modified) }
			withContext(Dispatchers.Main) { then(local) }
		}
	}

	private fun validName(name: String) = name.isNotEmpty() && name != "." && name != ".." && '/' !in name

	private fun run(label: String, reload: Boolean = true, block: suspend () -> Unit) {
		viewModelScope.launch {
			busy = label
			try {
				withContext(Dispatchers.IO) { block() }
			} catch (e: Exception) {
				toast(e.message ?: "Error")
			} finally {
				busy = null
				if (reload) refresh()
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
		if (isStart(s)) saveStart(null)
	}

	fun moveShortcut(s: Shortcut, delta: Int) {
		shortcuts = swap(shortcuts, s, delta).also(store::save)
	}

	// --- Servidores SFTP ---

	fun openServer(s: Server) {
		connectJob?.cancel()
		connectJob = viewModelScope.launch {
			loading = true
			try {
				val path = withContext(Dispatchers.IO) { Sftp.realPath(s, s.path) }
				show(RemoteLoc(s, path))
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				loading = false
				toast("«${s.name}»: ${e.message}")
			}
		}
	}

	fun saveServer(s: Server) {
		val old = servers.find { it.id == s.id }
		// Guardar de nuevo un servidor también sirve para aceptar una clave de host que ha cambiado.
		old?.let(Sftp::forget)
		servers = (if (old == null) servers + s else servers.map { if (it.id == s.id) s else it }).also(serverStore::save)
		if (old == null || (dir as? RemoteLoc)?.server?.id == s.id) openServer(s)
	}

	fun removeServer(s: Server) {
		Sftp.forget(s)
		servers = servers.filterNot { it.id == s.id }.also(serverStore::save)
		if (isStart(s)) saveStart(null)
		if ((dir as? RemoteLoc)?.server?.id == s.id) open(LocalLoc(roots.first().dir))
	}

	fun moveServer(s: Server, delta: Int) {
		servers = swap(servers, s, delta).also(serverStore::save)
	}

	// --- Ubicación de inicio ---

	fun isStart(s: Shortcut) = start == "path:${s.path}"

	fun isStart(s: Server) = start == "sftp:${s.id}"

	fun toggleStart(s: Shortcut) = saveStart(if (isStart(s)) null else "path:${s.path}")

	fun toggleStart(s: Server) = saveStart(if (isStart(s)) null else "sftp:${s.id}")

	private fun saveStart(key: String?) {
		start = key
		prefs.edit().putString("start", key).apply()
	}

	private fun openStart() {
		val key = start ?: return
		when {
			key.startsWith("path:") -> File(key.removePrefix("path:")).takeIf { it.isDirectory }?.let { dir = LocalLoc(it) }
			key.startsWith("sftp:") -> servers.find { it.id == key.removePrefix("sftp:") }?.let(::openServer)
		}
	}

	private fun <T> swap(list: List<T>, item: T, delta: Int): List<T> {
		val l = list.toMutableList()
		val i = l.indexOf(item)
		val j = i + delta
		if (i < 0 || j !in l.indices) return list
		l[i] = l[j].also { l[j] = l[i] }
		return l
	}

	fun toast(msg: String) {
		viewModelScope.launch(Dispatchers.Main) {
			Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
		}
	}
}
