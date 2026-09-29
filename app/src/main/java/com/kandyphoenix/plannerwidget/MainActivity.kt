package com.kandyphoenix.plannerwidget

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

private const val PLANNER_URL = "https://kandyphoenix.github.io/kandys-planner/"

/**
 * This app exists to host the home-screen widget. The first launch asks for the widget key
 * (the planner Worker's WIDGET_KEY secret); every launch after that just hands off to the
 * browser — the real app is the PWA at kandyphoenix.github.io/kandys-planner.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (WidgetKeyStore.get(this).isNotBlank()) {
            openPlannerAndFinish()
            return
        }
        setContent {
            KeySetup(
                onSave = { key ->
                    WidgetKeyStore.set(this, key)
                    // Refresh every widget instance right away with the new key.
                    WorkManager.getInstance(this).enqueueUniqueWork(
                        "planner_widget_refresh_now",
                        ExistingWorkPolicy.REPLACE,
                        OneTimeWorkRequestBuilder<RefreshWorker>().build(),
                    )
                    openPlannerAndFinish()
                },
            )
        }
    }

    private fun openPlannerAndFinish() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PLANNER_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // No browser handled the intent; nothing else to show.
        }
        finish()
    }
}

@Composable
private fun KeySetup(onSave: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF161619)).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Kandy's Planner widget", color = Color(0xFFF3F3F6), fontSize = 22.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "The planner is locked to your account now. Paste the widget key (the Worker's WIDGET_KEY secret) so the widget can read your agenda. It stays on this phone only.",
            color = Color(0xFFB9B9C2),
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("Widget key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { if (key.isNotBlank()) onSave(key) }, modifier = Modifier.fillMaxWidth()) {
            Text("Save and open the planner")
        }
    }
}
