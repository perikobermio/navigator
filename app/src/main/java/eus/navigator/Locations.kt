package eus.navigator

import android.content.Context
import java.io.File
import java.io.IOException

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

/** Operaciones sobre [Loc] que eligen la implementación local o SFTP. Todas bloquean: llamar desde IO. */
object Fs {
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

	fun copy(src: Loc, destDir: Loc, progress: (String) -> Unit) {
		if (src is LocalLoc && destDir is LocalLoc) {
			progress(src.name)
			return FileOps.copy(src.file, destDir.file)
		}
		val dir = isDir(src) ?: throw IOException("«${src.name}» ya no existe")
		require(!(dir && isInside(destDir, src))) { "No se puede copiar una carpeta dentro de sí misma" }
		transfer(src, dir, uniqueName(destDir, src.name), progress)
	}

	fun move(src: Loc, destDir: Loc, progress: (String) -> Unit) {
		if (src is LocalLoc && destDir is LocalLoc) {
			progress(src.name)
			return FileOps.move(src.file, destDir.file)
		}
		if (src is RemoteLoc && destDir is RemoteLoc && src.server.id == destDir.server.id) {
			if (src.parent?.path == destDir.path) return
			require(!isInside(destDir, src)) { "No se puede mover una carpeta dentro de sí misma" }
			progress(src.name)
			return Sftp.rename(src, uniqueName(destDir, src.name) as RemoteLoc)
		}
		// Entre dispositivos distintos no hay renombrado posible: copia y borra.
		copy(src, destDir, progress)
		delete(src)
	}

	/** Copia de un archivo o carpeta entre dos ubicaciones cualesquiera usando flujos. */
	private fun transfer(src: Loc, isDir: Boolean, target: Loc, progress: (String) -> Unit) {
		if (isDir) {
			mkdir(target)
			for (e in list(src, showHidden = true, SortBy.NAME, false)) transfer(e.loc, e.isDir, target.child(e.name), progress)
			return
		}
		progress(src.name)
		read(src) { input -> write(target) { input.copyTo(it) } }
	}

	private fun <T> read(loc: Loc, block: (java.io.InputStream) -> T): T = when (loc) {
		is LocalLoc -> loc.file.inputStream().use(block)
		is RemoteLoc -> Sftp.read(loc, block)
	}

	private fun write(loc: Loc, block: (java.io.OutputStream) -> Unit) = when (loc) {
		is LocalLoc -> loc.file.outputStream().use(block)
		is RemoteLoc -> Sftp.write(loc, block)
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

	/** Archivo local con el contenido de [loc]; los remotos se descargan a la caché para abrirlos o compartirlos. */
	fun localCopy(context: Context, loc: Loc): File = when (loc) {
		is LocalLoc -> loc.file
		is RemoteLoc -> File(context.cacheDir, "sftp/${loc.server.id}${loc.path}").also { f ->
			f.parentFile?.mkdirs()
			Sftp.read(loc) { input -> f.outputStream().use { input.copyTo(it) } }
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
