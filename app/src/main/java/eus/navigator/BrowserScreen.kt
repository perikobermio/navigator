@file:OptIn(ExperimentalMaterial3Api::class)

package eus.navigator

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private sealed interface Dialog {
	data object NewFolder : Dialog
	data object NewFile : Dialog
	data class Rename(val loc: Loc) : Dialog
	data class Delete(val items: List<Loc>) : Dialog
	data class Details(val entries: List<Entry>) : Dialog
	data class AddShortcut(val file: File) : Dialog
	data class RenameShortcut(val shortcut: Shortcut) : Dialog
	data class EditServer(val server: Server?, val isNew: Boolean = server == null) : Dialog
	data class SendTo(val items: List<Loc>) : Dialog
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
	BackHandler(!drawer.isOpen && vm.selection.isEmpty() && vm.query == null && !vm.atRoot) {
		vm.goUp()
	}

	fun openShortcut(s: Shortcut) {
		scope.launch { drawer.close() }
		val f = File(s.path)
		when {
			f.isDirectory -> vm.open(LocalLoc(f))
			f.isFile -> { f.parentFile?.let { vm.open(LocalLoc(it)) }; FileOps.open(ctx, f) }
			else -> vm.toast("«${s.name}» ya no existe")
		}
	}

	fun openServer(s: Server) {
		scope.launch { drawer.close() }
		vm.openServer(s)
	}

	ModalNavigationDrawer(
		drawerState = drawer,
		drawerContent = {
			Drawer(
				vm = vm,
				onRoot = { scope.launch { drawer.close() }; vm.open(LocalLoc(it.dir)) },
				onShortcut = ::openShortcut,
				onAddCurrent = { (vm.dir as? LocalLoc)?.let { dialog = Dialog.AddShortcut(it.file) } ?: vm.toast("Solo carpetas locales") },
				onRenameShortcut = { dialog = Dialog.RenameShortcut(it) },
				onServer = ::openServer,
				onEditServer = { dialog = Dialog.EditServer(it) },
				onCopyServer = {
					dialog = Dialog.EditServer(it.copy(id = UUID.randomUUID().toString(), name = "${it.name} (copia)"), isNew = true)
				},
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
			FileList(vm, padding, onOpenFile = { e -> vm.withLocalFiles(listOf(e)) { FileOps.open(ctx, it[0]) } })
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

	vm.transferState?.let { TransferDialog(it, vm::cancelTransfer) }

	when (val d = dialog) {
		null -> {}
		Dialog.NewFolder -> NameDialog("Nueva carpeta", "", onDismiss = { dialog = null }) { vm.createFolder(it) }
		Dialog.NewFile -> NameDialog("Nuevo archivo", "", onDismiss = { dialog = null }) { vm.createFile(it) }
		is Dialog.Rename -> NameDialog("Renombrar", d.loc.name, onDismiss = { dialog = null }) { vm.rename(d.loc, it) }
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
					if (d.items.size == 1) "«${d.items[0].name}» se borrará definitivamente."
					else "${d.items.size} elementos se borrarán definitivamente."
				)
			},
			confirmButton = { TextButton({ vm.delete(d.items); dialog = null }) { Text("Borrar") } },
			dismissButton = { TextButton({ dialog = null }) { Text("Cancelar") } },
		)
		is Dialog.Details -> DetailsDialog(d.entries) { dialog = null }
		is Dialog.EditServer -> ServerDialog(d.server, d.isNew, onDismiss = { dialog = null }, onSave = vm::saveServer)
		is Dialog.SendTo -> SendDialog(vm, d.items) { dialog = null }
	}
}

