package com.nuvio.app.features.profiles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.isDesktop
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.core.ui.LocalUiMotion
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.NuvioToastHost
import com.nuvio.app.features.membership.CosmeticEntitlement
import com.nuvio.app.features.settings.MemberBrandWordmark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun ProfileSelectionScreen(
    onProfileSelected: (NuvioProfile) -> Unit,
    onEditProfile: (NuvioProfile) -> Unit,
    onAddProfile: () -> Unit,
    onBack: (() -> Unit)? = null,
    interactionEnabled: Boolean = true,
    activeProfileIndex: Int? = null,
    contentVisible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pinDialogProfile by remember { mutableStateOf<NuvioProfile?>(null) }
    var isEditMode by remember { mutableStateOf(false) }
    var hoveredProfileIndex by remember { mutableStateOf<Int?>(null) }

    val motion = LocalUiMotion.current
    val titleAlpha = remember { Animatable(0f) }
    val titleOffset = remember { Animatable(20f) }
    val manageAlpha = remember { Animatable(0f) }
    val onProfileClick: (NuvioProfile) -> Unit = { profile ->
        if (interactionEnabled) {
            routeProfileSelection(
                profile = profile,
                isEditMode = isEditMode,
                activeProfileIndex = activeProfileIndex,
                onEditProfile = onEditProfile,
                onActiveProfileSelected = { scope.launch { showAlreadyActiveProfileToast(it) } },
                onPinRequired = { pinDialogProfile = it },
                onProfileSelected = onProfileSelected,
            )
        }
    }

    LaunchedEffect(Unit) {
        AvatarRepository.refreshAvatars()
    }

    LaunchedEffect(motion) {
        launch { titleAlpha.animateTo(1f, tween(motion.durationMillis(280), easing = FastOutSlowInEasing)) }
        launch { titleOffset.animateTo(0f, tween(motion.durationMillis(280), easing = FastOutSlowInEasing)) }
        delay(motion.durationMillis(90).toLong())
        manageAlpha.animateTo(1f, tween(motion.durationMillis(180)))
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val backgroundProfile = profileState.profiles.firstOrNull { it.profileIndex == hoveredProfileIndex }
        ?: profileState.activeProfile ?: profileState.profiles.firstOrNull()
    val hoveredProfileColor = remember(profileState.profiles, hoveredProfileIndex) {
        if (!isDesktop) {
            null
        } else {
            profileState.profiles
                .firstOrNull { it.profileIndex == hoveredProfileIndex }
                ?.avatarColorHex
                ?.let(::parseHexColor)
        }
    }

    LaunchedEffect(profileState.profiles) {
        if (hoveredProfileIndex != null && profileState.profiles.none { it.profileIndex == hoveredProfileIndex }) {
            hoveredProfileIndex = null
        }
    }

    fun updateHoveredProfile(profile: NuvioProfile, isHovered: Boolean) {
        hoveredProfileIndex = if (isHovered) {
            profile.profileIndex
        } else {
            hoveredProfileIndex.takeUnless { it == profile.profileIndex }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize(),
    ) {
        ProfileBackgroundBackdrop(
            profile = backgroundProfile,
            colorOverride = hoveredProfileColor,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Color(0xFF080D16).copy(alpha = 0.5f), Color(0xFF080D16).copy(alpha = 0.8f)))))

        AnimatedVisibility(
            visible = contentVisible,
            enter = fadeIn(tween(motion.durationMillis(180))),
            exit = fadeOut(tween(motion.durationMillis(180))),
            modifier = Modifier.fillMaxSize(),
        ) {
            ProfileSelectionContent(
                profiles = profileState.profiles,
                isEditMode = isEditMode,
                enabled = interactionEnabled,
                isLoaded = profileState.isLoaded,
                headerAlpha = titleAlpha.value,
                headerOffset = if (motion.allowsSpatialEffects) titleOffset.value else 0f,
                manageAlpha = manageAlpha.value,
                onManage = { isEditMode = !isEditMode },
                onAdd = onAddProfile,
                onProfileClick = onProfileClick,
                onProfileHighlight = ::updateHoveredProfile,
                modifier = Modifier.padding(top = statusBarTop),
            )
        }
        if (onBack != null && interactionEnabled && contentVisible) {
            NuvioBackButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = statusBarTop + 8.dp),
            )
        }

        NuvioToastHost(modifier = Modifier.align(Alignment.TopCenter))
    }

    pinDialogProfile?.let { profile ->
        PinEntryDialog(
            profileName = profile.name,
            onVerify = { pin -> ProfileRepository.verifyPin(profile.profileIndex, pin) },
            onVerified = {
                pinDialogProfile = null
                if (interactionEnabled && profile.profileIndex != activeProfileIndex) {
                    onProfileSelected(profile)
                }
            },
            onDismiss = { pinDialogProfile = null },
        )
    }
}

