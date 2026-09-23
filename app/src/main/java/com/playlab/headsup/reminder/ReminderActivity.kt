package com.headsup.app.reminder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.headsup.app.ui.theme.HeadsUpTheme

/** 全屏提醒页（强制打断） */
class ReminderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            HeadsUpTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        FilledTonalIconButton(onClick = {}, modifier = Modifier.size(96.dp)) {
                            Icon(Icons.Default.DirectionsWalk, null, Modifier.size(56.dp))
                        }
                        Spacer(Modifier.height(24.dp))
                        Text(ReminderManager.randomTitle(), style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "走路时请少看手机，注意周围环境",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(32.dp))
                        Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) {
                            Text("我知道了，抬头看路")
                        }
                    }
                }
            }
        }
    }
}
