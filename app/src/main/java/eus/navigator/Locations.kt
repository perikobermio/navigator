package eus.navigator

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Una ubicación navegable: un archivo o carpeta local, o una ruta en un servidor SFTP. */
sealed interface Loc {
	val path: String
	val name: String
	val parent: Loc?
	fun child(name: String): Loc
}

data class LocalLoc(val file: File) : Loc {
	override val path: String get() = file.path
	override val name: String get() = file.name
	override val parent get() = file.parentFile?.let(::LocalLoc)
	override fun child(name: String) = LocalLoc(File(file, name))
}

/** [path] siempre es absoluta y sin barra final (salvo la raíz "/"). */
data class RemoteLoc(val server: Server, override val path: String) : Loc {
	override val name: String get() = if (path == "/") server.name else path.substringAfterLast('/')
	override val parent get() = if (path == "/") null else RemoteLoc(server, path.substringBeforeLast('/').ifEmpty { "/" })
	override fun child(name: String) = RemoteLoc(server, if (path == "/") "/$name" else "$path/$name")
}

class CancelledException : IOException("Cancelado")

/** Progreso de una transferencia. Lo escribe el hilo de IO; la interfaz lo consulta periódicamente. */
class Progress {
	@Volatile var totalBytes = -1L
	@Volatile var totalFiles = 0
	@Volatile var bytes = 0L
	@Volatile var files = 0
	@Volatile var current: String? = null
	@Volatile var cancelled = false

	fun check() {
		if (cancelled) throw CancelledException()
	}
}

/** Operaciones sobre [Loc] que eligen la implementación local o SFTP. Todas bloquean: llamar desde IO. */
object Fs {
	/** Sufijo de los temporales de [replaceFile]; la sincronización los ignora si quedan huérfanos. */
	const val PART = ".navigator-part"

	fun list(dir: Loc, showHidden: Boolean, sort: SortBy, descending: Boolean): List<Entry> = when (dir) {
		is LocalLoc -> FileOps.sorted(FileOps.list(dir.file, showHidden), sort, descending)
		is RemoteLoc -> FileOps.sorted(Sftp.list(dir, showHidden), sort, descending)
	}

	/** null si no existe; true si es carpeta. */
	private fun isDir(loc: Loc): Boolean? = when (loc) {
		is LocalLoc -> if (loc.file.exists()) loc.file.isDirectory else null
		is RemoteLoc -> Sftp.stat(loc)?.isDir
	}

	fun exists(loc: Loc) = isDir(loc) != null

	fun mkdir(loc: Loc) = when (loc) {
		is LocalLoc -> if (!loc.file.mkdir()) throw IOException("No se pudo crear «${loc.name}»") else Unit
		is RemoteLoc -> Sftp.mkdir(loc)
	}

	fun createFile(loc: Loc) = when (loc) {
		is LocalLoc -> if (!loc.file.createNewFile()) throw IOException("No se pudo crear «${loc.name}»") else Unit
		is RemoteLoc -> Sftp.createFile(loc)
	}

	fun rename(from: Loc, to: Loc) = when (from) {
		is LocalLoc -> if (!from.file.renameTo((to as LocalLoc).file)) throw IOException("No se pudo renombrar") else Unit
		is RemoteLoc -> Sftp.rename(from, to as RemoteLoc)
	}

	fun delete(loc: Loc) = when (loc) {
		is LocalLoc -> if (!loc.file.deleteRecursively()) throw IOException("No se pudo borrar «${loc.name}»") else Unit
		is RemoteLoc -> Sftp.delete(loc)
	}

	fun copy(src: Loc, destDir: Loc, p: Progress) {
		val dir = isDir(src) ?: throw IOException("«${src.name}» ya no existe")
		require(!(dir && isInside(destDir, src))) { "No se puede copiar una carpeta dentro de sí misma" }
		transfer(src, dir, uniqueName(destDir, src.name), p)
	}

