package io.qent.bro.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.qent.bro.ui.viewmodels.AppState
import io.qent.bro.ui.viewmodels.UiPreset
import io.qent.bro.ui.viewmodels.UiServer

@Composable
fun AddServerDialog(state: AppState) {
    var name by remember { mutableStateOf(TextFieldValue("")) }
    var id by remember { mutableStateOf(TextFieldValue("")) }

    AlertDialog(
        onDismissRequest = { state.showAddServerDialog.value = false },
        title = { Text("Add server") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("ID") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.text.isNotBlank() && id.text.isNotBlank()) {
                    state.servers.add(UiServer(id = id.text.trim(), name = name.text.trim(), isConnected = false))
                    state.showAddServerDialog.value = false
                }
            }) { Text("Add") }
        },
        dismissButton = {
            Button(onClick = { state.showAddServerDialog.value = false }) { Text("Cancel") }
        }
    )
}

@Composable
fun AddPresetDialog(state: AppState) {
    var name by remember { mutableStateOf(TextFieldValue("")) }
    var description by remember { mutableStateOf(TextFieldValue("")) }

    AlertDialog(
        onDismissRequest = { state.showAddPresetDialog.value = false },
        title = { Text("Add preset") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.text.isNotBlank()) {
                    state.presets.add(UiPreset(id = name.text.trim().lowercase().replace(" ", "-"), name = name.text.trim(), description = description.text.ifBlank { null }))
                    state.showAddPresetDialog.value = false
                }
            }) { Text("Add") }
        },
        dismissButton = {
            Button(onClick = { state.showAddPresetDialog.value = false }) { Text("Cancel") }
        }
    )
}

