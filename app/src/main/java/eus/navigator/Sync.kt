package eus.navigator

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Tipos de archivo que se pueden filtrar en un origen. */
enum class Kind(val label: String, val exts: Set<String>) {
	IMAGES("Imágenes", IMAGE_EXT),
	VIDEOS("Vídeos", VIDEO_EXT),
	AUDIO("Audio", setOf("mp3", "wav", "ogg", "flac", "m4a", "aac", "opus")),
	DOCS("Documentos", setOf("pdf", "doc", "docx", "odt", "rtf", "txt", "md", "csv", "xls", "xlsx", "ods", "ppt", "pptx", "odp", "epub")),
}

/** Carpeta guardable: local si [serverId] es null; en SFTP, [path] es absoluta. */
data class PlaceRef(val serverId: String?, val path: String) {
	fun resolve(servers: List<Server>): Loc =
		if (serverId == null) LocalLoc(File(path))
		else RemoteLoc(servers.find { it.id == serverId } ?: throw IOException("El servidor de «$path» ya no existe"), path)

	fun label(servers: List<Server>): String =
		if (serverId == null) path else "${servers.find { it.id == serverId }?.name ?: "¿Servidor borrado?"}: $path"

	companion object {
		fun of(loc: Loc) = when (loc) {
			is LocalLoc -> PlaceRef(null, loc.path)
			is RemoteLoc -> PlaceRef(loc.server.id, loc.path)
		}
	}
}

/** Carpeta de la que se trae contenido. Sin tipos ni extensiones, se trae todo. */
data class SyncSource(
	val place: PlaceRef,
	val kinds: Set<Kind> = emptySet(),
	/** Extensiones extra separadas por comas o espacios, p. ej. "pdf, epub". */
	val exts: String = "",
	val recursive: Boolean = true,
) {
	private val allowed: Set<String> by lazy {
		kinds.flatMap { it.exts }.toSet() + exts.split(',', ' ', ';').map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }
	}

	fun matches(name: String) = allowed.isEmpty() || name.substringAfterLast('.', "").lowercase() in allowed

	val summary: String
		get() {
			val what = (kinds.map { it.label } + listOfNotNull(exts.trim().takeIf { it.isNotEmpty() })).ifEmpty { listOf("Todo") }
			return what.joinToString(", ") + if (recursive) " · con subcarpetas" else ""
		}
}

/**
 * Sincronización hacia [target], que usa las mismas claves que la ubicación de inicio:
 * "path:<ruta>" para un acceso directo o "sftp:<id>" para un servidor.
 */
data class SyncRule(
	val target: String,
	val sources: List<SyncSource> = emptyList(),
	val auto: Boolean = false,
	val intervalMin: Int = 60,
	val lastRun: Long = 0,
	val lastFiles: Int = 0,
	val lastError: String? = null,
) {
	val usesNetwork get() = target.startsWith("sftp:") || sources.any { it.place.serverId != null }
}

class SyncStore(context: Context) {
	private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

	@Synchronized
	fun load(): List<SyncRule> {
		val json = JSONArray(prefs.getString("list", "[]"))
		return (0 until json.length()).map { i ->
			val o = json.getJSONObject(i)
			val sources = o.getJSONArray("sources")
			SyncRule(
				target = o.getString("target"),
				sources = (0 until sources.length()).map { j ->
					val s = sources.getJSONObject(j)
					val kinds = s.getJSONArray("kinds")
					SyncSource(
						PlaceRef(s.optString("server").ifEmpty { null }, s.getString("path")),
						(0 until kinds.length()).mapNotNull { k -> runCatching { Kind.valueOf(kinds.getString(k)) }.getOrNull() }.toSet(),
						s.optString("exts"),
						s.optBoolean("recursive", true),
					)
				},
				auto = o.optBoolean("auto"),
				intervalMin = o.optInt("interval", 60),
				lastRun = o.optLong("lastRun"),
				lastFiles = o.optInt("lastFiles"),
				lastError = o.optString("lastError").ifEmpty { null },
			)
		}
	}

	@Synchronized
	fun save(list: List<SyncRule>) {
		val json = JSONArray()
		for (r in list) {
			val sources = JSONArray()
			r.sources.forEach { s ->
				sources.put(
					JSONObject().put("server", s.place.serverId ?: "").put("path", s.place.path)
						.put("kinds", JSONArray(s.kinds.map { it.name })).put("exts", s.exts).put("recursive", s.recursive)
				)
			}
			json.put(
				JSONObject().put("target", r.target).put("sources", sources).put("auto", r.auto).put("interval", r.intervalMin)
					.put("lastRun", r.lastRun).put("lastFiles", r.lastFiles).put("lastError", r.lastError ?: "")
			)
		}
		prefs.edit().putString("list", json.toString()).apply()
	}

	@Synchronized
	fun recordRun(target: String, files: Int, error: String?) {
		save(load().map { if (it.target == target) it.copy(lastRun = System.currentTimeMillis(), lastFiles = files, lastError = error) else it })
	}
}

/** Un archivo que la sincronización va a copiar. [rel] es su ruta relativa al origen. */
data class SyncItem(val src: Loc, val dest: Loc, val rel: String, val size: Long, val modified: Long, val isNew: Boolean)

data class SyncResult(val copied: Int, val failed: Int, val error: String?)

object Sync {
	/** Diferencia de fechas que se tolera: FAT guarda con 2 s de precisión y SFTP en segundos. */
	private const val MTIME_SLACK = 2000

