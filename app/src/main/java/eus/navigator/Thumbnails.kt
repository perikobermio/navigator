package eus.navigator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.jcraft.jsch.ChannelSftp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp")

fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in VIDEO_EXT

/**
 * Miniaturas de imágenes y vídeos. Se generan solo para lo que está en pantalla, fuera del hilo
 * principal y con una caché en memoria. Cada tipo de trabajo tiene su cola para no frenar a los demás:
 * imágenes locales, vídeos locales (extraer un fotograma es caro) y remotos (limitados por la red).
 * Las miniaturas remotas se guardan además en disco para no volver a descargar nada.
 */
object Thumbnails {
	private const val SIZE = 320
	/** Imágenes remotas más grandes no se descargan para hacer la miniatura. */
	private const val MAX_REMOTE_IMAGE = 25L * 1024 * 1024
	/** Máximo que se lee de un vídeo remoto buscando un fotograma antes de rendirse. */
	private const val MAX_REMOTE_VIDEO_READ = 16L * 1024 * 1024
	private const val MAX_DISK = 50L * 1024 * 1024

	private val images = Dispatchers.IO.limitedParallelism(2)
	private val videos = Dispatchers.IO.limitedParallelism(1)
	private val remote = Dispatchers.IO.limitedParallelism(2)

	private lateinit var diskDir: File

	private val cache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
		override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
	}

	/** Archivos que no se pudieron decodificar, para no reintentarlos en cada pasada. */
	private val failed = HashSet<String>()

	fun init(context: Context) {
		diskDir = File(context.cacheDir, "thumbs")
		trimDisk()
	}

	/** Mantiene la caché en disco por debajo del límite borrando primero las miniaturas más antiguas. */
	private fun trimDisk() {
		val files = diskDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
		var total = 0L
		for (f in files) {
			total += f.length()
			if (total > MAX_DISK) f.delete()
		}
	}

	fun cached(key: String): ImageBitmap? = cache.get(key)

	suspend fun load(e: Entry, key: String): ImageBitmap? {
		cache.get(key)?.let { return it }
		if (synchronized(failed) { key in failed }) return null
		val video = isVideo(e.name)
		val loc = e.loc
		val lane = if (loc is RemoteLoc) remote else if (video) videos else images
		// Si el elemento sale de pantalla antes de su turno, la corrutina se cancela y no se llega a generar.
		return withContext(lane) {
			cache.get(key)?.let { return@withContext it }
			val bitmap = runCatching {
				when (loc) {
					is LocalLoc -> if (video) video(loc.file) else image(loc.file)
					is RemoteLoc -> remote(loc, e, video, key)
				}
			}.getOrNull()
			if (bitmap == null) {
				synchronized(failed) { failed += key }
				null
			} else {
				bitmap.asImageBitmap().also { cache.put(key, it) }
			}
		}
	}

	private fun image(file: File): Bitmap? {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			return ThumbnailUtils.createImageThumbnail(file, Size(SIZE, SIZE), null)
		}
		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeFile(file.path, bounds)
		var sample = 1
		while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
		return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
	}

	@Suppress("DEPRECATION")
	private fun video(file: File): Bitmap? =
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ThumbnailUtils.createVideoThumbnail(file, Size(SIZE, SIZE), null)
		else ThumbnailUtils.createVideoThumbnail(file.path, MediaStore.Images.Thumbnails.MINI_KIND)

	private fun remote(loc: RemoteLoc, e: Entry, video: Boolean, key: String): Bitmap? {
		val disk = File(diskDir, sha1(key) + ".jpg")
		if (disk.exists()) BitmapFactory.decodeFile(disk.path)?.let { return it }
		val bitmap = if (video) remoteVideo(loc, e.size) else remoteImage(loc, e.size)
		bitmap?.let { b ->
			diskDir.mkdirs()
			runCatching { disk.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
		}
		return bitmap
	}

	private fun remoteImage(loc: RemoteLoc, size: Long): Bitmap? {
		if (size > MAX_REMOTE_IMAGE) return null
		val tmp = File.createTempFile("thumb", null, diskDir.also { it.mkdirs() })
		try {
			Sftp.read(loc) { input -> tmp.outputStream().use { input.copyTo(it) } }
			return image(tmp)
		} finally {
			tmp.delete()
		}
	}

	/** Extrae un fotograma leyendo del servidor solo los trozos del vídeo que pide el decodificador. */
	private fun remoteVideo(loc: RemoteLoc, size: Long): Bitmap? = Sftp.use(loc.server) { ch ->
		val mmr = MediaMetadataRetriever()
		try {
			mmr.setDataSource(SftpDataSource(ch, loc.path, size))
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
				mmr.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIZE, SIZE)
			} else {
				mmr.frameAtTime?.let { ThumbnailUtils.extractThumbnail(it, SIZE, SIZE) }
			}
		} finally {
			mmr.release()
		}
	}

	private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

	/** Acceso aleatorio a un archivo remoto por bloques, con una pequeña caché y un tope de lectura. */
	private class SftpDataSource(private val ch: ChannelSftp, private val path: String, private val length: Long) : MediaDataSource() {
		private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
			override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 16
		}
		private var fetched = 0L

		override fun getSize() = length

		override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
			if (position >= length) return -1
			val index = position / BLOCK
			val block = blocks.getOrPut(index) { fetch(index * BLOCK) }
			val start = (position - index * BLOCK).toInt()
			val n = minOf(size, block.size - start)
			if (n <= 0) return -1
			System.arraycopy(block, start, buffer, offset, n)
			return n
		}

		private fun fetch(from: Long): ByteArray {
			val want = minOf(BLOCK.toLong(), length - from).toInt()
			fetched += want
			if (fetched > MAX_REMOTE_VIDEO_READ) throw IOException("Demasiados datos para una miniatura")
			val out = ByteArray(want)
			ch.get(path, null, from).use { input ->
				var read = 0
				while (read < want) {
					val n = input.read(out, read, want - read)
					if (n < 0) break
					read += n
				}
				return if (read == want) out else out.copyOf(read)
			}
		}

		override fun close() {}

		companion object {
			const val BLOCK = 256 * 1024
		}
	}
}

/** Miniatura de [e] si es una imagen o vídeo; null mientras carga o si no aplica. */
@Composable
fun rememberThumbnail(e: Entry): ImageBitmap? {
	if (e.isDir || (!isVideo(e.name) && !isImage(e.name))) return null
	val loc = e.loc
	val key = when (loc) {
		is LocalLoc -> loc.path
		is RemoteLoc -> "sftp:${loc.server.id}:${loc.path}"
	} + ":${e.modified}:${e.size}"
	val thumb by produceState(Thumbnails.cached(key), key) {
		if (value == null) value = Thumbnails.load(e, key)
	}
	return thumb
}
