package com.example.appletvremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.appletvremote.R
import com.example.appletvremote.protocol.discovery.AppleTvDevice

@Composable
fun PinScreen(
    device: AppleTvDevice,
    forAirPlay: Boolean = false,
    busy: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
) {
    var pin by remember(device.credentialKey, forAirPlay) { mutableStateOf("") }
    val title = stringResource(
        if (forAirPlay) R.string.airplay_pairing_title else R.string.pairing_title,
    )
    val hint = stringResource(
        if (forAirPlay) R.string.airplay_pairing_hint else R.string.pairing_hint,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // M3 tonal circle behind the device glyph, like a large avatar.
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(
                modifier = Modifier.size(72.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tv,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = device.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(20.dp))

        error?.let {
            MessageCard(message = it, isError = true, onDismiss = onDismissError)
            Spacer(Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = pin,
            onValueChange = { value ->
                val digits = value.filter { it.isDigit() }.take(4)
                pin = digits
            },
            label = { Text(stringResource(R.string.pin_label)) },
            singleLine = true,
            enabled = !busy,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = MaterialTheme.typography.headlineMedium.copy(
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.width(220.dp),
        )

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = { onSubmit(pin) },
            enabled = pin.length == 4 && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(
                    text = stringResource(R.string.connect),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        TextButton(onClick = onCancel, enabled = !busy) {
            Text(stringResource(R.string.cancel))
        }
    }
}
