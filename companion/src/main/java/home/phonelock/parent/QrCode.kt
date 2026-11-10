package home.phonelock.parent

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code for [text], drawn black on white whatever the theme (scanners need the contrast), scaled
 * without smoothing so the modules stay sharp.
 */
@Composable
internal fun QrCode(text: String, modifier: Modifier = Modifier) {
    val image = remember(text) { encodeQr(text) }
    Image(image, contentDescription = "QR code", filterQuality = FilterQuality.None,
        modifier = modifier.fillMaxWidth().aspectRatio(1f).background(androidx.compose.ui.graphics.Color.White)
            .padding(12.dp))
}

/** Encodes [text] as a QR code with one pixel per module; the Image scales it up. */
private fun encodeQr(text: String): ImageBitmap {
    val hints = mapOf(
        // Medium correction: survives a smudged screen while keeping long setup payloads scannable.
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 0,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    val px = IntArray(m.width * m.height) { i ->
        if (m.get(i % m.width, i / m.width)) Color.BLACK else Color.WHITE
    }
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
