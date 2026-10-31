package com.armsx2.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsx2.data.library.Licences
import com.armsx2.i18n.str
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.common.FileBrowserDialog
import com.armsx2.ui.common.canBrowse
import com.armsx2.ui.packages.OpenedDocument
import com.armsx2.ui.packages.isLicenceName
import com.armsx2.ui.packages.openForInstall
import com.armsx2.ui.packages.stagedName
import com.armsx3.Rpcs3Bridge
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.armsx3.core.ProgressRepository
import com.armsx3.core.ARMSX3
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
 *  could be given: the firmware's own user icons, then the PSN avatars installed for the user. PSN
 *  avatars without their licence are only counted, as RPCS3's dialog hides them. */
private class AvatarList(
    val userDir: String,
    val selected: String,
    val default: String,
    val builtin: List<String>,
    val usable: List<String>,
    val hidden: Int,
) {
    /** The file games are given now. */
    val current: String get() = selected.ifBlank { default }

    /** Everything the grid offers, the firmware's icons first. */
    val choices: List<String> get() = builtin + usable
}

/** The avatars marked as favourites, by file name so they survive a move of the data folder: a PSN
 *  avatar is named by its hash, and the firmware's icons and the player's own pictures are named
 *  apart from those. With a few thousand avatars installed, this is how one gets found again. */
private object AvatarFavorites {
    private const val Key = "rpcn.avatar.favorites"

    val names = mutableStateOf<Set<String>>(emptySet())
    private var loaded = false

    private fun prefs(context: Context) = context.getSharedPreferences("ARMSX2", Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        names.value = prefs(context).getStringSet(Key, null)?.toSet().orEmpty()
    }

    fun contains(path: String): Boolean = path.substringAfterLast('/') in names.value

    /** True when the avatar is a favourite now. */
    fun toggle(context: Context, path: String): Boolean {
        val name = path.substringAfterLast('/')
        val now = if (name in names.value) names.value - name else names.value + name
        names.value = now
        prefs(context).edit().putStringSet(Key, now).apply()
        return name in now
    }
}

