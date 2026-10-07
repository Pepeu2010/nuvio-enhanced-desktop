package com.nuvio.app.features.profiles

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.svg.SvgDecoder
import com.nuvio.app.core.ui.NavigationMotion
import com.nuvio.app.core.ui.NuvioTheme
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DesktopProfileStudioAvatarEditorTest {
    private fun source(): AvatarRasterSource {
        val image = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color.RED; fillRect(0, 0, 100, 100)
            color = Color.BLUE; fillRect(100, 0, 100, 100); dispose()
        }
        return AvatarRasterPipeline.fromClipboardImage(image)
    }

    private fun choices(directory: Path): List<ProfileStudioAvatarChoice> = ProfileStudioAvatars.library.map { avatar ->
        val output = directory.resolve("${avatar.code}.svg")
        javaClass.getResourceAsStream("/profile-studio/avatars/${avatar.code}.svg")!!.use { Files.copy(it, output) }
        ProfileStudioAvatarChoice(avatar, output.toUri().toString())
    }

    @Test fun laptop1366() = viewport(1366, 768)
    @Test fun fullHd1920() = viewport(1920, 1080)
    @Test fun desktop1440p() = viewport(2560, 1440)
    @Test fun desktop4k() = viewport(3840, 2160)

    private fun viewport(width: Int, height: Int) = runDesktopComposeUiTest(width = width, height = height) {
        val available = choices(Files.createTempDirectory("telumia-avatar-ui-assets"))
        val actions = ProfileStudioEditorActions({ source() }, { source() }, { source() }, { _, _ -> }, {}, {})
        var chooseLabel = ""
        var cancelLabel = ""
        setContent { NuvioTheme(navigationMotion = NavigationMotion.OFF) {
            chooseLabel = stringResource(Res.string.profile_studio_import)
            cancelLabel = stringResource(Res.string.profile_studio_cancel)
            val context = LocalPlatformContext.current
            val loader = remember { ImageLoader.Builder(context).components { add(SvgDecoder.Factory()) }.build() }
            DisposableEffect(loader) { onDispose { loader.shutdown() } }
            Box(Modifier.fillMaxSize().testTag("studio-viewport")) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(androidx.compose.ui.unit.Dp(24f))) {
                    DesktopProfileStudioAvatarEditor(null, available, false, actions, loader)
                }
            }
        } }
        assertTrue(onNodeWithTag("profile-studio").fetchSemanticsNode().boundsInRoot.width <= 1441f)
        onNodeWithText(chooseLabel).assertIsDisplayed().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("avatar-crop-preview").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("avatar-crop-preview").assertIsDisplayed()
        onNodeWithTag("avatar-zoom").assertIsDisplayed()
        val image = onNodeWithTag("studio-viewport").captureToImage().toAwtImage()
        assertEquals(width, image.width); assertEquals(height, image.height)
        ImageIO.write(image, "png", File(File("build/reports/telumia-profile-studio").apply { mkdirs() }, "studio-$width.png"))
        onNodeWithText(cancelLabel).performScrollTo().performClick()
        onNodeWithTag("avatar-crop-preview").assertDoesNotExist()
        val gallery = onNodeWithTag("studio-viewport").captureToImage().toAwtImage()
        ImageIO.write(gallery, "png", File("build/reports/telumia-profile-studio/library-$width.png"))
    }

    @Test fun selectingTheCropSavesTheChosenRegionAndCancelPreservesThePreviousAvatar() = runDesktopComposeUiTest(width = 1366, height = 768) {
        val store = OwnedProfileAvatarStore(Files.createTempDirectory("telumia-avatar-ui-store"))
        val account = ProfileAvatarScope("fixture-account", 1)
        val current = mutableStateOf<String?>(null)
        var calls = 0
        var chooseLabel = ""; var saveLabel = ""; var cancelLabel = ""
        val actions = ProfileStudioEditorActions({ source() }, { source() }, { source() }, { input, crop ->
            current.value = store.savePhoto(account, AvatarRasterPipeline.variants(input, crop)).imageUri; calls++
        }, {}, { store.clear(account); current.value = null })
        setContent { NuvioTheme(navigationMotion = NavigationMotion.OFF) {
            chooseLabel = stringResource(Res.string.profile_studio_import)
            saveLabel = stringResource(Res.string.profile_studio_save)
            cancelLabel = stringResource(Res.string.profile_studio_cancel)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                DesktopProfileStudioAvatarEditor(current.value, emptyList(), true, actions)
            }
        } }
        onNodeWithText(chooseLabel).performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("avatar-center-x").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("avatar-center-x").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        onNodeWithTag("avatar-zoom").performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        onNodeWithText(saveLabel).performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { calls == 1 }
        val saved = assertIs<LocalProfileAvatar.Photo>(store.load(account))
        val output = java.nio.file.Paths.get(java.net.URI(saved.imageUri))
        assertEquals(Color.BLUE.rgb, ImageIO.read(output.toFile()).getRGB(256, 256))
        assertEquals(4, Files.list(output.parent).use { it.filter { path -> path.fileName.toString().endsWith(".png") }.count().toInt() })
        onNodeWithText(chooseLabel).performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("avatar-crop-preview").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText(cancelLabel).performScrollTo().performClick()
        runOnIdle { assertEquals(1, calls); assertEquals(saved, store.load(account)) }
    }

    @Test fun bundledSelectionPersistsAndAClipboardFailureLeavesItIntact() = runDesktopComposeUiTest(width = 1366, height = 768) {
        val directory = Files.createTempDirectory("telumia-avatar-ui-library")
        val available = choices(directory)
        val store = OwnedProfileAvatarStore(directory.resolve("owned"))
        val account = ProfileAvatarScope("fixture-account", 1)
        var pasteLabel = ""; var errorLabel = ""
        var selected: String? = null
        val actions = ProfileStudioEditorActions({ null }, { throw AvatarImageException(AvatarImageFailure.UNSUPPORTED) }, { source() }, { _, _ -> }, { avatar ->
            store.saveBundled(account, avatar.id, available.map { it.avatar.id }.toSet()); selected = avatar.id
        }, {})
        setContent { NuvioTheme(navigationMotion = NavigationMotion.OFF) {
            pasteLabel = stringResource(Res.string.profile_studio_paste)
            errorLabel = stringResource(Res.string.profile_studio_error_format)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { DesktopProfileStudioAvatarEditor(null, available, false, actions) }
        } }
        onNodeWithTag("avatar-choice-${available.first().avatar.id}").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { selected != null }
        val prior = store.load(account)
        onNodeWithText(pasteLabel).performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(errorLabel).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText(errorLabel).performScrollTo().assertIsDisplayed()
        runOnIdle { assertEquals(prior, store.load(account)) }
    }
}
