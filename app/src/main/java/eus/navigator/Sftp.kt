package eus.navigator

import android.content.Context
import android.content.SharedPreferences
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.UnknownHostException
import java.util.UUID

/** Servidor SFTP guardado. [path] vacío significa la carpeta personal del usuario. */
data class Server(
	val id: String = UUID.randomUUID().toString(),
	val name: String,
	val host: String,
	val port: Int = 22,
	val user: String,
	val password: String,
	val path: String,
) {
	val address get() = if (port == 22) host else "$host:$port"
}

/** Servidores guardados en SharedPreferences como JSON (privadas de la app, sin copia de seguridad). */
class ServerStore(context: Context) {
	private val prefs = context.getSharedPreferences("servers", Context.MODE_PRIVATE)

	fun load(): List<Server> {
		val json = JSONArray(prefs.getString("list", "[]"))
		return (0 until json.length()).map {
			val o = json.getJSONObject(it)
			Server(
				o.getString("id"), o.getString("name"), o.getString("host"), o.getInt("port"),
				o.getString("user"), o.getString("password"), o.getString("path"),
			)
		}
	}

	fun save(list: List<Server>) {
		val json = JSONArray()
		list.forEach {
			json.put(
				JSONObject().put("id", it.id).put("name", it.name).put("host", it.host).put("port", it.port)
					.put("user", it.user).put("password", it.password).put("path", it.path)
			)
		}
		prefs.edit().putString("list", json.toString()).apply()
	}
}

/**
 * Conexiones SFTP. Mantiene una sesión SSH por servidor y abre un canal por operación,
 * así varias operaciones (p. ej. leer de un servidor y escribir en otro) no se pisan.
 */
object Sftp {
	private val jsch = JSch()
	private val sessions = HashMap<String, Session>()
	private lateinit var hostKeys: SharedPreferences

	fun init(context: Context) {
		hostKeys = context.getSharedPreferences("sftp_host_keys", Context.MODE_PRIVATE)
	}

	/** Olvida la sesión y la clave del host aceptada, p. ej. tras editar el servidor. */
	fun forget(s: Server) {
		drop(s)
		hostKeys.edit().remove(s.address).apply()
	}

	fun closeAll() = synchronized(sessions) {
		sessions.values.forEach { it.disconnect() }
		sessions.clear()
	}

	fun <T> use(s: Server, block: (ChannelSftp) -> T): T {
		val ch = try {
			channel(s)
		} catch (e: JSchException) {
			// La sesión guardada puede estar muerta aunque diga estar conectada: reintenta una vez.
			drop(s)
			try { channel(s) } catch (e: JSchException) { throw IOException(friendly(e), e) }
		}
		try {
			return block(ch)
		} catch (e: SftpException) {
			throw IOException(friendly(e), e)
		} finally {
			ch.disconnect()
		}
	}

	fun realPath(s: Server, path: String): String = use(s) { it.realpath(path.ifBlank { "." }) }

	fun list(dir: RemoteLoc, showHidden: Boolean): List<Entry> = use(dir.server) { ch ->
		entries(ch, dir.path)
			.filter { showHidden || !it.filename.startsWith(".") }
			.map { e ->
				val child = dir.child(e.filename)
				// Los enlaces simbólicos se resuelven para saber si apuntan a una carpeta.
				val a = if (e.attrs.isLink) runCatching { ch.stat(child.path) }.getOrDefault(e.attrs) else e.attrs
				Entry(child, e.filename, a.isDir, if (a.isDir) 0 else a.size, a.mTime * 1000L, -1)
			}
	}

	fun stat(loc: RemoteLoc): SftpATTRS? = use(loc.server) { ch ->
		try {
			ch.stat(loc.path)
		} catch (e: SftpException) {
			if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw e
		}
	}

	fun mkdir(loc: RemoteLoc) = use(loc.server) { it.mkdir(loc.path) }

	/** Crea la carpeta y las intermedias que falten. */
	fun mkdirs(loc: RemoteLoc) = use(loc.server) { ch ->
		var path = ""
		for (part in loc.path.split('/').filter { it.isNotEmpty() }) {
			path += "/$part"
			val exists = try { ch.stat(path).isDir } catch (e: SftpException) {
				if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw e
			}
			if (!exists) ch.mkdir(path)
		}
	}

