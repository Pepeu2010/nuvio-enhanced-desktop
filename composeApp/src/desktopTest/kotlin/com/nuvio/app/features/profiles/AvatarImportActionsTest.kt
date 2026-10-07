package com.nuvio.app.features.profiles

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import kotlin.test.*

class AvatarImportActionsTest {
    private fun files(entries: List<*>): Transferable = object : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor?) = flavor == DataFlavor.javaFileListFlavor
        override fun getTransferData(flavor: DataFlavor?): Any = if (isDataFlavorSupported(flavor)) entries else throw UnsupportedFlavorException(flavor)
    }

    @Test fun dropUsesOneExplicitFileAndNeverTurnsClipboardTextIntoAUrlRequest() {
        val selected = File("local-avatar.png")
        assertEquals(selected.toPath(), AvatarImportActions.singleFile(files(listOf(selected))))
        assertFailsWith<AvatarImageException> { AvatarImportActions.singleFile(StringSelection("https://example.com/image.png")) }
        assertFailsWith<AvatarImageException> { AvatarImportActions.singleFile(files(listOf(selected, selected))) }
        assertFailsWith<AvatarImageException> { AvatarImportActions.singleFile(files(listOf("file:///private.txt"))) }
    }
}
