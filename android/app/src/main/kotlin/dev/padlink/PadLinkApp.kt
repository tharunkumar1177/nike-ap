// PadLinkApp.kt: application object that owns the process-wide settings store and transport router.
package dev.padlink

import android.app.Application
import android.content.Context
import dev.padlink.settings.SettingsStore
import dev.padlink.transport.TransportRouter

/** Process-wide singletons shared by activities and the connection service. */
class PadLinkApp : Application() {

    /** Persistent settings. */
    lateinit var settings: SettingsStore
        private set

    /** Active transport owner. */
    lateinit var router: TransportRouter
        private set

    /** Creates the singletons before any component starts. */
    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        router = TransportRouter(this, settings)
    }

    companion object {
        /** Returns the application object from any context. */
        fun from(context: Context): PadLinkApp = context.applicationContext as PadLinkApp
    }
}
