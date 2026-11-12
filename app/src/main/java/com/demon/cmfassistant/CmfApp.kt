package com.demon.cmfassistant

import android.app.Application
import com.demon.cmfassistant.core.AssistantController
import com.demon.cmfassistant.data.Settings

class CmfApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var controller: AssistantController
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        controller = AssistantController(this, settings)
    }
}
