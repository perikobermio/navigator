@file:OptIn(ExperimentalMaterial3Api::class)

package eus.navigator

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface Dialog {
	data object NewFolder : Dialog
	data object NewFile : Dialog
	data class Rename(val file: File) : Dialog
	data class Delete(val files: List<File>) : Dialog
	data class Details(val files: List<File>) : Dialog
	data class AddShortcut(val file: File) : Dialog
	data class RenameShortcut(val shortcut: Shortcut) : Dialog
}

@Composable
fun BrowserScreen(vm: BrowserViewModel) {
	val drawer = rememberDrawerState(DrawerValue.Closed)
	val scope = rememberCoroutineScope()
	val ctx = LocalContext.current
	var dialog by remember { mutableStateOf<Dialog?>(null) }

	BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
	BackHandler(!drawer.isOpen && vm.selection.isNotEmpty()) { vm.clearSelection() }
	BackHandler(!drawer.isOpen && vm.selection.isEmpty() && vm.query != null) { vm.query = null }
	BackHandler(!drawer.isOpen && vm.selection.isEmpty() && vm.query == null && vm.currentRoot?.dir?.path != vm.dir.path) {
		vm.goUp()
	}

	fun openShortcut(s: Shortcut) {
		scope.launch { drawer.close() }
		val f = File(s.path)
		when {
			f.isDirectory -> vm.open(f)
			f.isFile -> { f.parentFile?.let(vm::open); FileOps.open(ctx, f) }
			else -> vm.toast("«${s.name}» ya no existe")
		}
	}

	ModalNavigationDrawer(
		drawerState = drawer,
		drawerContent = {
			Drawer(
				vm = vm,
				onRoot = { scope.launch { drawer.close() }; vm.open(it.dir) },
				onShortcut = ::openShortcut,
				onAddCurrent = { dialog = Dialog.AddShortcut(vm.dir) },
				onRenameShortcut = { dialog = Dialog.RenameShortcut(it) },
			)
		},
	) {
		Scaffold(
			topBar = {
				Column {
					when {
						vm.selection.isNotEmpty() -> SelectionBar(vm, onDialog = { dialog = it })
						vm.query != null -> SearchBar(vm)
						else -> MainBar(vm, onMenu = { scope.launch { drawer.open() } }, onDialog = { dialog = it })
					}
					Breadcrumbs(vm)
					if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
					else Spacer(Modifier.height(2.dp))
				}
			},
			bottomBar = { vm.clip?.let { PasteBar(it, vm) } },
		) { padding ->
			FileList(vm, padding, onOpenFile = { FileOps.open(ctx, it.file) })
		}
	}

	vm.busy?.let { label ->
		AlertDialog(
			onDismissRequest = {},
			confirmButton = {},
			text = {
				Row(verticalAlignment = Alignment.CenterVertically) {
					CircularProgressIndicator(Modifier.size(24.dp))
					Spacer(Modifier.width(16.dp))
					Text(label)
				}
			},
		)
	}

	when (val d = dialog) {
		null -> {}
		Dialog.NewFolder -> NameDialog("Nueva carpeta", "", onDismiss = { dialog = null }) { vm.createFolder(it) }
		Dialog.NewFile -> NameDialog("Nuevo archivo", "", onDismiss = { dialog = null }) { vm.createFile(it) }
		is Dialog.Rename -> NameDialog("Renombrar", d.file.name, onDismiss = { dialog = null }) { vm.rename(d.file, it) }
		is Dialog.AddShortcut -> NameDialog("Nombre del acceso directo", d.file.name.ifEmpty { d.file.path }, onDismiss = { dialog = null }) {
			vm.addShortcut(d.file, it)
		}
		is Dialog.RenameShortcut -> NameDialog("Renombrar acceso directo", d.shortcut.name, onDismiss = { dialog = null }) {
			vm.renameShortcut(d.shortcut, it)
		}
		is Dialog.Delete -> AlertDialog(
			onDismissRequest = { dialog = null },
			title = { Text("¿Borrar?") },
			text = {
				Text(
					if (d.files.size == 1) "«${d.files[0].name}» se borrará definitivamente."
					else "${d.files.size} elementos se borrarán definitivamente."
				)
			},
			confirmButton = { TextButton({ vm.delete(d.files); dialog = null }) { Text("Borrar") } },
			dismissButton = { TextButton({ dialog = null }) { Text("Cancelar") } },
		)
		is Dialog.Details -> DetailsDialog(d.files) { dialog = null }
	}
}

