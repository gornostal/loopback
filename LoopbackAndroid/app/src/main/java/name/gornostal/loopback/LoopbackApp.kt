package name.gornostal.loopback

import android.app.Application
import name.gornostal.loopback.push.Notifications

class LoopbackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
