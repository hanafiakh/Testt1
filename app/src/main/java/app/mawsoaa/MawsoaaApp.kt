package app.mawsoaa

import android.app.Application
import app.mawsoaa.data.Content
import app.mawsoaa.data.Prefs

class MawsoaaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Content.init(this)
        Prefs.init(this)
    }
}
