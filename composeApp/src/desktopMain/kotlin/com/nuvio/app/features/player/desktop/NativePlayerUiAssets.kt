package com.nuvio.app.features.player.desktop

/** Shared by the real native bridge and its packaged-asset verification, without loading the JNI library. */
internal object NativePlayerUiAssets {
    fun files(): Map<String, ByteArray> = linkedMapOf(
        "controls.html" to resource("/player-ui/controls.html"),
        "controls.css" to resource("/player-ui/controls.css").toString(Charsets.UTF_8)
            .replace("/* __TELUMIA_PLAYER_FONT_FACES__ */", fontFaces).toByteArray(Charsets.UTF_8),
        "controls.js" to resource("/player-ui/controls.js"),
        "timed-metadata.js" to resource("/player-ui/timed-metadata.js"),
        "fonts/manrope_variable.ttf" to resource("/composeResources/nuvio.composeapp.generated.resources/font/manrope_variable.ttf"),
        "fonts/OFL.txt" to resource("/composeResources/nuvio.composeapp.generated.resources/files/licenses/manrope/OFL.txt"),
        "fonts/FONTLOG.txt" to resource("/composeResources/nuvio.composeapp.generated.resources/files/licenses/manrope/FONTLOG.txt"),
    )

    private val fontFaces = """
        @font-face {
          font-family: "Telumia Manrope";
          src: url("fonts/manrope_variable.ttf") format("truetype");
          font-weight: 200 800;
          font-style: normal;
          font-display: block;
        }
    """.trimIndent()

    private fun resource(path: String): ByteArray = NativePlayerUiAssets::class.java.getResourceAsStream(path)
        ?.use { it.readBytes() } ?: error("Missing native player resource: $path")
}
