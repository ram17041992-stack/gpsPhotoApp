package com.gpsphoto.app.camera

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StampSettingsSheet(s: StampSettings, update: ((StampSettings) -> StampSettings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
        ) {
            Text("Photo stamp", style = MaterialTheme.typography.titleLarge)

            Section("Template")
            Choices(Template.entries, s.template, { it.label }) { v -> update { it.copy(template = v) } }
            if (s.template == Template.ADVANCED) {
                Section("Map style")
                Choices(MapType.entries, s.mapType, { it.label }) { v -> update { it.copy(mapType = v) } }
            }
            Section("Coordinates")
            Choices(CoordFormat.entries, s.coordFormat, { it.label }) { v -> update { it.copy(coordFormat = v) } }

            Section("Show on photo")
            Toggle("Address", s.showAddress) { v -> update { it.copy(showAddress = v) } }
            Toggle("Latitude & longitude", s.showCoords) { v -> update { it.copy(showCoords = v) } }
            Toggle("Date & time", s.showDateTime) { v -> update { it.copy(showDateTime = v) } }
            Toggle("Altitude & accuracy", s.showAltitude) { v -> update { it.copy(showAltitude = v) } }
            Toggle("Weather", s.showWeather) { v -> update { it.copy(showWeather = v) } }

            OutlinedTextField(
                value = s.note,
                onValueChange = { v -> update { it.copy(note = v.take(120)) } },
                label = { Text("Note (e.g. project or site name)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )

            Section("Saving")
            Toggle("Also save the photo without stamp", s.keepOriginal) { v -> update { it.copy(keepOriginal = v) } }

            Text(
                "Address, map and weather need internet. GPS and time work offline. " +
                    "Maps © OpenStreetMap contributors; satellite imagery © Esri; weather by Open-Meteo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun <T> Choices(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onPick(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
