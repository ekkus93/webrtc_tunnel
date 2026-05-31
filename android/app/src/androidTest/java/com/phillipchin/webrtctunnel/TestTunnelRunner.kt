package com.phillipchin.webrtctunnel

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

class TestTunnelRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        return super.newApplication(cl, TestWebRtcTunnelApplication::class.java.name, context)
    }
}
