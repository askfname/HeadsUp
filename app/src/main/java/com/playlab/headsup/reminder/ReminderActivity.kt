package com.playlab.headsup.reminder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.playlab.headsup.R
import com.playlab.headsup.ui.theme.HeadsUpTheme

/** 全屏提醒页（强制打断） */
class ReminderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // 沉浸式：系统栏透明，图标深浅自动适配
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
                            Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, Modifier.size(56.dp))
                        }
                        Spacer(Modifier.height(24.dp))
                        Text(ReminderManager.randomTitle(this@ReminderActivity), style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.reminder_content),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(32.dp))
                        Button(onClick = { finishAndRemoveTask() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.reminder_confirm))
                        }
                    }
                }
            }
        }
    }
}
