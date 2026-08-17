package io.github.jcartu.hermex

import android.app.Application
import io.github.jcartu.hermex.di.AppContainer

class HermexApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
