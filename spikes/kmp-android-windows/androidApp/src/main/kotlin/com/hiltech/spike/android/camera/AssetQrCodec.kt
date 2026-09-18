package com.hiltech.spike.android.camera

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.net.URI

object AssetQrCodec {
    fun decodeArgb(
        width: Int,
        height: Int,
        pixels: IntArray,
    ): String {
        val source = RGBLuminanceSource(
            width,
            height,
            pixels,
        )

        return MultiFormatReader()
            .decode(
                BinaryBitmap(
                    HybridBinarizer(source),
                ),
            )
            .text
    }

    fun parseAssetId(payload: String): String? {
        val uri = runCatching { URI(payload) }.getOrNull()
            ?: return null

        if (uri.scheme != "hiltech" || uri.host != "asset") {
            return null
        }

        return uri.path
            ?.trim('/')
            ?.takeIf { it.isNotBlank() }
    }
}
