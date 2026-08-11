package dev.bookscanner.app

import android.app.Application

class BookScannerApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(AppContainer.defaultStorageRoot(this))
    }
}
