package com.phillipchin.webrtctunnel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.phillipchin.webrtctunnel.ui.WebRtcTunnelApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deps = (application as HasAppDependencies).deps
        setContent {
            WebRtcTunnelApp(deps)
        }
    }
}