	fun resolveTarget(key: String, servers: List<Server>): Loc = when {
		key.startsWith("path:") -> LocalLoc(File(key.removePrefix("path:")))
		key.startsWith("sftp:") -> {
			val s = servers.find { it.id == key.removePrefix("sftp:") } ?: throw IOException("El servidor ya no existe")
			RemoteLoc(s, Sftp.realPath(s, s.path))
		}
		else -> throw IOException("Destino no válido")
	}

	/**
	 * Calcula qué hay que copiar, como un rsync sin --delete: archivos que faltan en el destino
	 * o cuyo tamaño o fecha han cambiado. Nunca se borra nada del destino.
	 */
	fun plan(rule: SyncRule, servers: List<Server>, p: Progress): List<SyncItem> {
		val target = resolveTarget(rule.target, servers)
		val items = LinkedHashMap<String, SyncItem>()
		val destCache = HashMap<String, Map<String, Entry>>()

		fun destEntries(dir: Loc) = destCache.getOrPut(dir.path) {
			// Si la carpeta aún no existe en el destino, todo lo de dentro es nuevo.
			runCatching { Fs.list(dir, showHidden = true, SortBy.NAME, false) }.getOrDefault(emptyList()).associateBy { it.name }
		}

		for (source in rule.sources) {
			val root = source.place.resolve(servers)
			if (Fs.isInside(root, target) && Fs.isInside(target, root)) {
				throw IOException("El origen «${source.place.path}» es la misma carpeta que el destino")
			}
			fun walk(dir: Loc, destDir: Loc, rel: String) {
				p.check()
				p.current = rel.ifEmpty { dir.name }
				for (e in Fs.list(dir, showHidden = false, SortBy.NAME, false)) {
					if (e.isDir) {
						// Si el destino está dentro del origen, se salta: si no, se copiaría a sí mismo en bucle.
						if (source.recursive && !Fs.isInside(e.loc, target)) walk(e.loc, destDir.child(e.name), "$rel${e.name}/")
						continue
					}
					if (e.name.endsWith(Fs.PART) || !source.matches(e.name)) continue
					val existing = destEntries(destDir)[e.name]
					if (existing != null && !existing.isDir && existing.size == e.size && e.modified <= existing.modified + MTIME_SLACK) continue
					if (existing?.isDir == true) continue
					val dest = destDir.child(e.name)
					val key = dest.path
					// Si dos orígenes traen el mismo archivo, gana el más reciente.
					val prev = items[key]
					if (prev == null || e.modified > prev.modified) {
						items[key] = SyncItem(e.loc, dest, rel + e.name, e.size, e.modified, isNew = existing == null)
					}
				}
			}
			walk(root, target, "")
		}
		return items.values.toList()
	}

	/** Copia [items]. Un archivo que falla no detiene el resto; cancelar sí. */
	fun run(items: List<SyncItem>, p: Progress): SyncResult {
		p.totalFiles = items.size
		p.bytes = 0
		p.files = 0
		p.totalBytes = items.sumOf { it.size }
		val made = HashSet<String>()
		var failed = 0
		var error: String? = null
		for (item in items) {
			p.check()
			try {
				item.dest.parent?.let { dir -> if (made.add(dir.path)) Fs.mkdirs(dir) }
				Fs.replaceFile(item.src, item.dest, item.modified, p)
			} catch (e: CancelledException) {
				throw e
			} catch (e: Exception) {
				Log.w("Navigator", "Sync: fallo al copiar ${item.rel}", e)
				failed++
				if (error == null) error = "${item.rel}: ${e.message ?: e.javaClass.simpleName}"
			}
		}
		return SyncResult(items.size - failed, failed, error)
	}

	fun workName(target: String) = "sync:$target"

	/** Programa (o cancela) la sincronización automática de [rule]. */
	fun schedule(context: Context, rule: SyncRule) {
		val wm = WorkManager.getInstance(context)
		if (!rule.auto || rule.sources.isEmpty()) {
			wm.cancelUniqueWork(workName(rule.target))
			return
		}
		val constraints = Constraints.Builder()
			.setRequiredNetworkType(if (rule.usesNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
			.setRequiresBatteryNotLow(true)
			.build()
		val request = PeriodicWorkRequestBuilder<SyncWorker>(rule.intervalMin.toLong(), TimeUnit.MINUTES)
			.setConstraints(constraints)
			.setInputData(workDataOf("target" to rule.target))
			.build()
		wm.enqueueUniquePeriodicWork(workName(rule.target), ExistingPeriodicWorkPolicy.UPDATE, request)
	}

	fun unschedule(context: Context, target: String) {
		WorkManager.getInstance(context).cancelUniqueWork(workName(target))
	}
}

/** Sincronización automática en segundo plano, lanzada por WorkManager aunque la app esté cerrada. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
	override suspend fun doWork(): Result {
		val target = inputData.getString("target") ?: return Result.failure()
		val ctx = applicationContext
		Sftp.init(ctx)
		val store = SyncStore(ctx)
		val rule = store.load().find { it.target == target && it.auto } ?: return Result.success()
		val servers = ServerStore(ctx).load()
		val p = Progress()
		return coroutineScope {
			// Si el sistema detiene el trabajo, la corrutina se cancela: se avisa a la copia para que pare.
			val watcher = launch { try { awaitCancellation() } finally { p.cancelled = true } }
			try {
				val r = withContext(Dispatchers.IO) { Sync.run(Sync.plan(rule, servers, p), p) }
				store.recordRun(target, r.copied, r.error)
			} catch (e: CancelledException) {
				// Se reintentará en la siguiente ventana.
			} catch (e: Exception) {
				Log.w("Navigator", "Sync automática de $target", e)
				store.recordRun(target, 0, e.message ?: e.javaClass.simpleName)
			} finally {
				watcher.cancel()
			}
			Result.success()
		}
	}
}
