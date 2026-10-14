package com.imayx.host.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.imayx.host.graph
import com.imayx.host.security.AppLock
import com.imayx.host.security.BiometricHelper
import com.imayx.host.security.findFragmentActivity
import com.imayx.host.ui.components.BtnKind
import com.imayx.host.ui.components.IxButton
import com.imayx.host.ui.components.IxTextField
import com.imayx.host.ui.theme.Ix
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign

/** Re-authentication gate for sensitive areas (credentials, Node secrets) when App Lock is enabled. */
class Gate {
    var pending by mutableStateOf<(() -> Unit)?>(null)

    fun request(lock: AppLock, action: () -> Unit) {
        if (lock.isSensitiveUnlocked()) action() else pending = action
    }
}

val LocalGate = staticCompositionLocalOf { Gate() }

@Composable
private fun PinEntry(
    lock: AppLock,
    onSuccess: () -> Unit,
    autoBiometric: Boolean,
    biometricTitle: String
) {
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val activity = ctx.findFragmentActivity()
    val canBio = lock.isBiometricEnabled() && activity != null && BiometricHelper.canAuthenticate(ctx)

    fun tryBiometric() {
        if (activity == null) return
        BiometricHelper.prompt(activity, biometricTitle, onSuccess = onSuccess, onFailure = { })
    }

    LaunchedEffect(Unit) {
        if (autoBiometric && canBio) tryBiometric()
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IxTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit).take(12); error = null },
            label = "PIN",
            keyboardType = KeyboardType.NumberPassword,
            isError = error != null,
            supportingText = error,
            visualTransformation = PasswordVisualTransformation()
        )
        Spacer(Modifier.height(12.dp))
        IxButton(
            "Unlock",
            onClick = {
                val blocked = lock.blockedSeconds()
                if (blocked > 0) {
                    error = "Too many attempts. Try again in ${blocked}s"
                } else if (lock.verifyPin(pin)) {
                    pin = ""
                    onSuccess()
                } else {
                    val b = lock.blockedSeconds()
                    error = if (b > 0) "Too many attempts. Try again in ${b}s" else "Wrong PIN"
                    pin = ""
                }
            },
            enabled = pin.length >= 4,
            modifier = Modifier.fillMaxWidth()
        )
        if (canBio) {
            Spacer(Modifier.height(8.dp))
            IxButton(
                "Use fingerprint / biometrics",
                onClick = { tryBiometric() },
                icon = Icons.Rounded.Fingerprint,
                kind = BtnKind.Secondary,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun LockScreen(lock: AppLock) {
    val c = Ix.c
    Box(
        modifier = Modifier.fillMaxSize().background(c.bg).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(76.dp).background(c.accent.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = c.accent, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text("IMAYX Host", color = c.text, style = MaterialTheme.typography.headlineMedium)
            Text("Locked", color = c.soft, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            PinEntry(lock, onSuccess = { lock.markUnlocked(); lock.markSensitiveUnlocked() }, autoBiometric = true, biometricTitle = "Unlock IMAYX Host")
        }
    }
}

@Composable
fun GateDialog(gate: Gate) {
    val lock = LocalContext.current.graph.appLock
    val action = gate.pending ?: return
    val c = Ix.c
    AlertDialog(
        onDismissRequest = { gate.pending = null },
        containerColor = c.card,
        titleContentColor = c.text,
        title = { Text("Authentication required") },
        text = {
            Column {
                Text("Enter your app PIN to open sensitive settings.", color = c.soft, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(14.dp))
                PinEntry(
                    lock,
                    onSuccess = {
                        lock.markSensitiveUnlocked()
                        gate.pending = null
                        action()
                    },
                    autoBiometric = false,
                    biometricTitle = "Authenticate"
                )
            }
        },
        confirmButton = {},
        dismissButton = { IxButton("Cancel", { gate.pending = null }, kind = BtnKind.Ghost) }
    )
}
