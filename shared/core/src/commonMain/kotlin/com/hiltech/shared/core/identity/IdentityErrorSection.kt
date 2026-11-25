package com.hiltech.shared.core.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hiltech.shared.core.design.HiltechLanguage
import com.hiltech.shared.core.design.hiltechText

@Composable
internal fun IdentityLanguageChoice(language: HiltechLanguage, onChange: (HiltechLanguage) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = language == HiltechLanguage.Arabic,
            onClick = { onChange(HiltechLanguage.Arabic) }, label = { Text("العربية") })
        FilterChip(selected = language == HiltechLanguage.English,
            onClick = { onChange(HiltechLanguage.English) }, label = { Text("English") })
    }
}

@Composable
internal fun IdentityErrorSection(
    presentation: IdentityErrorPresentation,
    onRecover: (IdentityRecoveryAction) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(presentation.title, style = MaterialTheme.typography.titleLarge)
        Text(presentation.message, style = MaterialTheme.typography.bodyLarge)
        presentation.supportCode?.let { code ->
            Text(hiltechText("المرجع: ", "Reference: ") + code, style = MaterialTheme.typography.labelSmall)
        }
        presentation.primaryLabel?.let { label ->
            Button(onClick = { onRecover(presentation.primaryAction) }) { Text(label) }
        }
        val secondary = presentation.secondaryAction
        val secondaryLabel = presentation.secondaryLabel
        if (secondary != null && secondaryLabel != null) {
            OutlinedButton(onClick = { onRecover(secondary) }) { Text(secondaryLabel) }
        }
    }
}
