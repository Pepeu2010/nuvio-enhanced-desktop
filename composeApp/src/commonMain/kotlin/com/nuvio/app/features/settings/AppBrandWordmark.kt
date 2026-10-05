package com.nuvio.app.features.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.MaterialTheme
import com.nuvio.app.core.ui.appTheme
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.app_logo_wordmark_original
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun AppBrandWordmark(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    icon: AppIconOption? = null,
    compact: Boolean = false,
) {
    val state by remember {
        AppIconRepository.ensureLoaded()
        AppIconRepository.state
    }.collectAsStateWithLifecycle()
    val artwork = icon?.wordmarkResource ?: MaterialTheme.appTheme.wordmarkResource(state.selected)
    val colors = MaterialTheme.nuvio.colors
    BoxWithConstraints(
        modifier = modifier.aspectRatio(if (compact) 1f else 4.1f)
            .clearAndSetSemantics { if (contentDescription != null) this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        val height = maxHeight
        val fontScale = LocalDensity.current.fontScale
        if (!compact && artwork != Res.drawable.app_logo_wordmark_original) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(artwork), contentDescription = null,
                    modifier = Modifier.height(height * 0.68f), contentScale = ContentScale.Fit)
                Text("ENHANCED", style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = (height.value * 0.24f / fontScale).sp,
                    lineHeight = (height.value * 0.28f / fontScale).sp,
                    letterSpacing = (height.value * 0.055f / fontScale).sp,
                ), color = colors.textSecondary, maxLines = 1)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.height(height * 0.86f).aspectRatio(1f)) {
                    drawRoundRect(colors.accent.copy(alpha = 0.12f), cornerRadius = CornerRadius(size.width * 0.22f))
                    val aperture = Path().apply {
                        moveTo(size.width * 0.20f, size.height * 0.20f)
                        lineTo(size.width * 0.44f, size.height * 0.20f)
                        lineTo(size.width * 0.28f, size.height * 0.80f)
                        lineTo(size.width * 0.20f, size.height * 0.80f)
                        close()
                        moveTo(size.width * 0.72f, size.height * 0.20f)
                        lineTo(size.width * 0.80f, size.height * 0.20f)
                        lineTo(size.width * 0.80f, size.height * 0.80f)
                        lineTo(size.width * 0.56f, size.height * 0.80f)
                        close()
                    }
                    drawPath(aperture, colors.accent)
                    val light = Path().apply {
                        moveTo(size.width * 0.43f, size.height * 0.34f)
                        lineTo(size.width * 0.66f, size.height * 0.50f)
                        lineTo(size.width * 0.43f, size.height * 0.66f)
                        close()
                    }
                    drawPath(light, colors.textPrimary)
                }
                if (!compact) {
                    Spacer(Modifier.width(height * 0.24f))
                    Column {
                        Text("NUVIO", style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = (height.value * 0.50f / fontScale).sp,
                            lineHeight = (height.value * 0.54f / fontScale).sp,
                            letterSpacing = (height.value * 0.02f / fontScale).sp,
                            fontWeight = FontWeight.SemiBold,
                        ), color = colors.textPrimary, maxLines = 1)
                        Text("ENHANCED", style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = (height.value * 0.24f / fontScale).sp,
                            lineHeight = (height.value * 0.28f / fontScale).sp,
                            letterSpacing = (height.value * 0.045f / fontScale).sp,
                        ), color = colors.textSecondary, maxLines = 1)
                    }
                }
            }
        }
    }
}
