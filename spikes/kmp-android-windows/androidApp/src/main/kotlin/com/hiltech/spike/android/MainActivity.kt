package com.hiltech.spike.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hiltech.spike.shared.HiltechRtlAdaptiveApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            HiltechRtlAdaptiveApp()
        }
    }
}
