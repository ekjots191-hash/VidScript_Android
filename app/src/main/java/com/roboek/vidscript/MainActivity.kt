package com.roboek.vidscript

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.roboek.vidscript.ui.*
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VidScriptTheme {
                LaunchedEffect(Unit) { delay(1800); vm.splash = false }
                Box(Modifier.fillMaxSize()) {
                    MainScreen(vm)
                    if (vm.showGuide && !vm.splash) GuideDialog(vm)
                    if (vm.showModels) ModelManagerDialog(vm)
                    AnimatedVisibility(vm.splash, exit = fadeOut()) { SplashOverlay() }
                }
            }
        }
    }
}
