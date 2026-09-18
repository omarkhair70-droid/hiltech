package com.hiltech.spike.android.camera

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AssetQrCodecTest {
    @Test
    fun generated_hiltech_asset_qr_decodes_to_exact_asset_id() {
        val payload = "hiltech://asset/ASSET-FLUKE-03"
        val matrix = QRCodeWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            256,
            256,
        )

        val pixels = IntArray(256 * 256)

        for (y in 0 until 256) {
            for (x in 0 until 256) {
                pixels[y * 256 + x] =
                    if (matrix[x, y]) {
                        0xff000000.toInt()
                    } else {
                        0xffffffff.toInt()
                    }
            }
        }

        val decoded = AssetQrCodec.decodeArgb(
            width = 256,
            height = 256,
            pixels = pixels,
        )

        assertEquals(payload, decoded)
        assertEquals(
            "ASSET-FLUKE-03",
            AssetQrCodec.parseAssetId(decoded),
        )
    }

    @Test
    fun non_hiltech_or_wrong_resource_qr_is_not_an_asset() {
        assertNull(
            AssetQrCodec.parseAssetId(
                "https://example.com/asset/ASSET-FLUKE-03",
            ),
        )
        assertNull(
            AssetQrCodec.parseAssetId(
                "hiltech://project/PROJECT-A",
            ),
        )
    }
}