@Composable
private fun MainBar(vm: BrowserViewModel, onMenu: () -> Unit, onDialog: (Dialog) -> Unit) {
	var menu by remember { mutableStateOf(false) }
	val local = vm.dir as? LocalLoc
	val isShortcut = local != null && vm.shortcuts.any { it.path == local.path }
	TopAppBar(
		navigationIcon = { IconButton(onMenu) { Icon(Icons.Default.Menu, "Menú") } },
		title = {
			Text(
				if (vm.atRoot) vm.root?.first ?: vm.dir.name else vm.dir.name,
				maxLines = 1, overflow = TextOverflow.Ellipsis,
			)
		},
		actions = {
			if (local != null) IconButton({
				if (isShortcut) vm.removeShortcut(vm.shortcuts.first { it.path == local.path }) else onDialog(Dialog.AddShortcut(local.file))
			}) {
				// Ojo: Icons.Outlined.Star también es una estrella rellena; la hueca es StarBorder.
				if (isShortcut) Icon(Icons.Filled.Star, "Quitar acceso directo", tint = MaterialTheme.colorScheme.primary)
				else Icon(Icons.Default.StarBorder, "Crear acceso directo")
			}
			IconButton(vm::toggleGrid) {
				if (vm.grid) Icon(Icons.AutoMirrored.Filled.ViewList, "Ver como lista")
				else Icon(Icons.Default.GridView, "Ver como cuadrícula")
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
			IconButton({ onDialog(Dialog.SendTo(sel)) }) { Icon(Icons.AutoMirrored.Filled.Send, "Enviar a…") }
			IconButton({ vm.copySelection(cut = false) }) { Icon(Icons.Default.ContentCopy, "Copiar") }
			IconButton({ vm.copySelection(cut = true) }) { Icon(Icons.Default.ContentCut, "Mover") }
			IconButton({ onDialog(Dialog.Delete(sel)) }) { Icon(Icons.Default.Delete, "Borrar") }
			IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Más") }
			DropdownMenu(menu, { menu = false }) {
				DropdownMenuItem({ Text("Seleccionar todo") }, { menu = false; vm.selectAll() })
				if (sel.size == 1) {
					DropdownMenuItem({ Text("Renombrar") }, { menu = false; onDialog(Dialog.Rename(sel[0])) })
					(sel[0] as? LocalLoc)?.let { l ->
						DropdownMenuItem({ Text("Crear acceso directo") }, { menu = false; onDialog(Dialog.AddShortcut(l.file)) })
					}
				}
				DropdownMenuItem({ Text("Compartir") }, { menu = false; vm.withLocalFiles(vm.selectedEntries) { FileOps.share(ctx, it) } })
				DropdownMenuItem({ Text("Detalles") }, { menu = false; onDialog(Dialog.Details(vm.selectedEntries)) })
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
	val (rootName, rootLoc) = vm.root ?: return
	val crumbs = remember(vm.dir, rootLoc) {
		val list = mutableListOf(rootName to rootLoc)
		var acc = rootLoc
		vm.dir.path.removePrefix(rootLoc.path).split('/').filter { it.isNotEmpty() }.forEach {
			acc = acc.child(it)
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
		Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
			val err = vm.loadError
			if (err != null) {
				Column(horizontalAlignment = Alignment.CenterHorizontally) {
					Text(err, color = MaterialTheme.colorScheme.error)
					Spacer(Modifier.height(12.dp))
					TextButton(vm::refresh) { Text("Reintentar") }
				}
			} else {
				Text(
					if (vm.query.isNullOrBlank()) "Carpeta vacía" else "Sin resultados",
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		return
	}
	val onClick = { e: Entry ->
		when {
			vm.selection.isNotEmpty() -> vm.toggle(e.loc)
			e.isDir -> vm.open(e.loc)
			else -> onOpenFile(e)
		}
	}
	if (vm.grid) {
		val state = rememberLazyGridState()
		LaunchedEffect(vm.dir) { state.scrollToItem(0) }
		LazyVerticalGrid(
			columns = GridCells.Fixed(2),
			state = state,
			contentPadding = PaddingValues(
				start = 8.dp, end = 8.dp,
				top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 4.dp,
			),
			modifier = Modifier.fillMaxSize(),
		) {
			gridItems(items, key = { it.loc.path }) { e ->
				GridItem(e, selected = e.loc in vm.selection, onClick = { onClick(e) }, onLongClick = { vm.toggle(e.loc) })
			}
		}
	} else {
		val state = rememberLazyListState()
		LaunchedEffect(vm.dir) { state.scrollToItem(0) }
		LazyColumn(state = state, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
			items(items, key = { it.loc.path }) { e ->
				ListItemRow(e, selected = e.loc in vm.selection, onClick = { onClick(e) }, onLongClick = { vm.toggle(e.loc) })
			}
		}
	}
}

private fun subtitle(e: Entry) =
	(if (!e.isDir) FileOps.size(e.size) else if (e.children >= 0) "${e.children} elementos" else "Carpeta") +
		" · " + FileOps.date(e.modified)

@Composable
private fun ListItemRow(e: Entry, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
	val thumb = rememberThumbnail(e)
	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier
			.fillMaxWidth()
			.background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
			.combinedClickable(onClick = onClick, onLongClick = onLongClick)
			.padding(horizontal = 16.dp, vertical = 10.dp),
	) {
		Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
			when {
				selected -> SelectedMark()
				thumb != null -> Image(
					thumb, null, contentScale = ContentScale.Crop,
					modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
				)
				else -> Icon(iconFor(e), null, tint = tintFor(e), modifier = Modifier.size(28.dp))
			}
		}
		Spacer(Modifier.width(16.dp))
		Column(Modifier.weight(1f)) {
			Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
			Text(subtitle(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
}

@Composable
private fun GridItem(e: Entry, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
	val thumb: ImageBitmap? = rememberThumbnail(e)
	Column(
		Modifier
			.padding(4.dp)
			.clip(RoundedCornerShape(12.dp))
			.background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
			.combinedClickable(onClick = onClick, onLongClick = onLongClick)
			.padding(6.dp),
	) {
		Box(
			Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))
				.background(MaterialTheme.colorScheme.surfaceVariant),
			contentAlignment = Alignment.Center,
		) {
			if (thumb != null) {
				Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
				if (isVideo(e.name)) Icon(
					Icons.Default.PlayCircle, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(40.dp),
				)
			} else {
				Icon(iconFor(e), null, tint = tintFor(e), modifier = Modifier.size(56.dp))
			}
			if (selected) Box(Modifier.align(Alignment.TopStart).padding(6.dp)) { SelectedMark() }
		}
		Spacer(Modifier.height(6.dp))
		Text(
			e.name, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
			style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 2.dp),
		)
		Text(
			subtitle(e), maxLines = 1, overflow = TextOverflow.Ellipsis,
			style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(horizontal = 2.dp),
		)
	}
}

@Composable
private fun SelectedMark() {
	Icon(
		Icons.Default.Check, null,
		tint = MaterialTheme.colorScheme.onPrimary,
		modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(6.dp),
	)
}

@Composable
private fun tintFor(e: Entry) =
	if (e.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun PasteBar(clip: Clip, vm: BrowserViewModel) {
	Surface(tonalElevation = 3.dp) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
		) {
			Text(
				"${clip.items.size} para ${if (clip.cut) "mover" else "copiar"}",
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
	onServer: (Server) -> Unit,
	onEditServer: (Server?) -> Unit,
	onCopyServer: (Server) -> Unit,
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
				Hint("Pulsa + o la estrella para guardar la carpeta actual.\nMantén pulsado un archivo o carpeta para crear uno.")
			}
			itemsIndexed(vm.shortcuts, key = { _, s -> s.path }) { i, s ->
				var menu by remember { mutableStateOf(false) }
				val f = File(s.path)
				NavigationDrawerItem(
					icon = {
						Icon(
							when {
								vm.isStart(s) -> Icons.Default.Home
								f.isFile -> iconForName(f.name)
								else -> Icons.Default.Folder
							},
							null,
						)
					},
					label = { TwoLines(s.name, s.path) },
					badge = {
						Box {
							IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Opciones") }
							DropdownMenu(menu, { menu = false }) {
								DropdownMenuItem({ Text("Renombrar") }, { menu = false; onRenameShortcut(s) })
								if (!f.isFile) StartMenuItem(vm.isStart(s)) { menu = false; vm.toggleStart(s) }
								if (i > 0) DropdownMenuItem({ Text("Subir") }, { menu = false; vm.moveShortcut(s, -1) })
								if (i < vm.shortcuts.lastIndex) DropdownMenuItem({ Text("Bajar") }, { menu = false; vm.moveShortcut(s, 1) })
								DropdownMenuItem({ Text("Añadir a pantalla de inicio") }, { menu = false; pinToHome(ctx, s) })
								DropdownMenuItem({ Text("Quitar") }, { menu = false; vm.removeShortcut(s) })
							}
						}
					},
					selected = vm.dir.path == s.path && vm.dir is LocalLoc,
					onClick = { onShortcut(s) },
				)
			}
			item {
				HorizontalDivider(Modifier.padding(vertical = 8.dp))
				Row(verticalAlignment = Alignment.CenterVertically) {
					Box(Modifier.weight(1f)) { SectionTitle("Servidores SFTP") }
					IconButton({ onEditServer(null) }) { Icon(Icons.Default.Add, "Añadir servidor") }
				}
			}
			if (vm.servers.isEmpty()) item { Hint("Pulsa + para añadir un servidor SFTP.") }
			itemsIndexed(vm.servers, key = { _, s -> "sftp:${s.id}" }) { i, s ->
				var menu by remember { mutableStateOf(false) }
				NavigationDrawerItem(
					icon = { Icon(if (vm.isStart(s)) Icons.Default.Home else Icons.Default.Dns, null) },
					label = { TwoLines(s.name, "${s.user}@${s.address}:${s.path.ifBlank { "~" }}") },
					badge = {
						Box {
							IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Opciones") }
							DropdownMenu(menu, { menu = false }) {
								DropdownMenuItem({ Text("Editar") }, { menu = false; onEditServer(s) })
								DropdownMenuItem({ Text("Duplicar") }, { menu = false; onCopyServer(s) })
								StartMenuItem(vm.isStart(s)) { menu = false; vm.toggleStart(s) }
								if (i > 0) DropdownMenuItem({ Text("Subir") }, { menu = false; vm.moveServer(s, -1) })
								if (i < vm.servers.lastIndex) DropdownMenuItem({ Text("Bajar") }, { menu = false; vm.moveServer(s, 1) })
								DropdownMenuItem({ Text("Quitar") }, { menu = false; vm.removeServer(s) })
							}
						}
					},
					selected = (vm.dir as? RemoteLoc)?.server?.id == s.id,
					onClick = { onServer(s) },
				)
			}
		}
	}
}

@Composable
private fun StartMenuItem(isStart: Boolean, onClick: () -> Unit) {
	DropdownMenuItem(
		text = { Text(if (isStart) "No abrir al iniciar" else "Abrir al iniciar") },
		onClick = onClick,
		leadingIcon = { Icon(Icons.Default.Home, null) },
	)
}

@Composable
private fun TwoLines(title: String, subtitle: String) {
	Column {
		Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
		Text(
			subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

@Composable
private fun Hint(text: String) {
	Text(
		text,
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
	)
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
private fun DetailsDialog(entries: List<Entry>, onDismiss: () -> Unit) {
	val totals by produceState<Result<Pair<Long, Int>>?>(null, entries) {
		value = withContext(Dispatchers.IO) { runCatching { Fs.totals(entries) } }
	}
	val single = entries.singleOrNull()
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(single?.name ?: "${entries.size} elementos", maxLines = 2, overflow = TextOverflow.Ellipsis) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				val size = when (val t = totals) {
					null -> "Calculando…"
					else -> t.fold(
						{ (bytes, count) -> FileOps.size(bytes) + if (single?.isDir == false) "" else " · $count archivos" },
						{ "No disponible" },
					)
				}
				if (single != null) {
					val loc = single.loc
					Detail("Ruta", if (loc is RemoteLoc) "${loc.server.user}@${loc.server.address}:${loc.path}" else loc.path)
					Detail("Tipo", if (single.isDir) "Carpeta" else FileOps.mime(single.name))
					Detail("Modificado", FileOps.date(single.modified))
				}
				Detail("Tamaño", size)
				(single?.loc as? LocalLoc)?.file?.let { f ->
					Detail("Permisos", (if (f.canRead()) "Lectura" else "") + (if (f.canWrite()) " · Escritura" else ""))
				}
			}
		},
		confirmButton = { TextButton(onDismiss) { Text("Cerrar") } },
	)
}

@Composable
private fun ServerDialog(initial: Server?, isNew: Boolean, onDismiss: () -> Unit, onSave: (Server) -> Unit) {
	var name by remember { mutableStateOf(initial?.name.orEmpty()) }
	var host by remember { mutableStateOf(initial?.address.orEmpty()) }
	var user by remember { mutableStateOf(initial?.user.orEmpty()) }
	var password by remember { mutableStateOf(initial?.password.orEmpty()) }
	var path by remember { mutableStateOf(initial?.path.orEmpty()) }
	var showPassword by remember { mutableStateOf(false) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(if (isNew) "Nuevo servidor SFTP" else "Editar servidor") },
		text = {
			Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
				OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, placeholder = { Text(host.ifBlank { "Mi servidor" }) }, singleLine = true)
				OutlinedTextField(
					host, { host = it }, label = { Text("Host") }, supportingText = { Text("host o host:puerto") }, singleLine = true,
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
				)
				OutlinedTextField(user, { user = it }, label = { Text("Usuario") }, singleLine = true)
				OutlinedTextField(
					password, { password = it }, label = { Text("Contraseña") }, singleLine = true,
					visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
					trailingIcon = {
						IconButton({ showPassword = !showPassword }) {
							Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Mostrar contraseña")
						}
					},
				)
				OutlinedTextField(
					path, { path = it }, label = { Text("Ruta") }, placeholder = { Text("/var/www") },
					supportingText = { Text("Vacío: carpeta personal") }, singleLine = true,
				)
			}
		},
		confirmButton = {
			TextButton(
				onClick = {
					val raw = host.trim()
					// Admite "host:puerto"; con más de un ":" se asume una IPv6 sin puerto.
					val parts = raw.split(':')
					val (h, port) = parts.takeIf { it.size == 2 }?.get(1)?.toIntOrNull()?.let { parts[0] to it } ?: (raw to 22)
					val n = name.trim().ifEmpty { h }
					val p = path.trim()
					onSave(
						initial?.copy(name = n, host = h, port = port, user = user.trim(), password = password, path = p)
							?: Server(name = n, host = h, port = port, user = user.trim(), password = password, path = p)
					)
					onDismiss()
				},
				enabled = host.isNotBlank() && user.isNotBlank(),
			) { Text("Guardar") }
		},
		dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
	)
}

@Composable
private fun SendDialog(vm: BrowserViewModel, items: List<Loc>, onDismiss: () -> Unit) {
	var cut by remember { mutableStateOf(false) }
	val folders = remember(vm.shortcuts) { vm.shortcuts.filter { File(it.path).isDirectory } }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(if (items.size == 1) "Enviar «${items[0].name}» a…" else "Enviar ${items.size} elementos a…", maxLines = 2, overflow = TextOverflow.Ellipsis) },
		text = {
			Column {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					modifier = Modifier.fillMaxWidth().clip(CircleShape).clickable { cut = !cut },
				) {
					Checkbox(cut, { cut = it })
					Text("Mover (quitar del origen)")
				}
				HorizontalDivider(Modifier.padding(vertical = 8.dp))
				LazyColumn(Modifier.heightIn(max = 400.dp)) {
					if (folders.isNotEmpty()) item { DestHeader("Accesos directos") }
					items(folders) { s ->
						Dest(Icons.Default.Folder, s.name, s.path) { vm.sendTo(items, s, cut); onDismiss() }
					}
					if (vm.servers.isNotEmpty()) item { DestHeader("Servidores SFTP") }
					items(vm.servers) { s ->
						Dest(Icons.Default.Dns, s.name, "${s.user}@${s.address}:${s.path.ifBlank { "~" }}") { vm.sendTo(items, s, cut); onDismiss() }
					}
					item {
						Dest(Icons.Default.FolderOpen, "Otra carpeta…", "Navega hasta el destino y pulsa Pegar") {
							vm.copyToClip(items, cut); onDismiss()
						}
					}
				}
			}
		},
		confirmButton = {},
		dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
	)
}