	fun setModified(loc: RemoteLoc, millis: Long) = use(loc.server) { it.setMtime(loc.path, (millis / 1000).toInt()) }

	fun createFile(loc: RemoteLoc) = use(loc.server) { it.put(ByteArrayInputStream(ByteArray(0)), loc.path) }

	fun rename(from: RemoteLoc, to: RemoteLoc) = use(from.server) { it.rename(from.path, to.path) }

	fun delete(loc: RemoteLoc) = use(loc.server) { rm(it, loc.path) }

	fun <T> read(loc: RemoteLoc, block: (InputStream) -> T): T = use(loc.server) { ch -> ch.get(loc.path).use(block) }

	fun write(loc: RemoteLoc, block: (OutputStream) -> Unit) = use(loc.server) { ch -> ch.put(loc.path).use(block) }

	private fun rm(ch: ChannelSftp, path: String) {
		if (ch.lstat(path).isDir) {
			entries(ch, path).forEach { rm(ch, "$path/${it.filename}") }
			ch.rmdir(path)
		} else {
			ch.rm(path)
		}
	}

	private fun entries(ch: ChannelSftp, path: String): List<ChannelSftp.LsEntry> =
		ch.ls(path).filterIsInstance<ChannelSftp.LsEntry>().filter { it.filename != "." && it.filename != ".." }

	private fun channel(s: Server): ChannelSftp =
		(session(s).openChannel("sftp") as ChannelSftp).apply { connect(TIMEOUT) }

	private fun session(s: Server): Session = synchronized(sessions) {
		sessions[s.id]?.takeIf { it.isConnected }?.let { return it }
		val session = jsch.getSession(s.user, s.host, s.port).apply {
			setPassword(s.password.toByteArray())
			userInfo = PasswordInfo(s.password)
			// La clave del host se comprueba a mano abajo: se acepta la primera vez y luego debe coincidir.
			setConfig("StrictHostKeyChecking", "no")
			setConfig("PreferredAuthentications", "password,keyboard-interactive")
			setServerAliveInterval(30_000)
		}
		try {
			session.connect(TIMEOUT)
		} catch (e: JSchException) {
			throw IOException(friendly(e), e)
		}
		checkHostKey(s, session)
		sessions[s.id] = session
		session
	}

	private fun checkHostKey(s: Server, session: Session) {
		val key = session.hostKey.key
		val known = hostKeys.getString(s.address, null)
		if (known == null) {
			hostKeys.edit().putString(s.address, key).apply()
		} else if (known != key) {
			session.disconnect()
			throw IOException("La clave de ${s.address} ha cambiado. Si es legítimo, edita y guarda el servidor para aceptarla.")
		}
	}

	private fun drop(s: Server) = synchronized(sessions) {
		sessions.remove(s.id)?.disconnect()
	}

	private fun friendly(e: Exception): String = when {
		e is SftpException -> when (e.id) {
			ChannelSftp.SSH_FX_NO_SUCH_FILE -> "No existe en el servidor"
			ChannelSftp.SSH_FX_PERMISSION_DENIED -> "Permiso denegado en el servidor"
			else -> e.message ?: "Error SFTP"
		}
		e.cause is UnknownHostException -> "Host desconocido"
		e.message?.contains("Auth fail", ignoreCase = true) == true -> "Usuario o contraseña incorrectos"
		e.message?.contains("timeout", ignoreCase = true) == true -> "El servidor no responde"
		else -> "No se pudo conectar: ${e.message}"
	}

	private const val TIMEOUT = 15_000

	/** Responde con la contraseña tanto a la autenticación por contraseña como a la interactiva. */
	private class PasswordInfo(private val password: String) : UserInfo, UIKeyboardInteractive {
		override fun getPassword() = password
		override fun getPassphrase(): String? = null
		override fun promptPassword(message: String?) = true
		override fun promptPassphrase(message: String?) = false
		override fun promptYesNo(message: String?) = false
		override fun showMessage(message: String?) {}
		override fun promptKeyboardInteractive(
			destination: String?, name: String?, instruction: String?, prompt: Array<String>, echo: BooleanArray,
		): Array<String> = Array(prompt.size) { password }
	}
}
