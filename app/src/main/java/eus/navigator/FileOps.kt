package eus.navigator

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.DateFormat
import java.util.Date

/** [children] es -1 cuando no se conoce (carpetas remotas). */
data class Entry(
	val loc: Loc,
	val name: String,
	val isDir: Boolean,
	val size: Long,
	val modified: Long,
	val children: Int,
)

data class StorageRoot(val name: String, val dir: File, val removable: Boolean)

enum class SortBy { NAME, DATE, SIZE, TYPE }

object FileOps {
	fun list(dir: File, showHidden: Boolean): List<Entry> {
		val files = dir.listFiles() ?: return emptyList()
		return files
			.filter { showHidden || !it.name.startsWith(".") }
			.map { f ->
				val isDir = f.isDirectory
				Entry(
					loc = LocalLoc(f),
					name = f.name,
					isDir = isDir,
					size = if (isDir) 0 else f.length(),
					modified = f.lastModified(),
					children = if (isDir) f.list()?.size ?: 0 else 0,
				)
			}
	}

	fun sorted(entries: List<Entry>, sort: SortBy, descending: Boolean): List<Entry> {
		val byField: Comparator<Entry> = when (sort) {
			SortBy.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
			SortBy.DATE -> compareBy { it.modified }
			SortBy.SIZE -> compareBy { if (it.isDir) it.children.toLong() else it.size }
			SortBy.TYPE -> compareBy<Entry> { it.name.substringAfterLast('.', "").lowercase() }
				.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
		}
		// Las carpetas siempre van primero, sea cual sea el orden.
		return entries.sortedWith(
			compareByDescending<Entry> { it.isDir }.then(if (descending) byField.reversed() else byField)
		)
	}

	fun roots(context: Context): List<StorageRoot> {
		val roots = mutableListOf<StorageRoot>()
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			val sm = context.getSystemService(StorageManager::class.java)
			for (v in sm.storageVolumes) {
				val dir = v.directory ?: continue
				roots += StorageRoot(v.getDescription(context), dir, v.isRemovable)
			}
		} else {
			// En Android 8-10 se deduce la raíz de cada volumen a partir de su carpeta Android/data.
			context.getExternalFilesDirs(null).filterNotNull().forEachIndexed { i, f ->
				val root = File(f.path.substringBefore("/Android/data"))
				roots += StorageRoot(if (i == 0) "Almacenamiento interno" else "Tarjeta SD", root, i > 0)
			}
		}
		if (roots.isEmpty()) {
			roots += StorageRoot("Almacenamiento interno", Environment.getExternalStorageDirectory(), false)
		}
		return roots
	}

	fun freeSpace(dir: File): Pair<Long, Long>? = runCatching {
		val s = StatFs(dir.path)
		s.availableBytes to s.totalBytes
	}.getOrNull()

	/** Devuelve un nombre libre en [dir]: "foto.jpg" -> "foto (1).jpg". */
	fun uniqueName(dir: File, name: String): File {
		var target = File(dir, name)
		if (!target.exists()) return target
		val dot = name.lastIndexOf('.').takeIf { it > 0 }
		val base = if (dot != null) name.substring(0, dot) else name
		val ext = if (dot != null) name.substring(dot) else ""
		var i = 1
		while (target.exists()) target = File(dir, "$base (${i++})$ext")
		return target
	}

	fun isInside(child: File, parent: File): Boolean {
		val c = child.canonicalPath
		val p = parent.canonicalPath
		return c == p || c.startsWith("$p/")
	}

	fun mime(file: File): String = mime(file.name)

	fun mime(name: String): String =
		MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "*/*"

	private fun uri(context: Context, file: File): Uri =
		FileProvider.getUriForFile(context, "${context.packageName}.files", file)

	/** Abre con la app predeterminada del tipo; si el tipo es desconocido o nadie lo abre, deja elegir cualquier app. */
	fun open(context: Context, file: File) {
		val mime = mime(file)
		if (mime != "*/*") {
			try {
				context.startActivity(viewIntent(context, file, mime))
				return
			} catch (_: ActivityNotFoundException) {
			}
		}
		chooser(context, file, "*/*")
	}

	/** Pregunta siempre con qué app abrir. */
	fun openWith(context: Context, file: File) = chooser(context, file, mime(file))

	private fun chooser(context: Context, file: File, mime: String) {
		try {
			context.startActivity(Intent.createChooser(viewIntent(context, file, mime), "Abrir «${file.name}» con"))
		} catch (_: ActivityNotFoundException) {
			Toast.makeText(context, "Ninguna app puede abrir este archivo", Toast.LENGTH_SHORT).show()
		}
	}

	private fun viewIntent(context: Context, file: File, mime: String) = Intent(Intent.ACTION_VIEW)
		.setDataAndType(uri(context, file), mime)
		.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

	fun share(context: Context, files: List<File>) {
		val uris = files.filter { it.isFile }.map { uri(context, it) }
		if (uris.isEmpty()) {
			Toast.makeText(context, "Solo se pueden compartir archivos", Toast.LENGTH_SHORT).show()
			return
		}
		val intent = if (uris.size == 1) {
			Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(mime(files.first { it.isFile }))
		} else {
			Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)).setType("*/*")
		}
		intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		context.startActivity(Intent.createChooser(intent, "Compartir"))
	}

	fun size(bytes: Long): String {
		if (bytes < 1024) return "$bytes B"
		val units = arrayOf("KB", "MB", "GB", "TB")
		var v = bytes / 1024.0
		var i = 0
		while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
		return String.format(if (v < 10) "%.1f %s" else "%.0f %s", v, units[i])
	}

	fun date(millis: Long): String =
		DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

	/** Tamaño total y número de archivos de una selección (recorre carpetas). */
	fun totals(files: Collection<File>): Pair<Long, Int> {
		var bytes = 0L
		var count = 0
		for (f in files) f.walkTopDown().filter { it.isFile }.forEach { bytes += it.length(); count++ }
		return bytes to count
	}
}