	fun move(src: Loc, destDir: Loc, p: Progress) {
		if (src.parent?.let { isSame(it, destDir) } == true) return
		if (src is LocalLoc && destDir is LocalLoc) {
			require(!(src.file.isDirectory && isInside(destDir, src))) { "No se puede mover una carpeta dentro de sí misma" }
			// renameTo solo funciona dentro del mismo volumen; si falla, se copia y se borra más abajo.
			if (src.file.renameTo(FileOps.uniqueName(destDir.file, src.name))) return
		}
		if (src is RemoteLoc && destDir is RemoteLoc && src.server.id == destDir.server.id) {
			require(!isInside(destDir, src)) { "No se puede mover una carpeta dentro de sí misma" }
			return Sftp.rename(src, uniqueName(destDir, src.name) as RemoteLoc)
		}
		// El original solo se borra cuando la copia ha terminado entera.
		copy(src, destDir, p)
		p.check()
		delete(src)
	}

	/** Mide lo que se va a transferir para poder mostrar el progreso. */
	fun measure(items: List<Loc>, p: Progress) {
		var bytes = 0L
		var files = 0
		fun walk(loc: Loc, isDir: Boolean, size: Long) {
			p.check()
			if (!isDir) { bytes += size; files++; return }
			list(loc, showHidden = true, SortBy.NAME, false).forEach { walk(it.loc, it.isDir, it.size) }
		}
		for (item in items) when (item) {
			is LocalLoc -> FileOps.totals(listOf(item.file)).let { (b, c) -> bytes += b; files += c }
			is RemoteLoc -> Sftp.stat(item)?.let { walk(item, it.isDir, it.size) }
		}
		p.totalBytes = bytes
		p.totalFiles = files
	}

	/** Copia de un archivo o carpeta entre dos ubicaciones cualesquiera usando flujos. */
	private fun transfer(src: Loc, isDir: Boolean, target: Loc, p: Progress) {
		p.check()
		if (isDir) {
			mkdir(target)
			for (e in list(src, showHidden = true, SortBy.NAME, false)) transfer(e.loc, e.isDir, target.child(e.name), p)
			return
		}
		p.current = src.name
		try {
			read(src) { input -> write(target) { pump(input, it, p) } }
		} catch (e: Exception) {
			// No deja archivos a medias si se cancela o falla.
			runCatching { delete(target) }
			throw e
		}
		p.files++
	}

	fun mkdirs(loc: Loc) = when (loc) {
		is LocalLoc -> if (!loc.file.isDirectory && !loc.file.mkdirs()) throw IOException("No se pudo crear ${loc.path}") else Unit
		is RemoteLoc -> Sftp.mkdirs(loc)
	}

	/**
	 * Copia un archivo sobrescribiendo [dest] y le pone la fecha del original.
	 * Escribe primero en un temporal oculto: si se corta, el archivo que ya hubiera en [dest] sigue intacto.
	 */
	fun replaceFile(src: Loc, dest: Loc, modified: Long, p: Progress) {
		// Sin punto delante: muchos servidores SFTP de hosting prohíben crear archivos ocultos.
		val tmp = dest.parent?.child("${dest.name}$PART") ?: throw IOException("Destino no válido")
		p.current = src.name
		val before = p.bytes
		try {
			read(src) { input -> write(tmp) { pump(input, it, p) } }
			if (exists(dest)) delete(dest)
			rename(tmp, dest)
		} catch (e: CancelledException) {
			runCatching { delete(tmp) }
			throw e
		} catch (e: IOException) {
			runCatching { delete(tmp) }
			// Algunos servidores no dejan crear el temporal o renombrarlo: se reintenta escribiendo directamente.
			p.bytes = before
			try {
				read(src) { input -> write(dest) { pump(input, it, p) } }
			} catch (e2: CancelledException) {
				throw e2
			} catch (e2: IOException) {
				throw IOException("${e2.message} (${dest.path})", e2)
			}
		}
		// Si el sistema no deja cambiar la fecha, el destino queda más nuevo que el original y tampoco se recopia.
		runCatching {
			when (dest) {
				is LocalLoc -> dest.file.setLastModified(modified)
				is RemoteLoc -> Sftp.setModified(dest, modified)
			}
		}
		p.files++
	}