@Composable
private fun MainBar(vm: BrowserViewModel, onMenu: () -> Unit, onDialog: (Dialog) -> Unit) {
	var menu by remember { mutableStateOf(false) }
	val isShortcut = vm.shortcuts.any { it.path == vm.dir.path }
	TopAppBar(
		navigationIcon = { IconButton(onMenu) { Icon(Icons.Default.Menu, "Menú") } },
		title = {
			Text(
				if (vm.currentRoot?.dir?.path == vm.dir.path) vm.currentRoot!!.name else vm.dir.name,
				maxLines = 1, overflow = TextOverflow.Ellipsis,
			)
		},
		actions = {
			IconButton({ if (isShortcut) vm.removeShortcut(vm.shortcuts.first { it.path == vm.dir.path }) else onDialog(Dialog.AddShortcut(vm.dir)) }) {
				Icon(if (isShortcut) Icons.Filled.Star else Icons.Outlined.Star, "Acceso directo")
			}
			IconButton({ vm.query = "" }) { Icon(Icons.Default.Search, "Buscar") }
			IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Más") }
			DropdownMenu(menu, { menu = false }) {
				DropdownMenuItem({ Text("Nueva carpeta") }, { menu = false; onDialog(Dialog.NewFolder) })
				DropdownMenuItem({ Text("Nuevo archivo") }, { menu = false; onDialog(Dialog.NewFile) })
				HorizontalDivider()
				for ((by, label) in listOf(SortBy.NAME to "Nombre", SortBy.DATE to "Fecha", SortBy.SIZE to "Tamaño", SortBy.TYPE to "Tipo")) {
					DropdownMenuItem(
						text = { Text("Ordenar por ${label.lowercase()}") },
						onClick = { vm.changeSort(by) },
						trailingIcon = {
							if (vm.sort == by) Icon(if (vm.descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward, null)
						},
					)
				}
				HorizontalDivider()
				DropdownMenuItem(
					text = { Text("Mostrar ocultos") },
					onClick = { vm.toggleHidden() },
					trailingIcon = { Checkbox(vm.showHidden, null) },
				)
				DropdownMenuItem({ Text("Actualizar") }, { menu = false; vm.refreshRoots() })
			}
		},
	)
}

@Composable
private fun SelectionBar(vm: BrowserViewModel, onDialog: (Dialog) -> Unit) {
	var menu by remember { mutableStateOf(false) }
	val ctx = LocalContext.current
	val sel = vm.selection.toList()
	TopAppBar(
		navigationIcon = { IconButton(vm::clearSelection) { Icon(Icons.Default.Close, "Cancelar") } },
		title = { Text("${sel.size}") },
		actions = {
			IconButton({ vm.copySelection(cut = false) }) { Icon(Icons.Default.ContentCopy, "Copiar") }
			IconButton({ vm.copySelection(cut = true) }) { Icon(Icons.Default.ContentCut, "Mover") }
			IconButton({ onDialog(Dialog.Delete(sel)) }) { Icon(Icons.Default.Delete, "Borrar") }
			IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Más") }
			DropdownMenu(menu, { menu = false }) {
				DropdownMenuItem({ Text("Seleccionar todo") }, { menu = false; vm.selectAll() })
				if (sel.size == 1) {
					DropdownMenuItem({ Text("Renombrar") }, { menu = false; onDialog(Dialog.Rename(sel[0])) })
					DropdownMenuItem({ Text("Crear acceso directo") }, { menu = false; onDialog(Dialog.AddShortcut(sel[0])) })
				}
				DropdownMenuItem({ Text("Compartir") }, { menu = false; FileOps.share(ctx, sel) })
				DropdownMenuItem({ Text("Detalles") }, { menu = false; onDialog(Dialog.Details(sel)) })
			}
		},
	)
}

@Composable
private fun SearchBar(vm: BrowserViewModel) {
	val focus = remember { FocusRequester() }
	LaunchedEffect(Unit) { focus.requestFocus() }
	TopAppBar(
		navigationIcon = { IconButton({ vm.query = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cerrar") } },
		title = {
			TextField(
				value = vm.query.orEmpty(),
				onValueChange = { vm.query = it },
				placeholder = { Text("Filtrar en esta carpeta") },
				singleLine = true,
				colors = TextFieldDefaults.colors(
					focusedContainerColor = Color.Transparent,
					unfocusedContainerColor = Color.Transparent,
					focusedIndicatorColor = Color.Transparent,
					unfocusedIndicatorColor = Color.Transparent,
				),
				modifier = Modifier.fillMaxWidth().focusRequester(focus),
			)
		},
	)
}

@Composable
private fun Breadcrumbs(vm: BrowserViewModel) {
	val root = vm.currentRoot ?: return
	val crumbs = remember(vm.dir, root) {
		val list = mutableListOf(root.name to root.dir)
		var acc = root.dir
		vm.dir.path.removePrefix(root.dir.path).split('/').filter { it.isNotEmpty() }.forEach {
			acc = File(acc, it)
			list += it to acc
		}
		list
	}
	val state = rememberLazyListState()
	LaunchedEffect(crumbs) { state.scrollToItem(crumbs.lastIndex) }
	LazyRow(
		state = state,
		contentPadding = PaddingValues(horizontal = 12.dp),
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier.fillMaxWidth().height(36.dp),
	) {
		itemsIndexed(crumbs) { i, (name, dir) ->
			val last = i == crumbs.lastIndex
			if (i > 0) Icon(
				Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
				Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Text(
				name,
				style = MaterialTheme.typography.labelLarge,
				color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier
					.clip(CircleShape)
					.combinedClickable(onClick = { if (!last) vm.open(dir) })
					.padding(horizontal = 6.dp, vertical = 4.dp),
			)
		}
	}
}

@Composable
private fun FileList(vm: BrowserViewModel, padding: PaddingValues, onOpenFile: (Entry) -> Unit) {
	val items = vm.visible
	if (items.isEmpty() && !vm.loading) {
		Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
			Text(
				if (vm.query.isNullOrBlank()) "Carpeta vacía" else "Sin resultados",
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		return
	}
	val state = rememberLazyListState()
	LaunchedEffect(vm.dir) { state.scrollToItem(0) }
	LazyColumn(state = state, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
		items(items, key = { it.file.path }) { e ->
			val selected = e.file in vm.selection
			Row(
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier
					.fillMaxWidth()
					.background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
					.combinedClickable(
						onClick = {
							when {
								vm.selection.isNotEmpty() -> vm.toggle(e.file)
								e.isDir -> vm.open(e.file)
								else -> onOpenFile(e)
							}
						},
						onLongClick = { vm.toggle(e.file) },
					)
					.padding(horizontal = 16.dp, vertical = 10.dp),
			) {
				Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
					if (selected) {
						Icon(
							Icons.Default.Check, null,
							tint = MaterialTheme.colorScheme.onPrimary,
							modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(6.dp),
						)
					} else {
						Icon(
							iconFor(e), null,
							tint = if (e.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.size(28.dp),
						)
					}
				}
				Spacer(Modifier.width(16.dp))
				Column(Modifier.weight(1f)) {
					Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
					Text(
						(if (e.isDir) "${e.children} elementos" else FileOps.size(e.size)) + " · " + FileOps.date(e.modified),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		}
	}
}

@Composable
private fun PasteBar(clip: Clip, vm: BrowserViewModel) {
	Surface(tonalElevation = 3.dp) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
		) {
			Text(
				"${clip.files.size} para ${if (clip.cut) "mover" else "copiar"}",
				modifier = Modifier.weight(1f),
				style = MaterialTheme.typography.bodyMedium,
			)
			TextButton(vm::cancelClip) { Text("Cancelar") }
			Spacer(Modifier.width(8.dp))
			Button(vm::paste) { Text("Pegar aquí") }
		}
	}
}

@Composable
private fun Drawer(
	vm: BrowserViewModel,
	onRoot: (StorageRoot) -> Unit,
	onShortcut: (Shortcut) -> Unit,
	onAddCurrent: () -> Unit,
	onRenameShortcut: (Shortcut) -> Unit,
) {
	val ctx = LocalContext.current
	ModalDrawerSheet {
		LazyColumn(contentPadding = PaddingValues(12.dp)) {
			item { SectionTitle("Almacenamiento") }
			items(vm.roots) { r ->
				val space = remember(r) { FileOps.freeSpace(r.dir) }
				NavigationDrawerItem(
					icon = { Icon(if (r.removable) Icons.Default.SdStorage else Icons.Default.PhoneAndroid, null) },
					label = {
						Column {
							Text(r.name)
							space?.let { (free, total) ->
								Text(
									"${FileOps.size(free)} libres de ${FileOps.size(total)}",
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}
						}
					},
					selected = vm.currentRoot == r && vm.dir.path == r.dir.path,
					onClick = { onRoot(r) },
				)
			}
			item {
				HorizontalDivider(Modifier.padding(vertical = 8.dp))
				Row(verticalAlignment = Alignment.CenterVertically) {
					Box(Modifier.weight(1f)) { SectionTitle("Accesos directos") }
					IconButton(onAddCurrent) { Icon(Icons.Default.Add, "Añadir carpeta actual") }
				}
			}
			if (vm.shortcuts.isEmpty()) item {
				Text(
					"Pulsa + o la estrella para guardar la carpeta actual.\nMantén pulsado un archivo o carpeta para crear uno.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
				)
			}
			itemsIndexed(vm.shortcuts, key = { _, s -> s.path }) { i, s ->
				var menu by remember { mutableStateOf(false) }
				val f = File(s.path)
				NavigationDrawerItem(
					icon = { Icon(if (f.isFile) iconForName(f.name) else Icons.Default.Folder, null) },
					label = {
						Column {
							Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
							Text(
								s.path, maxLines = 1, overflow = TextOverflow.Ellipsis,
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					},
					badge = {
						Box {
							IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Opciones") }
							DropdownMenu(menu, { menu = false }) {
								DropdownMenuItem({ Text("Renombrar") }, { menu = false; onRenameShortcut(s) })
								if (i > 0) DropdownMenuItem({ Text("Subir") }, { menu = false; vm.moveShortcut(s, -1) })
								if (i < vm.shortcuts.lastIndex) DropdownMenuItem({ Text("Bajar") }, { menu = false; vm.moveShortcut(s, 1) })
								DropdownMenuItem({ Text("Añadir a pantalla de inicio") }, { menu = false; pinToHome(ctx, s) })
								DropdownMenuItem({ Text("Quitar") }, { menu = false; vm.removeShortcut(s) })
							}
						}
					},
					selected = vm.dir.path == s.path,
					onClick = { onShortcut(s) },
				)
			}
		}
	}
}

@Composable
private fun SectionTitle(text: String) {
	Text(
		text,
		style = MaterialTheme.typography.titleSmall,
		fontWeight = FontWeight.SemiBold,
		color = MaterialTheme.colorScheme.primary,
		modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
	)
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
	// Preselecciona el nombre sin la extensión, como hacen los exploradores de escritorio.
	val base = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
	var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, base))) }
	val focus = remember { FocusRequester() }
	LaunchedEffect(Unit) { focus.requestFocus() }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			OutlinedTextField(value, { value = it }, singleLine = true, modifier = Modifier.focusRequester(focus))
		},
		confirmButton = {
			TextButton({ onConfirm(value.text); onDismiss() }, enabled = value.text.isNotBlank()) { Text("Aceptar") }
		},
		dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
	)
}

@Composable
private fun DetailsDialog(files: List<File>, onDismiss: () -> Unit) {
	val totals by produceState<Pair<Long, Int>?>(null, files) {
		value = withContext(Dispatchers.IO) { FileOps.totals(files) }
	}
	val single = files.singleOrNull()
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(single?.name ?: "${files.size} elementos", maxLines = 2, overflow = TextOverflow.Ellipsis) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				val size = totals?.let { (bytes, count) ->
					FileOps.size(bytes) + if (single?.isFile == true) "" else " · $count archivos"
				} ?: "Calculando…"
				if (single != null) {
					Detail("Ruta", single.path)
					Detail("Tipo", if (single.isDirectory) "Carpeta" else FileOps.mime(single))
					Detail("Modificado", FileOps.date(single.lastModified()))
				}
				Detail("Tamaño", size)
				if (single != null) Detail("Permisos", (if (single.canRead()) "Lectura" else "") + (if (single.canWrite()) " · Escritura" else ""))
			}
		},
		confirmButton = { TextButton(onDismiss) { Text("Cerrar") } },
	)
}

@Composable
private fun Detail(label: String, value: String) {
	Column {
		Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
		Text(value, style = MaterialTheme.typography.bodyMedium)
	}
}

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
	Scaffold { padding ->
		Column(
			modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
			verticalArrangement = Arrangement.Center,
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Icon(Icons.Default.Folder, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
			Spacer(Modifier.height(24.dp))
			Text("Acceso a archivos", style = MaterialTheme.typography.headlineSmall)
			Spacer(Modifier.height(8.dp))
			Text(
				"Para explorar el almacenamiento, Navigator necesita permiso para gestionar todos los archivos.",
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Spacer(Modifier.height(24.dp))
			Button(onRequest) { Text("Conceder permiso") }
		}
	}
}

private fun iconFor(e: Entry): ImageVector = if (e.isDir) Icons.Default.Folder else iconForName(e.name)

private fun iconForName(name: String): ImageVector = when (name.substringAfterLast('.', "").lowercase()) {
	"jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg" -> Icons.Default.Image
	"mp4", "mkv", "avi", "mov", "webm", "3gp" -> Icons.Default.Movie
	"mp3", "wav", "ogg", "flac", "m4a", "aac", "opus" -> Icons.Default.AudioFile
	"pdf" -> Icons.Default.PictureAsPdf
	"zip", "rar", "7z", "tar", "gz", "xz" -> Icons.Default.FolderZip
	"apk" -> Icons.Default.Android
	"txt", "md", "doc", "docx", "odt", "rtf", "csv", "xls", "xlsx", "json", "xml", "html" -> Icons.Default.Description
	else -> Icons.AutoMirrored.Filled.InsertDriveFile
}
