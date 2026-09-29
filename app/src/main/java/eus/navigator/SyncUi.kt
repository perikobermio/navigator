@file:OptIn(ExperimentalLayoutApi::class)

package eus.navigator

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val INTERVALS = listOf(15 to "15 min", 60 to "1 h", 360 to "6 h", 1440 to "24 h")

/** Editor de la sincronización de un acceso directo o servidor. */
@Composable
fun SyncDialog(vm: BrowserViewModel, draft: SyncRule) {
	val exists = vm.ruleFor(draft.target) != null
	AlertDialog(
		onDismissRequest = vm::cancelDraft,
		title = { Text("Sincronizar «${vm.targetName(draft.target)}»", maxLines = 2, overflow = TextOverflow.Ellipsis) },
		text = {
			Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(
					"Copia aquí lo nuevo o modificado de estas carpetas. Nunca borra nada del destino.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				draft.sources.forEachIndexed { i, source ->
					SourceCard(
						source, vm.servers,
						onChange = { vm.updateDraft(draft.copy(sources = draft.sources.toMutableList().also { l -> l[i] = it })) },
						onRemove = { vm.updateDraft(draft.copy(sources = draft.sources.filterIndexed { j, _ -> j != i })) },
					)
				}
				OutlinedButton(vm::startPickingSource, Modifier.fillMaxWidth()) {
					Icon(Icons.Default.Add, null)
					Spacer(Modifier.width(8.dp))
					Text("Añadir carpeta de origen")
				}
				HorizontalDivider(Modifier.padding(vertical = 4.dp))
				Choice("Manual", "Con un botón; antes revisas qué se va a copiar", !draft.auto) {
					vm.updateDraft(draft.copy(auto = false))
				}
				Choice("Automática", "En segundo plano, aunque la app esté cerrada", draft.auto) {
					vm.updateDraft(draft.copy(auto = true))
				}
				if (draft.auto) {
					FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 48.dp)) {
						for ((min, label) in INTERVALS) {
							FilterChip(draft.intervalMin == min, { vm.updateDraft(draft.copy(intervalMin = min)) }, { Text("Cada $label") })
						}
					}
				}
				lastRun(draft)?.let {
					Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
				if (exists) {
					TextButton({ vm.removeSync(draft.target) }) {
						Text("Quitar sincronización", color = MaterialTheme.colorScheme.error)
					}
				}
			}
		},
		confirmButton = { TextButton(vm::saveDraft, enabled = draft.sources.isNotEmpty()) { Text("Guardar") } },
		dismissButton = { TextButton(vm::cancelDraft) { Text("Cancelar") } },
	)
}

fun lastRun(rule: SyncRule): String? {
	if (rule.lastRun == 0L) return null
	val result = rule.lastError?.let { "error: $it" } ?: "${rule.lastFiles} archivos"
	return "Última sincronización: ${FileOps.date(rule.lastRun)} · $result"
}

@Composable
private fun SourceCard(source: SyncSource, servers: List<Server>, onChange: (SyncSource) -> Unit, onRemove: () -> Unit) {
	Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(12.dp)) {
		Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Icon(if (source.place.serverId == null) Icons.Default.Folder else Icons.Default.Dns, null, Modifier.size(20.dp))
				Spacer(Modifier.width(8.dp))
				Text(
					source.place.label(servers), maxLines = 2, overflow = TextOverflow.Ellipsis,
					style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
				)
				IconButton(onRemove) { Icon(Icons.Default.Close, "Quitar origen") }
			}
			FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				for (kind in Kind.entries) {
					val on = kind in source.kinds
					FilterChip(on, { onChange(source.copy(kinds = if (on) source.kinds - kind else source.kinds + kind)) }, { Text(kind.label) })
				}
			}
			OutlinedTextField(
				source.exts, { onChange(source.copy(exts = it)) },
				label = { Text("Otras extensiones") }, placeholder = { Text("pdf, epub") }, singleLine = true,
				supportingText = { Text(if (source.kinds.isEmpty() && source.exts.isBlank()) "Sin filtros: se trae todo" else source.summary) },
				modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
			)
			Row(
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onChange(source.copy(recursive = !source.recursive)) },
			) {
				Checkbox(source.recursive, { onChange(source.copy(recursive = it)) })
				Text("Incluir subcarpetas", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
			}
		}
	}
}

@Composable
private fun Choice(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
	) {
		RadioButton(selected, onClick)
		TwoLines(title, subtitle)
	}
}

/** Lista de lo que se va a copiar para validarlo antes de sincronizar. */
@Composable
fun SyncPreviewDialog(vm: BrowserViewModel, rule: SyncRule, items: List<SyncItem>) {
	var chosen by remember(items) { mutableStateOf(items.toSet()) }
	val bytes = chosen.sumOf { it.size }
	AlertDialog(
		onDismissRequest = vm::cancelPreview,
		title = { Text("Sincronizar «${vm.targetName(rule.target)}»", maxLines = 2, overflow = TextOverflow.Ellipsis) },
		text = {
			Column {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						"${chosen.size} de ${items.size} · ${FileOps.size(bytes)}",
						style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
					)
					TextButton({ chosen = if (chosen.size == items.size) emptySet() else items.toSet() }) {
						Text(if (chosen.size == items.size) "Ninguno" else "Todos")
					}
				}
				HorizontalDivider()
				LazyColumn(Modifier.heightIn(max = 420.dp)) {
					items(items, key = { it.dest.path }) { item ->
						val on = item in chosen
						PreviewRow(item, on) { chosen = if (on) chosen - item else chosen + item }
					}
				}
			}
		},
		confirmButton = {
			TextButton({ vm.confirmSync(items.filter { it in chosen }) }, enabled = chosen.isNotEmpty()) {
				Text("Sincronizar (${chosen.size})")
			}
		},
		dismissButton = { TextButton(vm::cancelPreview) { Text("Cancelar") } },
	)
}

@Composable
private fun PreviewRow(item: SyncItem, checked: Boolean, onToggle: () -> Unit) {
	val entry = remember(item) { Entry(item.src, item.src.name, false, item.size, item.modified, 0) }
	val thumb = rememberThumbnail(entry)
	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle).padding(vertical = 4.dp),
	) {
		Checkbox(checked, { onToggle() })
		Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
			if (thumb != null) {
				Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)))
			} else {
				Icon(iconForName(item.src.name), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
			}
		}
		Spacer(Modifier.width(12.dp))
		Box(Modifier.weight(1f)) {
			TwoLines(item.rel, (if (item.isNew) "Nuevo" else "Modificado") + " · " + FileOps.size(item.size))
		}
	}
}

/** Barra inferior mientras se elige la carpeta de origen de una sincronización. */
@Composable
fun PickSourceBar(vm: BrowserViewModel) {
	Surface(tonalElevation = 3.dp) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
		) {
			Text("Carpeta de origen", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
			TextButton(vm::cancelPicking) { Text("Cancelar") }
			Spacer(Modifier.width(8.dp))
			Button(vm::pickSource) { Text("Usar esta carpeta") }
		}
	}
}
