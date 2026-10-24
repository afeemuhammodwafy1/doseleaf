package com.wafyro.doseleaf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.wafyro.doseleaf.ui.DoseleafApp
import com.wafyro.doseleaf.ui.theme.DoseleafTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DoseleafTheme { DoseleafApp() } }
    }
}
