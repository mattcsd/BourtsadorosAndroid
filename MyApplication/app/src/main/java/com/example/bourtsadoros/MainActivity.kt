package com.example.bourtsadoros

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.bourtsadoros.ui.screen.MainScreen
import com.example.bourtsadoros.ui.theme.BourtsadorosTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BourtsadorosTheme {
                MainScreen()
            }
        }
    }
}