private fun readAvatarList(): AvatarList? {
    val json = Rpcs3Bridge.avatarList().ifBlank { return null }
    return runCatching {
        val o = JSONObject(json)
        val icons = o.optJSONArray("builtin")
        val builtin = (0 until (icons?.length() ?: 0)).mapNotNull { icons!!.optString(it).ifBlank { null } }
        val avatars = o.optJSONArray("avatars")
        val usable = ArrayList<String>()
        var hidden = 0
        for (i in 0 until (avatars?.length() ?: 0)) {
            val entry = avatars!!.optJSONObject(i) ?: continue
            if (entry.optBoolean("usable")) usable += entry.optString("path") else hidden++
        }
        AvatarList(o.optString("userDir"), o.optString("selected"), o.optString("default"), builtin, usable, hidden)
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

/** A line under the buttons: what just happened, or what went wrong. */
private class Notice(val text: String, val error: Boolean)

/** A package or licence the player picked to install, from the in-app browser or the system picker. */
private class PickedFile(
    val name: String,
    /** Opened for the native installer, which reads a descriptor. */
    val open: () -> OpenedDocument,
    /** The whole file, for a licence: a .rap is 16 bytes. */
    val read: () -> ByteArray?,
)

private fun pickedFile(file: File) = PickedFile(
    file.name,
    { OpenedDocument(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), null) },
    { file.takeIf { it.length() <= MaxPictureBytes }?.readBytes() },
)

private fun pickedFile(context: Context, uri: Uri): PickedFile {
    val resolver = context.contentResolver
    val name = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()
    return PickedFile(name, { openForInstall(context, uri, name) }, { resolver.openInputStream(uri)?.use { it.readCapped() } })
}

/** Installs one avatar package or licence the way the PKG/Data Manager does. True when it worked. */
private fun installOne(context: Context, file: PickedFile): Boolean {
    if (!isLicenceName(file.name)) {
        val id = ProgressRepository.create(context, "Installing ${file.name}")
        val opened = try {
            file.open()
        } catch (failure: Exception) {
            // Nothing else finishes the entry when the native call never happens, and its
            // notification is an ongoing one that would otherwise stay in the shade
            ProgressRepository.onProgressEvent(id, -1, 0, failure.message.orEmpty())
            return false
        }
        return try {
            ARMSX3.instance.install(opened.descriptor.fd, id)
        } finally {
            opened.close()
        }
    }

    val bytes = file.read() ?: return false
    val staged = File(context.cacheDir, stagedName(file.name))
    return try {
        staged.writeBytes(bytes)
        if (file.name.endsWith(".rap", ignoreCase = true)) {
            Licences.installRap(staged)
        } else {
            // An EDAT carries its own content id, so installKey needs no game path
            val id = ProgressRepository.create(context, "Installing ${file.name}", silent = true)
            val descriptor = ParcelFileDescriptor.open(staged, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                ARMSX3.instance.installKey(descriptor.fd, id, "")
            } finally {
                runCatching { descriptor.close() }
            }
        }
    } finally {
        runCatching { staged.delete() }
    }
}

/** "Default avatar", "PS3 user icon", "PSN avatar from NPEB02228" or "Your own picture". */
@Composable
private fun describe(list: AvatarList?, defaultMissing: Boolean): String {
    list ?: return ""
    val psnDir = list.userDir + "psn_avatar/"
    return when {
        list.selected.isBlank() ->
            if (defaultMissing) str("rpcn.avatar.noFirmware") else str("rpcn.avatar.current.default")
        list.selected in list.builtin -> str("rpcn.avatar.current.builtin")
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

/**
 * The firmware's own picture for this user, the one games get until another is chosen: the RPCN row's
 * icon in the library menu. Null until read, and while the firmware is not installed. Kept once
 * loaded, as the file depends only on the user and finding it means listing every avatar.
 */
@Composable
internal fun rememberDefaultAvatar(): ImageBitmap? {
    val image by produceState(DefaultAvatar.image) {
        if (value == null) {
            val path = withContext(Dispatchers.IO) { readAvatarList()?.default }.orEmpty()
            if (path.isNotBlank()) value = AvatarImages.load(path, AvatarPixels)?.also { DefaultAvatar.image = it }
        }
    }
    return image
}

private object DefaultAvatar {
    var image: ImageBitmap? = null
}

/** The picture games are given for the player now: the chosen avatar, or the firmware's default.
 *  Read again whenever the picker closes. For the Friends headers ([com.armsx2.ui.friends.RpcnSelfChip]). */
@Composable
internal fun RpcnAvatarImage(modifier: Modifier) {
    val path by produceState<String?>(null, RpcnAvatarPicker.open.value) {
        if (!RpcnAvatarPicker.open.value) value = withContext(Dispatchers.IO) { Rpcs3Bridge.avatarCurrent().ifBlank { null } }
    }
    AvatarPicture(path, modifier, AvatarPixels)
}

/** The picker: every usable avatar in a grid, a picture of the player's own, or the default. */
@Composable
fun RpcnAvatarPickerOverlay() {
    // The pad stays on the picker while it is up, the way ConfirmOverlay holds it, and goes back to
    // the picture on the profile card that opened it when it closes. A miss there left nothing
    // selected, and Up from nothing lands on the last control: the page flung to its bottom.
    // Closing on dispose too: leaving settings with the picker up must not bring it back the next
    // time settings opens.
    DisposableEffect(Layer) {
        val previous = SettingsControllerNav.activeLayer.value
        SettingsControllerNav.activeLayer.value = Layer
        SettingsControllerNav.clearSelection()
        onDispose {
            SettingsControllerNav.activeLayer.value = previous
            if (!SettingsControllerNav.selectById("rpcn.profile.avatar")) SettingsControllerNav.clearSelection()
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
    // Its own music while it is up, blended in over the menu's and back out again (AvatarMusic)
    DisposableEffect(Unit) {
        com.armsx2.AvatarMusic.enter(context)
        onDispose { com.armsx2.AvatarMusic.leave() }
    }
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<AvatarList?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var busy by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var browsingPackages by remember { mutableStateOf(false) }
    var favoritesOnly by remember { mutableStateOf(false) }
    // What Favorites shows: the favourites as the view was opened, so taking one off greys its star
    // instead of pulling the cell out from under the pad
    var favoritesShown by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) { AvatarFavorites.load(context) }
    val favorites by AvatarFavorites.names
    val grid = rememberLazyGridState()
    // Bumped by L1/R1, to land the pad on the first avatar of the view they switched to
    var shoulderSwitches by remember { mutableIntStateOf(0) }

    fun showFavorites(on: Boolean) {
        if (on) favoritesShown = AvatarFavorites.names.value
        favoritesOnly = on
        scope.launch { grid.scrollToItem(0) }
    }

    // L1/R1 flip All and Favorites while the picker is up, not the settings tabs behind it
    DisposableEffect(Unit) {
        com.armsx2.ui.settingshub.SettingsCategoryNav.overlayCycle = {
            showFavorites(!favoritesOnly)
            shoulderSwitches++
            com.armsx2.MenuSfx.play(com.armsx2.MenuSfx.Event.NAV)
        }
        onDispose { com.armsx2.ui.settingshub.SettingsCategoryNav.overlayCycle = null }
    }
    LaunchedEffect(shoulderSwitches) {
        if (shoulderSwitches == 0) return@LaunchedEffect
        var attempts = 0
        while (!SettingsControllerNav.selectById("${CellPrefix}0") && attempts < 10) {
            attempts++
            withFrameNanos {}
        }
        // An empty Favorites has no avatar to land on: its button says where the pad is
        if (attempts == 10) {
            SettingsControllerNav.selectById(if (favoritesOnly) "rpcn.avatar.filter.favorites" else "rpcn.avatar.filter.all")
        }
    }

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
            notice = if (ok) null else Notice(failed, error = true)
            reload()
            busy = false
        }
    }

    val favoriteAdded = str("rpcn.avatar.favoriteAdded")
    val favoriteRemoved = str("rpcn.avatar.favoriteRemoved")

    fun toggleFavorite(path: String) {
        val on = AvatarFavorites.toggle(context, path)
        com.armsx2.MenuSfx.play(if (on) com.armsx2.MenuSfx.Event.TOGGLE_ON else com.armsx2.MenuSfx.Event.TOGGLE_OFF)
        notice = Notice(if (on) favoriteAdded else favoriteRemoved, error = false)
    }

    fun usePicture(read: () -> ByteArray?) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val png = runCatching { read()?.let(::squareAvatarPng) }.getOrNull()
                png != null && Rpcs3Bridge.avatarUseImage(png)
            }
            notice = if (ok) null else Notice(unreadable, error = true)
            reload()
            busy = false
        }
    }

    // Avatar packages and their licences, installed right here rather than through the PKG/Data
    // Manager. One package at a time: that screen takes several packages as the parts of one split
    // package, and avatar packs are whole packages each. Packages before licences, since a licence
    // unlocks what its package wrote. On the app's scope, so closing the picker does not abandon an
    // install halfway.
    val installingText = str("rpcn.avatar.installing")
    val installedText = str("rpcn.avatar.installed")
    val installFailedText = str("rpcn.avatar.installFailed")

    fun installAvatars(picked: List<PickedFile>) {
        if (picked.isEmpty() || busy) return
        busy = true
        notice = null
        MainActivityRuntime.invoke {
            val failures = mutableListOf<String>()
            for (file in picked.sortedBy { isLicenceName(it.name) }) {
                notice = Notice(installingText.format(file.name), error = false)
                val ok = withContext(Dispatchers.IO) { runCatching { installOne(context, file) }.getOrDefault(false) }
                if (!ok) failures += file.name
            }
            notice = if (failures.isEmpty()) Notice(installedText, error = false)
            else Notice(installFailedText.format(failures.joinToString()), error = true)
            reload()
            busy = false
        }
    }

    val systemPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) usePicture { context.contentResolver.openInputStream(uri)?.use { it.readCapped() } }
    }
    val pickPicture = { if (canBrowse()) browsing = true else systemPicker.launch("image/*") }
    val packagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) installAvatars(uris.map { pickedFile(context, it) })
    }
    // Packages have no type of their own to filter on, so the system picker shows everything
    val pickPackages = { if (canBrowse()) browsingPackages = true else packagePicker.launch(arrayOf("*/*")) }
    // The installer writes into the user's folders, which a running game has mounted
    val canInstall = MainActivityRuntime.isVmIdle()
    val useDefault = { choose("") }
    val close = { RpcnAvatarPicker.open.value = false }

    val defaultMissing = rememberDefaultMissing(list)

    CompositionLocalProvider(LocalNavLayer provides Layer) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                        0.3f to Color.Transparent,
                    ),
                )
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
                    RpcnAvatarRing(84.dp) {
                        AvatarPicture(list?.current, Modifier.size(66.dp).clip(CircleShape), AvatarPixels)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        // Drawn over the page's own background rather than a Surface, so nothing
                        // supplies a content colour: without one it was black on the dark page
                        Text(
                            str("rpcn.avatar.title"),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            describe(list, defaultMissing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PickerButton("rpcn.avatar.picture", str("rpcn.avatar.useImage"), enabled = !busy, onClick = pickPicture)
                    PickerButton("rpcn.avatar.install", str("rpcn.avatar.install"), enabled = !busy && canInstall, onClick = pickPackages)
                    PickerButton("rpcn.avatar.default", str("rpcn.avatar.useDefault"), enabled = !busy, onClick = useDefault)
                    PickerButton("rpcn.avatar.close", str("action.close"), onClick = close)
                }

                val current = list
                val countText = str("rpcn.avatar.count")
                val hiddenText = str("rpcn.avatar.hidden")
                val psnHint = str("rpcn.avatar.psnHint")
                val status = when {
                    !loaded -> str("rpcn.avatar.loading")
                    current == null || (current.choices.isEmpty() && current.hidden == 0) -> str("rpcn.avatar.none")
                    else -> buildString {
                        append(countText.format(current.choices.size))
                        if (current.hidden > 0) append(' ').append(hiddenText.format(current.hidden))
                        // Most players have no PSN avatars, and nothing else says where they come from
                        if (current.usable.isEmpty()) append(' ').append(psnHint)
                    }
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                notice?.let {
                    Text(
                        it.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                val all = current?.choices.orEmpty()
                if (all.isNotEmpty()) {
                    val favoriteCount = all.count { it.substringAfterLast('/') in favorites }
                    Row(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterButton("rpcn.avatar.filter.all", str("rpcn.avatar.all").format(all.size), selected = !favoritesOnly) {
                            showFavorites(false)
                        }
                        FilterButton("rpcn.avatar.filter.favorites", str("rpcn.avatar.favorites").format(favoriteCount), selected = favoritesOnly) {
                            showFavorites(true)
                        }
                        Text(
                            str("rpcn.avatar.favoritesHint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                val paths = if (favoritesOnly) all.filter { it.substringAfterLast('/') in favoritesShown } else all
                if (favoritesOnly && paths.isEmpty()) {
                    Text(
                        str("rpcn.avatar.favoritesEmpty"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                if (paths.isNotEmpty()) {
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
                        val columns = maxOf(3, ((maxWidth + 8.dp) / (CellSize + 8.dp)).toInt())

                        // The pad only moves between controls that are on screen, and a lazy grid
                        // places only what is (see armsx3-dpad-nav-lazy-list-dead-end). Centring the
                        // selected avatar's row keeps part of a row on both sides of it in view, so Up
                        // and Down always have somewhere to go; second from the top left a sliver of
                        // the next row on the Odin, whose grid shows about two and a third rows. The
                        // cells opt out of controllerFocusable's own scroll-into-view: it started after
                        // this one and cancelled it, leaving the selection on the bottom row.
                        val selectedId = SettingsControllerNav.currentSelectedId()
                        val spacing = with(LocalDensity.current) { 8.dp.roundToPx() }
                        LaunchedEffect(selectedId, columns) {
                            val index = selectedId?.takeIf { it.startsWith(CellPrefix) }
                                ?.removePrefix(CellPrefix)?.toIntOrNull() ?: return@LaunchedEffect
                            val row = index / columns
                            val layout = grid.layoutInfo
                            val cell = layout.visibleItemsInfo.firstOrNull()?.size?.height
                            if (cell == null) {
                                grid.animateScrollToItem(maxOf(0, (row - 1) * columns))
                                return@LaunchedEffect
                            }
                            val pitch = cell + spacing
                            // Where the selected row's top sits once centred, and the rows above it
                            // that the scroll lands on so its offset stays positive
                            val centred = maxOf(0, (layout.viewportEndOffset - layout.viewportStartOffset - cell) / 2)
                            val above = minOf(row, (centred + pitch - 1) / pitch)
                            grid.animateScrollToItem((row - above) * columns, maxOf(0, above * pitch - centred))
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
                                // The default avatar is one of the firmware's icons: mark it while it is in use
                                val chosen = current != null &&
                                    (path == current.selected || (current.selected.isBlank() && path == current.default))
                                Box(
                                    Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        // Only on the chosen one: a 0.dp border still draws a hairline
                                        .then(
                                            if (chosen) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                                            else Modifier
                                        )
                                        .combinedClickable(
                                            enabled = !busy,
                                            onClick = pick,
                                            onLongClick = { toggleFavorite(path) },
                                        )
                                        .controllerFocusable(
                                            "$CellPrefix$index",
                                            RoundedCornerShape(12.dp),
                                            onConfirm = pick,
                                            onSecondary = { toggleFavorite(path) },
                                            // The grid scrolls to the selection itself (above)
                                            scrollIntoView = false,
                                        ),
                                ) {
                                    AvatarPicture(path, Modifier.fillMaxSize(), CellPixels)
                                    if (path.substringAfterLast('/') in favorites) FavoriteStar(Modifier.align(Alignment.TopEnd))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (browsingPackages) {
        FileBrowserDialog(
            title = str("rpcn.avatar.install"),
            extensions = setOf("pkg", "rap", "edat"),
            onPick = { file ->
                browsingPackages = false
                installAvatars(listOf(pickedFile(file)))
            },
            onDismiss = { browsingPackages = false },
            allowMultiple = true,
            onPickMultiple = { files ->
                browsingPackages = false
                installAvatars(files.map { pickedFile(it) })
            },
            selectedLabel = str("rpcn.avatar.installSelected"),
        )
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

/** All / Favorites. One button whose colours change, not two composables: a swap would drop the
 *  control from the pad's registry and send the selection to the top of the screen. */
@Composable
private fun FilterButton(id: String, label: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors = if (selected) {
            ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else ButtonDefaults.outlinedButtonColors(),
        modifier = Modifier.controllerFocusable(id, onConfirm = onClick),
    ) { Text(label) }
}

@Composable
private fun FavoriteStar(modifier: Modifier) {
    Box(
        modifier
            .padding(4.dp)
            .size(20.dp)
            .background(Color.Black.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("\u2605", color = Color(0xFFFFD54F), fontSize = 12.sp)
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
