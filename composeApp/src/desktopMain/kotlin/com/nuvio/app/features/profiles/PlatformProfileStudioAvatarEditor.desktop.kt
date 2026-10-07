@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.nuvio.app.features.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draganddrop.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import java.awt.datatransfer.DataFlavor
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.intl.Locale

internal data class ProfileStudioAvatarChoice(val avatar: BundledProfileAvatar, val imageUri: String)
internal class ProfileStudioEditorActions(
    val chooseImage: suspend () -> AvatarRasterSource?,
    val pasteImage: suspend () -> AvatarRasterSource,
    val readDroppedFile: suspend (Path) -> AvatarRasterSource,
    val savePhoto: suspend (AvatarRasterSource, AvatarCrop) -> Unit,
    val saveBundled: suspend (BundledProfileAvatar) -> Unit,
    val reset: suspend () -> Unit,
)

@Composable
internal actual fun PlatformProfileStudioAvatarEditor(profile: NuvioProfile) {
    val account = com.nuvio.app.core.auth.AuthRepository.state.collectAsState().value
    if (ProfileStudioAvatars.scope(profile) == null) return
    val revision = ProfileStudioAvatars.revision.collectAsState().value
    val currentImage by produceState<String?>(null, profile, revision, account) {
        value = withContext(Dispatchers.IO) { ProfileStudioAvatars.imageUrl(profile) }
    }
    var choices by remember { mutableStateOf<List<ProfileStudioAvatarChoice>>(emptyList()) }
    var libraryFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            choices = withContext(Dispatchers.IO) { ProfileStudioAvatars.library.map { ProfileStudioAvatarChoice(it, ProfileStudioAvatars.bundledImageUrl(it)) } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { libraryFailed = true }
    }
    val actions = remember(profile) { ProfileStudioEditorActions(
        chooseImage = { AvatarImportActions.chooseFile()?.let { withContext(Dispatchers.IO) { AvatarRasterPipeline.readFile(it) } } },
        pasteImage = { AvatarImportActions.readClipboard() },
        readDroppedFile = { withContext(Dispatchers.IO) { AvatarRasterPipeline.readFile(it) } },
        savePhoto = { source, crop -> withContext(Dispatchers.IO) { ProfileStudioAvatars.savePhoto(profile, source, crop) } },
        saveBundled = { avatar -> withContext(Dispatchers.IO) { ProfileStudioAvatars.saveBundled(profile, avatar) } },
        reset = { withContext(Dispatchers.IO) { ProfileStudioAvatars.reset(profile) } },
    ) }
    DesktopProfileStudioAvatarEditor(currentImage, choices, libraryFailed, actions)
}

