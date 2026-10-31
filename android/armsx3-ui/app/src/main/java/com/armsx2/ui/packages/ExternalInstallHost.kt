package com.armsx2.ui.packages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.armsx2.i18n.I18n
import com.armsx2.i18n.str
import com.armsx2.packages.ExternalInstall
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.common.ConfirmOverlay
import com.armsx2.ui.settings.SettingsControllerNav
import com.armsx2.ui.settings.controllerFocusable
import com.armsx3.core.ProgressRepository

/** Names listed in the question before the rest are counted instead. */
private const val MaxListed = 8

/**
 * The question for an install another app asked for, then its progress (see ExternalInstall).
 *
 * Mounted once in WindowImpl, above every screen, like GlobalConfirm: the request can arrive while
 * any screen is open.
 */
@Composable
fun ExternalInstallHost() {
    val request = ExternalInstall.showing.value ?: return
    // The installer needs the core, which is still starting when the request is what opened ARMSX3.
    if (!MainActivityRuntime.nativeReady.value) return
    val context = LocalContext.current.applicationContext

    // Back and B mostly arrive through MainActivityRuntime.handleFrontendBack, which asks
    // ExternalInstall first; this catches a back that reaches Compose instead. During the install
    // there is nothing to go back to, and cancel() does nothing then.
    BackHandler(enabled = true) { ExternalInstall.cancel() }

    if (ExternalInstall.installing.value) {
        InstallingOverlay()
        return
    }

    ConfirmOverlay(
        title = str("packages.external.title"),
        message = describe(request),
        confirmLabel = str("packages.external.confirm"),
        idPrefix = "external-install",
        onConfirm = { ExternalInstall.start(context, request) },
        onDismiss = { ExternalInstall.cancel() },
    )
}

/** Who is asking and for what: every file by name, so nothing installs that the user did not see. */
private fun describe(request: ExternalInstall.Request): String {
    val asking = request.caller?.let { I18n.get("packages.external.body").format(it) }
        ?: I18n.get("packages.external.bodyUnknown")
    val names = request.items.map { it.name }
    // A no-break space, so a long name that has to wrap does not leave its bullet on a line alone.
    val listed = names.take(MaxListed).joinToString("\n") { "•\u00A0$it" }
    val more = (names.size - MaxListed).takeIf { it > 0 }
        ?.let { "\n" + I18n.get("packages.external.more").format(it) }
        .orEmpty()
    return "$asking\n\n$listed$more"
}

@Composable
private fun InstallingOverlay() {
    val layer = "external-install-progress"
    // The pad is held here for the whole install, the way ConfirmOverlay holds it for a question.
    // With no selection of ours the home screen gives the D-pad to the cover grid behind the
    // scrim, and A there would start a game while a package is being written.
    DisposableEffect(layer) {
        val previous = SettingsControllerNav.activeLayer.value
        SettingsControllerNav.activeLayer.value = layer
        SettingsControllerNav.clearSelection()
        onDispose {
            SettingsControllerNav.activeLayer.value = previous
            SettingsControllerNav.clearSelection()
        }
    }
    LaunchedEffect(layer) {
        var attempts = 0
        while (!SettingsControllerNav.selectById("$layer.hold") && attempts < 10) {
            attempts++
            withFrameNanos {}
        }
    }

    // getItem returns the entry's state; reading .value here and the longs below is what
    // subscribes this to the native progress callbacks.
    val entry = ProgressRepository.getItem(ExternalInstall.progressId.value)?.value
    val fraction = entry?.let {
        if (it.isIndeterminate()) null
        else (it.value.longValue.toFloat() / it.max.longValue.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            // Swallow taps, so nothing behind the scrim can be started mid-install.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        // What holds the selection: does nothing, and sits under the card where its ring is hidden.
        Box(Modifier.size(1.dp).controllerFocusable("$layer.hold", onConfirm = {}, layer = layer))
        Surface(
            modifier = Modifier.padding(24.dp).widthIn(max = 420.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    str("packages.external.title"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    I18n.get("packages.external.installing").format(ExternalInstall.current.value.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
