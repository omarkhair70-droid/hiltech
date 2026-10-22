package com.hiltech.spike.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HiltechSpikeApp() {
    var approvals by remember { mutableIntStateOf(3) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("HILTECH", style = MaterialTheme.typography.headlineMedium)
                Text("Shared Android + Windows feature")
                Text("Needs You: $approvals")

                Button(
                    onClick = {
                        if (approvals > 0) approvals -= 1
                    },
                    enabled = approvals > 0,
                ) {
                    Text(if (approvals > 0) "Resolve one approval" else "All clear")
                }
            }
        }
    }
}
