package dev.bookscanner.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.decode.BitmapFactoryDecoder
import coil3.decode.ExifOrientationStrategy

class BookScannerApplication :
    Application(),
    SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(AppContainer.defaultStorageRoot(this))
    }

    /**
     * Coil is configured to **ignore EXIF orientation**, which is the opposite
     * of its default.
     *
     * The storage invariant (see `PageImageNormalizer`) keeps a capture's bytes
     * untouched and moves its declared orientation into the page's geometry, so
     * geometry is the single source of truth. Coil's default is to apply EXIF
     * while decoding, which meant every preview was rotated twice — once by
     * Coil and once by us. On a Pixel 7 that turned an upright page sideways in
     * the editor, and the crop rectangle was computed against the wrong extent.
     *
     * `PageImageDecoder` uses `BitmapFactory`, which never applies EXIF; this
     * makes the display path agree with it.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .components {
                add(BitmapFactoryDecoder.Factory(exifOrientationStrategy = ExifOrientationStrategy.IGNORE))
            }.build()
}
