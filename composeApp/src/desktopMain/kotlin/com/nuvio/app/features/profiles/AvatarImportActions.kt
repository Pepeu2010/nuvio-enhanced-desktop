package com.nuvio.app.features.profiles

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

internal object AvatarImportActions {
    suspend fun chooseFile(): Path? = suspendCancellableCoroutine { continuation ->
        val chooserRef = AtomicReference<JFileChooser?>()
        continuation.invokeOnCancellation { SwingUtilities.invokeLater { chooserRef.get()?.let { SwingUtilities.getWindowAncestor(it)?.dispose() } } }
        SwingUtilities.invokeLater {
            if (!continuation.isActive) return@invokeLater
            val chooser = JFileChooser().apply {
                isMultiSelectionEnabled = false
                fileFilter = FileNameExtensionFilter("PNG, JPEG, GIF, BMP", "png", "jpg", "jpeg", "gif", "bmp")
            }
            chooserRef.set(chooser)
            val selected = if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.toPath() else null
            if (continuation.isActive) continuation.resume(selected)
        }
    }

    fun singleFile(transferable: Transferable): Path {
        if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) throw AvatarImageException(AvatarImageFailure.UNSUPPORTED)
        val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*> ?: throw AvatarImageException(AvatarImageFailure.INVALID)
        if (files.size != 1) throw AvatarImageException(AvatarImageFailure.INVALID)
        return (files.single() as? File)?.toPath() ?: throw AvatarImageException(AvatarImageFailure.INVALID)
    }

    /** Called only after an explicit Paste action. Text URLs are not imported from the clipboard. */
    suspend fun readClipboard(): AvatarRasterSource = withContext(Dispatchers.IO) {
        val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
            ?: throw AvatarImageException(AvatarImageFailure.EMPTY)
        if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            val image = contents.getTransferData(DataFlavor.imageFlavor) as? java.awt.Image ?: throw AvatarImageException(AvatarImageFailure.INVALID)
            AvatarRasterPipeline.fromClipboardImage(image)
        } else AvatarRasterPipeline.readFile(singleFile(contents))
    }
}
