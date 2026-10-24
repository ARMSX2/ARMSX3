package com.armsx2.ui.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsx2.i18n.str
import com.armsx2.ui.common.FileBrowserDialog
import com.armsx2.ui.common.canBrowse
import com.armsx3.Rpcs3Bridge
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The player's avatar, chosen from the RPCN tab.
 *
 * Upstream gave sceNp local avatars (np_avatar): a game that shows the player's picture gets the
 * one chosen here, out of the PSN avatar packages installed for the user (each one needs its RAP
 * licence to be decrypted), a picture of the player's own, or the default one from the firmware.
 * RPCS3 picks it in its User Manager. This is the same choice, for a screen that has none.
 *
 * The picker is an overlay drawn by SettingsScreen over everything, not a Compose Dialog: a dialog
 * is its own window and takes the controller's keys away from the activity, where this app's D-pad
 * navigation lives (see ConfirmOverlay).
 */
object RpcnAvatarPicker {
    /** Whether the picker is up. B closes it, through MainActivityRuntime.handleFrontendBack. */
    val open = mutableStateOf(false)
}

/** The size a game asks for at most (SCE_NP_AVATAR_SIZE_LARGE). sceNp scales down from it. */
private const val AvatarPixels = 240

/** Pictures bigger than this are not read: a phone photo is a few MB, and this has to fit in memory. */
private const val MaxPictureBytes = 32L * 1024 * 1024

private const val Layer = "rpcn-avatar"

private const val CellPrefix = "rpcn.avatar.cell."

private val CellSize = 76.dp

/** Decode size for the grid. PSN avatars are 240 pixels, which stay whole for a cell this size. */
private const val CellPixels = 160

/** What np_avatar reports: where the user's avatars are, which one is chosen, and the ones a game
 *  could be given. Avatars without their licence are only counted, as RPCS3's dialog hides them. */
private class AvatarList(
    val userDir: String,
    val selected: String,
    val default: String,
    val usable: List<String>,
    val hidden: Int,
) {
    /** The file games are given now. */
    val current: String get() = selected.ifBlank { default }
}

private fun readAvatarList(): AvatarList? {
    val json = Rpcs3Bridge.avatarList().ifBlank { return null }
    return runCatching {
        val o = JSONObject(json)
        val avatars = o.optJSONArray("avatars")
        val usable = ArrayList<String>()
        var hidden = 0
        for (i in 0 until (avatars?.length() ?: 0)) {
            val entry = avatars!!.optJSONObject(i) ?: continue
            if (entry.optBoolean("usable")) usable += entry.optString("path") else hidden++
        }
        AvatarList(o.optString("userDir"), o.optString("selected"), o.optString("default"), usable, hidden)
    }.getOrNull()
}

/** Avatars decoded for the screen. An EDAT has to be decrypted before its PNG can be decoded, a few
 *  milliseconds each, and a screenful of the grid asks for dozens at once. */
private object AvatarImages {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    // Four at a time keeps the screen responsive while the grid fills.
    private val gate = Semaphore(4)

    fun cached(path: String, pixels: Int): ImageBitmap? = cache.get("$pixels:$path")?.asImageBitmap()

    suspend fun load(path: String, pixels: Int): ImageBitmap? {
        cached(path, pixels)?.let { return it }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                val png = Rpcs3Bridge.avatarImage(path) ?: return@withContext null
                decode(png, pixels)?.also { cache.put("$pixels:$path", it) }?.asImageBitmap()
            }
        }
    }

    private fun decode(png: ByteArray, pixels: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= pixels && bounds.outHeight / (sample * 2) >= pixels) sample *= 2
        return BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** Any picture the phone can decode, cut to its centre square, turned the way the camera held it,
 *  and scaled to [AvatarPixels]. As a PNG, the one format the core reads. */