@Composable
private fun DestHeader(text: String) {
	Text(
		text,
		style = MaterialTheme.typography.labelLarge,
		color = MaterialTheme.colorScheme.primary,
		modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
	)
}

@Composable
private fun Dest(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
	ListItem(
		headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
		supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
		leadingContent = { Icon(icon, null) },
		colors = ListItemDefaults.colors(containerColor = Color.Transparent),
		modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
	)
}

@Composable
private fun TransferDialog(t: TransferState, onCancel: () -> Unit) {
	AlertDialog(
		onDismissRequest = {},
		title = { Text(t.title) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				if (t.totalBytes < 0) {
					Text("Calculando tamaño…", color = MaterialTheme.colorScheme.onSurfaceVariant)
					LinearProgressIndicator(Modifier.fillMaxWidth())
					return@Column
				}
				// Con archivos vacíos no hay bytes que contar: se avanza por número de archivos.
				val fraction = when {
					t.totalBytes > 0 -> t.bytes.toFloat() / t.totalBytes
					t.totalFiles > 0 -> t.files.toFloat() / t.totalFiles
					else -> 0f
				}.coerceIn(0f, 1f)
				Text(t.current.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
				LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
				Row {
					Text(
						"Archivo ${minOf(t.files + 1, t.totalFiles)} de ${t.totalFiles}",
						style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
					)
					Text("${(fraction * 100).toInt()} %", style = MaterialTheme.typography.bodySmall)
				}
				val eta = if (t.speed > 0) " · quedan ${duration((t.totalBytes - t.bytes) / t.speed)}" else ""
				Text(
					"${FileOps.size(t.bytes)} de ${FileOps.size(t.totalBytes)}" +
						(if (t.speed > 0) " · ${FileOps.size(t.speed)}/s" else "") + eta,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		},
		confirmButton = {
			TextButton(onCancel, enabled = !t.cancelling) { Text(if (t.cancelling) "Cancelando…" else "Cancelar") }
		},
	)
}

private fun duration(seconds: Long): String = when {
	seconds < 60 -> "$seconds s"
	seconds < 3600 -> "${seconds / 60} min ${seconds % 60} s"
	else -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
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

private fun iconForName(name: String): ImageVector = if (isImage(name)) Icons.Default.Image else if (isVideo(name)) Icons.Default.Movie else when (name.substringAfterLast('.', "").lowercase()) {
	"svg" -> Icons.Default.Image
	"mp3", "wav", "ogg", "flac", "m4a", "aac", "opus" -> Icons.Default.AudioFile
	"pdf" -> Icons.Default.PictureAsPdf
	"zip", "rar", "7z", "tar", "gz", "xz" -> Icons.Default.FolderZip
	"apk" -> Icons.Default.Android
	"txt", "md", "doc", "docx", "odt", "rtf", "csv", "xls", "xlsx", "json", "xml", "html" -> Icons.Default.Description
	else -> Icons.AutoMirrored.Filled.InsertDriveFile
}
