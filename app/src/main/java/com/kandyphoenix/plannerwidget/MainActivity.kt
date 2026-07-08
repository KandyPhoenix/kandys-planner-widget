package com.kandyphoenix.plannerwidget

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private const val PLANNER_URL = "https://kandyphoenix.github.io/kandys-planner/"

/**
 * This app exists to host the home-screen widget. Opening the icon just hands off
 * to the browser — the real app is the PWA at kandyphoenix.github.io/kandys-planner.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PLANNER_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        } catch (e: Exception) {
            // Fall through to the placeholder screen below if no browser handled the intent.
        }
        setContent { Placeholder() }
    }
}

@Composable
private fun Placeholder() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF161619)).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Add the Kandy's Planner widget to your home screen — long-press an empty area, tap Widgets, and find it here.", color = Color(0xFFF3F3F6))
    }
}
