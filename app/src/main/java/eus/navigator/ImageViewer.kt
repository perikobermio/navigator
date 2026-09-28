package eus.navigator

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** Visor a pantalla completa de las imágenes de la carpeta, empezando por [start]. */
@Composable
fun ImageViewer(vm: BrowserViewModel, images: List<Entry>, start: Int, onClose: () -> Unit) {
	val ctx = LocalContext.current
	val pager = rememberPagerState(initialPage = start) { images.size }
	var chrome by remember { mutableStateOf(true) }
	// Se decodifica al tamaño de la pantalla: de sobra para verla y sin gastar memoria con fotos enormes.
	val config = LocalConfiguration.current
	val maxDim = with(LocalDensity.current) { max(config.screenWidthDp, config.screenHeightDp).dp.roundToPx() }

	Dialog(
		onDismissRequest = onClose,
		properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
	) {
		BackHandler(onBack = onClose)
		Box(Modifier.fillMaxSize().background(Color.Black)) {
			HorizontalPager(pager, key = { images[it].loc.path }, modifier = Modifier.fillMaxSize()) { page ->
				ZoomableImage(images[page], maxDim, onTap = { chrome = !chrome })
			}
			AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut()) {
				val current = images[pager.currentPage]
				Row(
					verticalAlignment = Alignment.CenterVertically,
					modifier = Modifier
						.fillMaxWidth()
						.background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
						.statusBarsPadding(),
				) {
					IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cerrar", tint = Color.White) }
					Column(Modifier.weight(1f)) {
						Text(current.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
						Text(
							"${pager.currentPage + 1} / ${images.size}",
							color = Color.White.copy(alpha = 0.7f),
							style = MaterialTheme.typography.bodySmall,
						)
					}
					IconButton({ vm.withLocalFiles(listOf(current)) { FileOps.share(ctx, it) } }) {
						Icon(Icons.Default.Share, "Compartir", tint = Color.White)
					}
					IconButton({ vm.withLocalFiles(listOf(current)) { FileOps.openWith(ctx, it[0]) } }) {
						Icon(Icons.AutoMirrored.Filled.OpenInNew, "Abrir con…", tint = Color.White)
					}
				}
			}
		}
	}
}

@Composable
private fun ZoomableImage(e: Entry, maxDim: Int, onTap: () -> Unit) {
	val ctx = LocalContext.current
	val image by produceState<Result<ImageBitmap>?>(null, e) {
		value = withContext(Dispatchers.IO) {
			runCatching {
				val file = Fs.localCopy(ctx, e.loc, Progress(), e.size, e.modified)
				decode(file, maxDim)?.asImageBitmap() ?: error("No se pudo abrir la imagen")
			}
		}
	}
	var scale by remember { mutableFloatStateOf(1f) }
	var offset by remember { mutableStateOf(Offset.Zero) }
	var size by remember { mutableStateOf(IntSize.Zero) }

	fun clamp(o: Offset): Offset {
		val maxX = size.width * (scale - 1) / 2
		val maxY = size.height * (scale - 1) / 2
		return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
	}

	Box(
		contentAlignment = Alignment.Center,
		modifier = Modifier
			.fillMaxSize()
			.onSizeChanged { size = it }
			.pointerInput(Unit) {
				detectTapGestures(
					onTap = { onTap() },
					onDoubleTap = { tap ->
						if (scale > 1f) {
							scale = 1f
							offset = Offset.Zero
						} else {
							// Acerca centrando el punto tocado.
							scale = 2.5f
							offset = clamp((Offset(size.width / 2f, size.height / 2f) - tap) * (scale - 1))
						}
					},
				)
			}
			.pointerInput(Unit) {
				// Pellizco para zoom y arrastre solo con zoom: sin zoom, el deslizamiento es para el pager.
				awaitEachGesture {
					awaitFirstDown(requireUnconsumed = false)
					do {
						val event = awaitPointerEvent()
						if (event.changes.size > 1 || scale > 1f) {
							scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
							offset = if (scale == 1f) Offset.Zero else clamp(offset + event.calculatePan())
							event.changes.forEach { if (it.positionChanged()) it.consume() }
						}
					} while (event.changes.any { it.pressed })
				}
			},
	) {
		when (val r = image) {
			null -> CircularProgressIndicator(color = Color.White)
			else -> r.fold(
				{
					Image(
						it, e.name,
						modifier = Modifier.fillMaxSize().graphicsLayer {
							scaleX = scale
							scaleY = scale
							translationX = offset.x
							translationY = offset.y
						},
					)
				},
				{ Icon(Icons.Default.BrokenImage, "No se pudo abrir", tint = Color.White.copy(alpha = 0.6f)) },
			)
		}
	}
}

/** Decodifica [file] con su lado mayor limitado a [maxDim] y respetando la orientación EXIF. */
private fun decode(file: File, maxDim: Int): Bitmap? {
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
		return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
			val s = max(info.size.width, info.size.height).toFloat() / maxDim
			if (s > 1) decoder.setTargetSize((info.size.width / s).toInt(), (info.size.height / s).toInt())
		}
	}
	val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
	BitmapFactory.decodeFile(file.path, bounds)
	var sample = 1
	while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
	val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
	val degrees = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) {
		ExifInterface.ORIENTATION_ROTATE_90 -> 90f
		ExifInterface.ORIENTATION_ROTATE_180 -> 180f
		ExifInterface.ORIENTATION_ROTATE_270 -> 270f
		else -> return bitmap
	}
	return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
}
