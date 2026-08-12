package com.armsx2.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.armsx2.CustomCovers
import com.armsx2.GameInfo

/**
 * Try each cover source in turn, falling through to [placeholder] when none load.
 *
 * There were two hand-rolled copies of this, one here and one in the library grid, and they
 * disagreed: this one ended its chain at the extracted ICON0.PNG, the grid's ended one step
 * earlier at the remote cover. So the in-game menu showed a PS3 game's own artwork while the
 * library showed a text placeholder for the same game -- reported for a European PSN title,
 * because the art repo's COV set is keyed by USA title IDs and has no entry for it.
 *
 * Both were also only ONE retry deep, which hid the divergence: the retry slot was spent on
 * the regional cover, and whether the local icon ever got a turn depended on whether that URL
 * happened to differ from the first one. A chain has no such limit, and one chain cannot
 * disagree with itself.
 */
@Composable
fun CoverFallbackChain(
    models: List<Any>,
    contentDescription: String,
    contentScale: ContentScale,
    placeholder: @Composable () -> Unit,
) {
    val head = models.firstOrNull()
    if (head == null) {
        placeholder()
        return
    }
    SubcomposeAsyncImage(
        model = head,
        contentDescription = contentDescription,
        modifier = Modifier.fillMaxSize(),
        contentScale = contentScale,
        loading = { placeholder() },
        error = { CoverFallbackChain(models.drop(1), contentDescription, contentScale, placeholder) },
    )
}

@Composable
fun GameCoverArt(game: GameInfo, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val customCover = remember(game.uri, CustomCovers.version.value) {
        CustomCovers.fileFor(context, game)
    }
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context)
            .data(customCover ?: game.coverModel)
            .crossfade(true)
            .build(),
        contentDescription = game.title,
        modifier = modifier.clip(RoundedCornerShape(14.dp)),
        contentScale = ContentScale.Crop,
        loading = { GameCoverPlaceholder(game.title, game.serial) },
        error = {
            // Cover Region can point at a release the art repo has no cover for; falling straight
            // to the placeholder would BLANK a cover the user already had (reported for the in-game
            // menu, which uses this component). Retry with this disc's own serial first, then with
            // the ICON0.PNG extracted from the disc itself -- wrong shape, but it is the real
            // game's art and beats nothing, and aldostools does not have art for every title.
            CoverFallbackChain(
                models = if (customCover != null) emptyList() else listOfNotNull(
                    game.discCoverUrl?.takeIf { it != game.coverUrl },
                    game.discIconFile,
                ),
                contentDescription = game.title,
                contentScale = ContentScale.Crop,
                placeholder = { GameCoverPlaceholder(game.title, game.serial) },
            )
        },
    )
}

@Composable
fun GameCoverPlaceholder(title: String, serial: String? = null, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (!serial.isNullOrBlank()) {
                Text(
                    text = serial,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.68f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