@Composable
internal fun DesktopProfileStudioAvatarEditor(currentImage: String?, choices: List<ProfileStudioAvatarChoice>,
    libraryFailed: Boolean, actions: ProfileStudioEditorActions, imageLoader: ImageLoader = SingletonImageLoader.get(LocalPlatformContext.current)) {
    val accent = Color(0xFFE6B76D)
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<AvatarRasterSource?>(null) }
    var crop by remember { mutableStateOf(AvatarCrop()) }
    var preview by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<AvatarImageFailure?>(null) }
    var saved by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf("all") }
    var dragActive by remember { mutableStateOf(false) }
    var previewPixels by remember { mutableIntStateOf(1) }
    val uriHandler = LocalUriHandler.current

    fun importImage(load: suspend () -> AvatarRasterSource?) {
        if (busy) return
        busy = true; failure = null; saved = false
        scope.launch {
            try { load()?.let { source = it; crop = AvatarCrop(); preview = null } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: AvatarImageException) { failure = error.reason }
            catch (_: Exception) { failure = AvatarImageFailure.INVALID }
            finally { busy = false }
        }
    }
    fun save(action: suspend () -> Unit) {
        if (busy) return
        busy = true; failure = null; saved = false
        scope.launch {
            try { action(); source = null; preview = null; saved = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure = AvatarImageFailure.INVALID }
            finally { busy = false }
        }
    }
    LaunchedEffect(source, crop) {
        val image = source ?: return@LaunchedEffect
        delay(60)
        preview = withContext(Dispatchers.IO) { AvatarRasterPipeline.preview(image, crop) }
    }
    val currentImport by rememberUpdatedState<(Path) -> Unit> { path -> importImage { actions.readDroppedFile(path) } }
    val target = remember { object : DragAndDropTarget {
        override fun onDrop(event: DragAndDropEvent): Boolean {
            return runCatching { currentImport(AvatarImportActions.singleFile(event.awtTransferable)); true }.getOrElse {
                failure = AvatarImageFailure.INVALID; false
            }
        }
        override fun onEntered(event: DragAndDropEvent) { dragActive = true }
        override fun onExited(event: DragAndDropEvent) { dragActive = false }
        override fun onEnded(event: DragAndDropEvent) { dragActive = false }
    } }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
    Surface(color = Color(0xFF121A26), shape = RoundedCornerShape(24.dp), modifier = Modifier.widthIn(max = 1440.dp).fillMaxWidth()
        .testTag("profile-studio").border(if (dragActive) 2.dp else 1.dp, if (dragActive) accent else Color(0xFF293445), RoundedCornerShape(24.dp))
        .dragAndDropTarget({ !busy && it.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) }, target)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(Res.string.profile_studio_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFFF5F0E8))
            Text(stringResource(Res.string.profile_studio_local_description), color = Color(0xFFA6B2C2), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { importImage(actions.chooseImage) }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF151A22))) {
                    Text(stringResource(Res.string.profile_studio_import))
                }
                OutlinedButton(onClick = { importImage(actions.pasteImage) }, enabled = !busy) { Text(stringResource(Res.string.profile_studio_paste), color = Color(0xFFF5F0E8)) }
                if (currentImage != null) OutlinedButton(onClick = { save(actions.reset) }, enabled = !busy) { Text(stringResource(Res.string.profile_studio_restore), color = Color(0xFFF5F0E8)) }
            }
            Text(stringResource(Res.string.profile_studio_drop_hint), color = Color(0xFFA6B2C2), style = MaterialTheme.typography.bodySmall)
            val image = source
            if (image != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(176.dp).clip(CircleShape).background(Color(0xFF080D15)).testTag("avatar-crop-preview")
                        .onSizeChanged { previewPixels = it.width.coerceAtLeast(1) }
                        .pointerInput(image, crop.zoom, busy) {
                            if (!busy) detectDragGestures { change, amount ->
                                change.consume()
                                val scale = previewPixels.toFloat() / minOf(image.width, image.height) * crop.zoom
                                crop = crop.copy(centerX = (crop.centerX - amount.x / (image.width * scale)).coerceIn(0f, 1f),
                                    centerY = (crop.centerY - amount.y / (image.height * scale)).coerceIn(0f, 1f))
                            }
                        }, contentAlignment = Alignment.Center) {
                        if (preview == null) CircularProgressIndicator(color = accent)
                        else AsyncImage(preview, stringResource(Res.string.profile_studio_crop_preview), modifier = Modifier.fillMaxSize(), imageLoader = imageLoader, contentScale = ContentScale.Crop)
                    }
                    Column(Modifier.widthIn(min = 260.dp, max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(Res.string.profile_studio_image_dimensions, image.width, image.height), color = Color(0xFFF5F0E8))
                        Text(stringResource(Res.string.profile_studio_zoom), color = Color(0xFFA6B2C2))
                        Slider(crop.zoom, { crop = crop.copy(zoom = it) }, enabled = !busy, valueRange = 1f..8f, modifier = Modifier.testTag("avatar-zoom"), colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Color(0xFF34404F)))
                        Text(stringResource(Res.string.profile_studio_horizontal), color = Color(0xFFA6B2C2))
                        Slider(crop.centerX, { crop = crop.copy(centerX = it) }, enabled = !busy, modifier = Modifier.testTag("avatar-center-x"), colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Color(0xFF34404F)))
                        Text(stringResource(Res.string.profile_studio_vertical), color = Color(0xFFA6B2C2))
                        Slider(crop.centerY, { crop = crop.copy(centerY = it) }, enabled = !busy, modifier = Modifier.testTag("avatar-center-y"), colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Color(0xFF34404F)))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { save { actions.savePhoto(image, crop) } }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF151A22))) { Text(stringResource(Res.string.profile_studio_save)) }
                            TextButton(onClick = { source = null; preview = null; failure = null }, enabled = !busy) { Text(stringResource(Res.string.profile_studio_cancel)) }
                        }
                    }
                }
            } else if (currentImage != null) {
                AsyncImage(currentImage, stringResource(Res.string.profile_studio_current_avatar), modifier = Modifier.size(96.dp).clip(CircleShape), imageLoader = imageLoader, contentScale = ContentScale.Crop)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = accent)
            if (failure != null) Text(stringResource(when (failure) {
                AvatarImageFailure.TOO_LARGE -> Res.string.profile_studio_error_size
                AvatarImageFailure.UNSUPPORTED -> Res.string.profile_studio_error_format
                AvatarImageFailure.EMPTY -> Res.string.profile_studio_error_empty
                else -> Res.string.profile_studio_error_generic
            }), color = Color(0xFFFFABA3), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (saved) Text(stringResource(Res.string.profile_studio_saved), color = Color(0xFF9CD4C0), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text(stringResource(Res.string.profile_studio_library), style = MaterialTheme.typography.titleMedium, color = Color(0xFFF5F0E8))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to Res.string.profile_studio_category_all, "animals" to Res.string.profile_studio_category_animals,
                    "nature" to Res.string.profile_studio_category_nature, "fantasy" to Res.string.profile_studio_category_fantasy,
                    "space" to Res.string.profile_studio_category_space).forEach { (id, label) ->
                    FilterChip(category == id, { category = id }, { Text(stringResource(label)) }, enabled = !busy,
                        colors = FilterChipDefaults.filterChipColors(labelColor = Color(0xFFF5F0E8),
                            selectedContainerColor = accent, selectedLabelColor = Color(0xFF151A22)))
                }
            }
            if (choices.isEmpty()) {
                if (libraryFailed) Text(stringResource(Res.string.profile_studio_library_error), color = Color(0xFFFFABA3))
                else CircularProgressIndicator(color = accent)
            } else {
                val portuguese = Locale.current.language == "pt"
                LazyVerticalGrid(GridCells.Adaptive(90.dp), modifier = Modifier.fillMaxWidth().height(240.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(choices.filter { category == "all" || it.avatar.category == category }, key = { it.avatar.id }) { choice ->
                        val name = if (portuguese) choice.avatar.namePtBr else choice.avatar.nameEn
                        OutlinedButton(onClick = { save { actions.saveBundled(choice.avatar) } }, enabled = !busy, shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(10.dp), modifier = Modifier.testTag("avatar-choice-${choice.avatar.id}")) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                AsyncImage(choice.imageUri, name, modifier = Modifier.size(52.dp).background(Color(0xFFF5F0E8), RoundedCornerShape(10.dp)), imageLoader = imageLoader, contentScale = ContentScale.Fit)
                                Text(name, maxLines = 2, style = MaterialTheme.typography.labelSmall, color = Color(0xFFF5F0E8))
                            }
                        }
                    }
                }
            }
            TextButton(onClick = { uriHandler.openUri("https://openmoji.org/") }) {
                Text("OpenMoji · CC BY-SA 4.0", color = Color(0xFFA6B2C2), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    }
}
