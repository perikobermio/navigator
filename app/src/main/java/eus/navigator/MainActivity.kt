package eus.navigator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.io.File

class MainActivity : ComponentActivity() {
	private val vm: BrowserViewModel by viewModels()
	private var granted by mutableStateOf(false)

	private val legacyPermission =
		registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checkPermission() }

	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)
		checkPermission()
		if (savedInstanceState == null) handleIntent(intent)

		setContent {
			val dark = isSystemInDarkTheme()
			val ctx = LocalContext.current
			val colors = when {
				Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
					if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
				dark -> darkColorScheme()
				else -> lightColorScheme()
			}
			MaterialTheme(colorScheme = colors) {
				if (granted) BrowserScreen(vm) else PermissionScreen(::requestPermission)
			}
		}
	}

	override fun onResume() {
		super.onResume()
		val before = granted
		checkPermission()
		// Refresca al volver (p. ej. tras conceder permiso o editar un archivo en otra app).
		if (granted) if (before) vm.refresh() else vm.refreshRoots()
		// La sincronización automática puede haber corrido en segundo plano.
		vm.reloadSync()
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		handleIntent(intent)
	}

	private fun handleIntent(intent: Intent?) {
		val path = intent?.getStringExtra(EXTRA_PATH) ?: return
		val f = File(path)
		when {
			f.isDirectory -> vm.open(LocalLoc(f))
			f.isFile -> {
				f.parentFile?.let { vm.open(LocalLoc(it)) }
				FileOps.open(this, f)
			}
			else -> vm.toast("El acceso directo ya no existe")
		}
	}

	private fun checkPermission() {
		granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			Environment.isExternalStorageManager()
		} else {
			checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
		}
	}

	private fun requestPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
			runCatching { startActivity(appIntent) }
				.onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
		} else {
			legacyPermission.launch(
				arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
			)
		}
	}
}
