package com.quasarapps.quasarsampleapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.quasarapps.quasarsampleapp.ui.theme.QuasarSampleAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QuasarSampleAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WebViewScreen(
                        url = "https://www.quasarapps.com",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