@Composable
internal fun ProfileSelectionContent(
    profiles: List<NuvioProfile>,
    isEditMode: Boolean,
    enabled: Boolean,
    onManage: () -> Unit,
    onAdd: () -> Unit,
    onProfileClick: (NuvioProfile) -> Unit,
    onProfileHighlight: (NuvioProfile, Boolean) -> Unit = { _, _ -> },
    headerAlpha: Float = 1f,
    headerOffset: Float = 0f,
    manageAlpha: Float = 1f,
    modifier: Modifier = Modifier,
    isLoaded: Boolean = true,
) {
    BoxWithConstraints(modifier.fillMaxSize().testTag("profile-selection")) {
        val wide = maxWidth >= 900.dp
        val short = maxHeight < 700.dp
        val horizontalPadding = if (wide) 56.dp else 24.dp
        val count = if (!isLoaded) 0 else profiles.size +
            if ((isEditMode || profiles.isEmpty()) && profiles.size < MAX_PROFILES) 1 else 0
        val contentWidth = (maxWidth - horizontalPadding * 2).coerceAtMost(1600.dp)
        val maximumColumns = ((contentWidth + 20.dp) / 156.dp).toInt().coerceAtLeast(1)
        val columns = if (wide) {
            if (count > maximumColumns) minOf(3, maximumColumns) else count.coerceAtLeast(1)
        } else if (contentWidth < 292.dp) 1 else 2
        val cardWidth = ((contentWidth - 20.dp * (columns - 1)) / columns).coerceIn(136.dp, 208.dp)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                .padding(horizontal = horizontalPadding, vertical = if (short) 32.dp else 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                Modifier.widthIn(max = 1600.dp).fillMaxWidth()
                    .graphicsLayer { alpha = headerAlpha; translationY = headerOffset },
                horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally,
            ) {
                MemberBrandWordmark(height = 36.dp)
                Spacer(Modifier.height(if (short) 20.dp else 32.dp))
                Text(
                    stringResource(Res.string.profile_who_is_watching),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = if (wide) 56.sp else 34.sp,
                        letterSpacing = (-1.4).sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = Color(0xFFF4EEDD),
                )
                Spacer(Modifier.height(10.dp))
                Text(stringResource(Res.string.telumia_profile_selection_hint),
                    style = MaterialTheme.typography.bodyLarge, color = Color(0xFFB7BCC5))
            }
            Spacer(Modifier.height(if (short) 24.dp else 40.dp))
            if (!isLoaded) CircularProgressIndicator(Modifier.testTag("profile-loading"), color = Color(0xFFE8BE72))
            if (isLoaded && profiles.isEmpty()) {
                Text(stringResource(Res.string.telumia_profile_selection_empty),
                    color = Color(0xFFB7BCC5), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
            }
            Column(Modifier.widthIn(max = 1600.dp).fillMaxWidth().focusGroup(),
                verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                for (rowStart in 0 until count step columns) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        for (index in rowStart until minOf(rowStart + columns, count)) {
                            if (index < profiles.size) {
                                val profile = profiles[index]
                                androidx.compose.runtime.key(profile.id, profile.profileIndex) {
                                    ProfileAvatarCard(profile, isEditMode, index * 35, enabled,
                                        { onProfileHighlight(profile, it) }, { onProfileClick(profile) }, cardWidth)
                                }
                            } else AddProfileCard(index * 35, enabled, onAdd, cardWidth)
                        }
                    }
                }
            }
            Spacer(Modifier.height(if (short) 24.dp else 36.dp))
            val manageInteraction = remember { MutableInteractionSource() }
            val manageFocused by manageInteraction.collectIsFocusedAsState()
            Box(Modifier.testTag("profile-manage").graphicsLayer { alpha = manageAlpha }
                .clip(RoundedCornerShape(14.dp))
                .background(if (isEditMode || manageFocused) Color(0xFF342B20) else Color(0xFF111B28))
                .border(if (manageFocused) 2.dp else 1.dp,
                    if (manageFocused || isEditMode) Color(0xFFE8BE72) else Color(0xFF354252), RoundedCornerShape(14.dp))
                .clickable(enabled = enabled && isLoaded, interactionSource = manageInteraction, indication = null, onClick = onManage)
                .padding(horizontal = 28.dp, vertical = 14.dp)) {
                Text(stringResource(if (isEditMode) Res.string.action_done else Res.string.profile_manage_profiles),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFF4EEDD), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ProfileAvatarCard(
    profile: NuvioProfile,
    isEditMode: Boolean,
    animDelay: Int,
    enabled: Boolean,
    onHoverChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    cardWidth: Dp = 150.dp,
) {
    val avatarColor = remember(profile.avatarColorHex) {
        parseHexColor(profile.avatarColorHex)
    }
    val avatars by AvatarRepository.avatars.collectAsStateWithLifecycle()
    val avatarItem = remember(profile.avatarId, avatars) {
        profile.avatarId?.let { id -> avatars.find { it.id == id } }
    }
    val avatarImageUrl = rememberProfileAvatarImageUrl(profile, avatarItem)

    val motion = LocalUiMotion.current
    val animAlpha = remember { Animatable(0f) }
    val animScale = remember { Animatable(0.85f) }
    val animOffset = remember { Animatable(30f) }

    LaunchedEffect(motion) {
        delay(motion.durationMillis(animDelay + 60).toLong())
        launch { animAlpha.animateTo(1f, tween(motion.durationMillis(220), easing = FastOutSlowInEasing)) }
        launch { animScale.animateTo(1f, tween(motion.durationMillis(260), easing = FastOutSlowInEasing)) }
        launch { animOffset.animateTo(0f, tween(motion.durationMillis(260), easing = FastOutSlowInEasing)) }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val currentOnHoverChange = rememberUpdatedState(onHoverChange)
    val pressScale = motion.scale(if (isPressed) 0.95f else 1f)

    LaunchedEffect(isHovered, isFocused, profile.profileIndex) {
        if (isDesktop) {
            currentOnHoverChange.value(isHovered || isFocused)
        }
    }

    DisposableEffect(profile.profileIndex) {
        onDispose {
            if (isDesktop) {
                currentOnHoverChange.value(false)
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .testTag("profile-card-${profile.profileIndex}")
            .width(cardWidth)
            .graphicsLayer {
                alpha = animAlpha.value
                scaleX = motion.scale(animScale.value) * pressScale
                scaleY = motion.scale(animScale.value) * pressScale
                translationY = if (motion.allowsSpatialEffects) animOffset.value else 0f
            }
            .clip(RoundedCornerShape(24.dp))
            .background(if (isFocused || isHovered) Color(0xFF1D2938) else Color(0xFF101B29))
            .border(if (isFocused) 2.dp else 1.dp,
                if (isFocused || isHovered) Color(0xFFE8BE72) else Color(0xFF293746), RoundedCornerShape(24.dp))
            .then(
                if (isDesktop) {
                    Modifier.hoverable(interactionSource)
                } else {
                    Modifier
                },
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 20.dp),
    ) {
        Box(
            modifier = Modifier.size((cardWidth - 24.dp).coerceAtMost(152.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarImageUrl != null) {
                val bgColor = avatarItem?.bgColor?.let { parseHexColor(it) } ?: avatarColor
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(20.dp))
                        .background(bgColor.copy(alpha = 0.2f)),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (avatarItem != null) {
                            avatarItem.bgColor?.let { parseHexColor(it) } ?: avatarColor
                        } else {
                            avatarColor.copy(alpha = 0.15f)
                        },
                    )
                    .then(
                        if (avatarImageUrl == null) Modifier.border(1.dp, avatarColor.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                        else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarImageUrl != null) {
                    AsyncImage(
                        model = avatarImageUrl,
                        contentDescription = avatarItem?.displayName ?: profile.name,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else if (profile.name.isNotBlank()) {
                    Text(
                        text = profile.name.take(1).uppercase(),
                        style = MaterialTheme.typography.headlineLarge.copy(fontSize = 54.sp),
                        color = avatarColor,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = avatarColor,
                        modifier = Modifier.size(46.dp),
                    )
                }
            }

            if (isEditMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            if (profile.pinEnabled && !isEditMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = profile.name.ifBlank {
                stringResource(Res.string.profile_label_number, profile.profileIndex)
            },
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
            color = Color(0xFFF4EEDD),
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AddProfileCard(
    animDelay: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    cardWidth: Dp = 150.dp,
) {
    val motion = LocalUiMotion.current
    val animAlpha = remember { Animatable(0f) }
    val animScale = remember { Animatable(0.85f) }
    val animOffset = remember { Animatable(30f) }

    LaunchedEffect(motion) {
        delay(motion.durationMillis(animDelay + 60).toLong())
        launch { animAlpha.animateTo(1f, tween(motion.durationMillis(220), easing = FastOutSlowInEasing)) }
        launch { animScale.animateTo(1f, tween(motion.durationMillis(260), easing = FastOutSlowInEasing)) }
        launch { animOffset.animateTo(0f, tween(motion.durationMillis(260), easing = FastOutSlowInEasing)) }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val pressScale = motion.scale(if (isPressed) 0.95f else 1f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .testTag("profile-add")
            .width(cardWidth)
            .graphicsLayer {
                alpha = animAlpha.value
                scaleX = motion.scale(animScale.value) * pressScale
                scaleY = motion.scale(animScale.value) * pressScale
                translationY = if (motion.allowsSpatialEffects) animOffset.value else 0f
            }
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF101B29))
            .border(if (isFocused) 2.dp else 1.dp,
                if (isFocused) Color(0xFFE8BE72) else Color(0xFF293746), RoundedCornerShape(24.dp))
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 20.dp),
    ) {
        Box(
            modifier = Modifier.size((cardWidth - 24.dp).coerceAtMost(152.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .border(
                        2.dp,
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                        RoundedCornerShape(20.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(Res.string.compose_profile_add_profile),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
            color = Color(0xFFF4EEDD),
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            minLines = 2,
        )
    }
}