	private fun pump(input: InputStream, output: OutputStream, p: Progress) {
		val buf = ByteArray(64 * 1024)
		while (true) {
			p.check()
			val n = input.read(buf)
			if (n < 0) break
			output.write(buf, 0, n)
			p.bytes += n
		}
	}

	private fun <T> read(loc: Loc, block: (InputStream) -> T): T = when (loc) {
		is LocalLoc -> loc.file.inputStream().use(block)
		is RemoteLoc -> Sftp.read(loc, block)
	}

	private fun write(loc: Loc, block: (OutputStream) -> Unit) = when (loc) {
		is LocalLoc -> loc.file.outputStream().use(block)
		is RemoteLoc -> Sftp.write(loc, block)
	}

	private fun isSame(a: Loc, b: Loc) = when {
		a is LocalLoc && b is LocalLoc -> a.file.canonicalPath == b.file.canonicalPath
		a is RemoteLoc && b is RemoteLoc -> a.server.id == b.server.id && a.path == b.path
		else -> false
	}

	fun isInside(child: Loc, parent: Loc): Boolean = when {
		child is LocalLoc && parent is LocalLoc -> FileOps.isInside(child.file, parent.file)
		child is RemoteLoc && parent is RemoteLoc ->
			child.server.id == parent.server.id && (child.path == parent.path || child.path.startsWith(parent.path.trimEnd('/') + "/"))
		else -> false
	}

	/** Devuelve un nombre libre en [dir]: "foto.jpg" -> "foto (1).jpg". */
	fun uniqueName(dir: Loc, name: String): Loc {
		if (dir is LocalLoc) return LocalLoc(FileOps.uniqueName(dir.file, name))
		val dot = name.lastIndexOf('.').takeIf { it > 0 }
		val base = if (dot != null) name.substring(0, dot) else name
		val ext = if (dot != null) name.substring(dot) else ""
		var target = dir.child(name)
		var i = 1
		while (exists(target)) target = dir.child("$base (${i++})$ext")
		return target
	}

	/**
	 * Archivo local con el contenido de [loc]; los remotos se descargan a la caché para abrirlos o compartirlos.
	 * Si ya se descargó y el remoto no ha cambiado ([size] y [modified] coinciden), se reutiliza.
	 */
	fun localCopy(context: Context, loc: Loc, p: Progress, size: Long = -1, modified: Long = 0): File = when (loc) {
		is LocalLoc -> loc.file
		is RemoteLoc -> File(context.cacheDir, "sftp/${loc.server.id}${loc.path}").also { f ->
			if (size >= 0 && f.length() == size && f.lastModified() == modified) {
				p.bytes += size
				p.files++
				return f
			}
			f.parentFile?.mkdirs()
			p.current = loc.name
			try {
				Sftp.read(loc) { input -> f.outputStream().use { pump(input, it, p) } }
			} catch (e: Exception) {
				f.delete()
				throw e
			}
			f.setLastModified(modified)
			p.files++
		}
	}

	/** Tamaño total y número de archivos de una selección (recorre carpetas). */
	fun totals(entries: Collection<Entry>): Pair<Long, Int> {
		var bytes = 0L
		var count = 0
		fun walk(e: Entry) {
			if (!e.isDir) { bytes += e.size; count++; return }
			if (e.loc is LocalLoc) {
				val (b, c) = FileOps.totals(listOf(e.loc.file))
				bytes += b; count += c
			} else {
				list(e.loc, showHidden = true, SortBy.NAME, false).forEach(::walk)
			}
		}
		entries.forEach(::walk)
		return bytes to count
	}
}