private fun squareAvatarPng(bytes: ByteArray): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val shortSide = minOf(bounds.outWidth, bounds.outHeight)
    if (shortSide <= 0) return null

    var sample = 1
    while (shortSide / (sample * 2) >= AvatarPixels) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null

    val degrees = runCatching {
        when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)

    val side = minOf(decoded.width, decoded.height)
    val scale = minOf(1f, AvatarPixels.toFloat() / side)
    val matrix = Matrix().apply {
        postScale(scale, scale)
        if (degrees != 0f) postRotate(degrees)
    }
    val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side, matrix, true)

    return ByteArrayOutputStream().use { out ->
        if (square.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
    }
}

private fun InputStream.readCapped(): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        total += n
        if (total > MaxPictureBytes) return null
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** "Default avatar", "PSN avatar from NPEB02228" or "Your own picture". */
@Composable
private fun describe(list: AvatarList?, defaultMissing: Boolean): String {
    list ?: return ""
    val psnDir = list.userDir + "psn_avatar/"
    return when {
        list.selected.isBlank() ->
            if (defaultMissing) str("rpcn.avatar.noFirmware") else str("rpcn.avatar.current.default")
        list.selected.startsWith(psnDir) ->
            str("rpcn.avatar.current.psn").format(list.selected.removePrefix(psnDir).substringBefore('/'))
        else -> str("rpcn.avatar.current.image")
    }
}

/** Whether the default avatar is missing, which it is until the firmware is installed. */
@Composable
private fun rememberDefaultMissing(list: AvatarList?): Boolean {
    val missing by produceState(false, list?.selected, list?.default) {
        value = list != null && list.selected.isBlank() && AvatarImages.load(list.default, AvatarPixels) == null
    }
    return missing
}

