package com.muza.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.muza.app.ui.screens.MainScreen
import com.muza.app.ui.screens.MainViewModel
import com.muza.app.ui.screens.SettingsScreen
import com.muza.app.ui.screens.YouTubeAuthScreen
import com.muza.app.ui.theme.MuzaTheme

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        setContent {
            MuzaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MuzaAppNavHost(mainViewModel = mainViewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val textToProcess: String? = when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    ?: intent.clipData?.let { cd ->
                        if (cd.itemCount > 0) cd.getItemAt(0).text?.toString() else null
                    }
                    ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
                    ?: intent.dataString
            }
            Intent.ACTION_VIEW -> {
                intent.dataString
            }
            else -> null
        }

        if (!textToProcess.isNullOrBlank()) {
            mainViewModel.processUrl(textToProcess)
        }
    }
}

@Composable
fun MuzaAppNavHost(mainViewModel: MainViewModel) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "main"
    ) {
        composable("main") {
            MainScreen(
                onNavigateToSettings = { navController.navigate("settings") },
                viewModel = mainViewModel
            )
        }
        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAuth = { navController.navigate("auth") }
            )
        }
        composable("auth") {
            YouTubeAuthScreen(
                onNavigateBack = { navController.popBackStack() },
                onAuthSuccess = { navController.popBackStack() }
            )
        }
    }
}
