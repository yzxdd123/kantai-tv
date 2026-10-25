package app.kantai.tv

import android.app.Application
import android.util.Log
import app.kantai.tv.data.AppRepo
import app.kantai.tv.data.ProbeStore
import app.kantai.tv.web.WebServer
import fi.iki.elonen.NanoHTTPD

class KantaiApp : Application() {
    lateinit var server: WebServer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppRepo.init(this)
        ProbeStore.init(this)
        server = WebServer(this)
        runCatching { server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
            .onFailure { Log.e("Kantai", "web server failed", it) }
    }

    companion object {
        lateinit var instance: KantaiApp
            private set
    }
}