@Composable
private fun AvatarPicture(path: String?, modifier: Modifier, pixels: Int) {
    val image by produceState(path?.let { AvatarImages.cached(it, pixels) }, path, pixels) {
        value = path?.let { AvatarImages.load(it, pixels) }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        image?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** The Avatar panel in the RPCN tab: the picture games get now, and the way into the picker. */
@Composable
internal fun RpcnAvatarRow() {
    val open = { RpcnAvatarPicker.open.value = true }
    // Read again whenever the picker closes, so the row shows what was just chosen.
    val list by produceState<AvatarList?>(null, RpcnAvatarPicker.open.value) {
        if (!RpcnAvatarPicker.open.value) value = withContext(Dispatchers.IO) { readAvatarList() }
    }
    val defaultMissing = rememberDefaultMissing(list)

    Row(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(rowAura())
            .clickable(onClick = open)
            .controllerFocusable("rpcn.avatar", onConfirm = open)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarPicture(list?.current, Modifier.size(56.dp), AvatarPixels)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                str("rpcn.avatar.choose"),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                describe(list, defaultMissing),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The picker: every usable avatar in a grid, a picture of the player's own, or the default. */
@Composable
fun RpcnAvatarPickerOverlay() {
    // The pad stays on the picker while it is up, the way ConfirmOverlay holds it, and goes back to
    // the Avatar row when it closes. Closing on dispose too: leaving settings with the picker up
    // must not bring it back the next time settings opens.
    DisposableEffect(Layer) {
        val previous = SettingsControllerNav.activeLayer.value
        SettingsControllerNav.activeLayer.value = Layer
        SettingsControllerNav.clearSelection()
        onDispose {
            SettingsControllerNav.activeLayer.value = previous
            if (!SettingsControllerNav.selectById("rpcn.avatar")) SettingsControllerNav.clearSelection()
            RpcnAvatarPicker.open.value = false
        }
    }
    // B arrives through handleFrontendBack; this catches a back gesture that reaches Compose instead.
    BackHandler(enabled = true) { RpcnAvatarPicker.open.value = false }
    LaunchedEffect(Layer) {
        var attempts = 0
        while (!SettingsControllerNav.selectById("rpcn.avatar.picture") && attempts < 10) {
            attempts++
            withFrameNanos {}
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<AvatarList?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }

    suspend fun reload() {
        list = withContext(Dispatchers.IO) { readAvatarList() }
        loaded = true
    }
    LaunchedEffect(Unit) { reload() }

    // str() is @Composable, so the callbacks close over these instead.
    val failed = str("rpcn.avatar.failed")
    val unreadable = str("rpcn.avatar.imageFailed")

    fun choose(path: String) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Rpcs3Bridge.avatarSelect(path) }
            note = if (ok) null else failed
            reload()
            busy = false
        }
    }

    fun usePicture(read: () -> ByteArray?) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val png = runCatching { read()?.let(::squareAvatarPng) }.getOrNull()
                png != null && Rpcs3Bridge.avatarUseImage(png)
            }
            note = if (ok) null else unreadable
            reload()
            busy = false
        }
    }

    val systemPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) usePicture { context.contentResolver.openInputStream(uri)?.use { it.readCapped() } }
    }
    val pickPicture = { if (canBrowse()) browsing = true else systemPicker.launch("image/*") }
    val useDefault = { choose("") }
    val close = { RpcnAvatarPicker.open.value = false }

    val defaultMissing = rememberDefaultMissing(list)

    CompositionLocalProvider(LocalNavLayer provides Layer) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // Nothing behind the picker takes a tap while it is up.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarPicture(list?.current, Modifier.size(64.dp), AvatarPixels)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            str("rpcn.avatar.title"),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            describe(list, defaultMissing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PickerButton("rpcn.avatar.picture", str("rpcn.avatar.useImage"), enabled = !busy, onClick = pickPicture)
                    PickerButton("rpcn.avatar.default", str("rpcn.avatar.useDefault"), enabled = !busy, onClick = useDefault)
                    PickerButton("rpcn.avatar.close", str("action.close"), onClick = close)
                }

                val current = list
                val countText = str("rpcn.avatar.count")
                val hiddenText = str("rpcn.avatar.hidden")
                val status = when {
                    !loaded -> str("rpcn.avatar.loading")
                    current == null || (current.usable.isEmpty() && current.hidden == 0) -> str("rpcn.avatar.none")
                    else -> buildString {
                        append(countText.format(current.usable.size))
                        if (current.hidden > 0) append(' ').append(hiddenText.format(current.hidden))
                    }
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                note?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                val paths = current?.usable.orEmpty()
                if (paths.isNotEmpty()) {
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
                        val columns = maxOf(3, ((maxWidth + 8.dp) / (CellSize + 8.dp)).toInt())
                        val grid = rememberLazyGridState()

                        // The pad only moves between controls that are composed, and a lazy grid
                        // composes only what is on screen (see armsx3-dpad-nav-lazy-list-dead-end).
                        // Keeping the selected avatar's row second from the top keeps a row composed
                        // on both sides of it, so Up and Down always have somewhere to go.
                        val selectedId = SettingsControllerNav.currentSelectedId()
                        LaunchedEffect(selectedId, columns) {
                            val index = selectedId?.takeIf { it.startsWith(CellPrefix) }
                                ?.removePrefix(CellPrefix)?.toIntOrNull() ?: return@LaunchedEffect
                            grid.animateScrollToItem(maxOf(0, (index / columns - 1) * columns))
                        }

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            state = grid,
                            contentPadding = PaddingValues(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(count = paths.size, key = { paths[it] }) { index ->
                                val path = paths[index]
                                val pick = { choose(path) }
                                val chosen = path == current?.selected
                                Box(
                                    Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        // Only on the chosen one: a 0.dp border still draws a hairline
                                        .then(
                                            if (chosen) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                                            else Modifier
                                        )
                                        .clickable(enabled = !busy, onClick = pick)
                                        .controllerFocusable("$CellPrefix$index", RoundedCornerShape(12.dp), onConfirm = pick),
                                ) {
                                    AvatarPicture(path, Modifier.fillMaxSize(), CellPixels)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (browsing) {
        FileBrowserDialog(
            title = str("rpcn.avatar.useImage"),
            extensions = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif"),
            onPick = { file ->
                browsing = false
                usePicture { file.takeIf { it.length() <= MaxPictureBytes }?.readBytes() }
            },
            onDismiss = { browsing = false },
        )
    }
}

@Composable
private fun PickerButton(id: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.controllerFocusable(id, onConfirm = { if (enabled) onClick() }),
    ) { Text(label) }
}
