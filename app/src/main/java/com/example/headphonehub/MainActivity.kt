package com.example.headphonehub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HeadphoneService.start(this)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HeadphoneHubApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeadphoneHubApp() {
    val context = LocalContext.current
    val settings = remember { Settings(context) }

    val playerState by PlayerBus.state.collectAsState()
    val headphoneName by PlayerBus.headphone.collectAsState()

    var singleClickAction by remember { mutableStateOf(settings.actionFor(ClickType.SINGLE)) }
    var doubleClickAction by remember { mutableStateOf(settings.actionFor(ClickType.DOUBLE)) }
    var voiceAlerts by remember { mutableStateOf(settings.voiceAlertEnabled) }
    var listenerOn by remember { mutableStateOf(settings.listenerEnabled) }

    var hasAudioPermission by remember { mutableStateOf(MediaScanner.hasPermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasAudioPermission = isGranted
    }

    LaunchedEffect(hasAudioPermission) {
        if (hasAudioPermission) {
            val songs = withContext(Dispatchers.IO) {
                MediaScanner.scan(context)
            }
            PlayerBus.state.value = PlayerBus.state.value.copy(queue = songs)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Headphone Hub", fontWeight = FontWeight.Bold) }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (headphoneName != null) 
                            MaterialTheme.colorScheme.primaryContainer 
                        else 
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Status",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = headphoneName ?: "No headphones connected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            item {
                Text(
                    text = "Button Remapping",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                ActionDropdown(
                    label = "Single Click",
                    selectedAction = singleClickAction,
                    onActionSelected = {
                        singleClickAction = it
                        settings.setAction(ClickType.SINGLE, it)
                    }
                )
            }

            item {
                ActionDropdown(
                    label = "Double Click",
                    selectedAction = doubleClickAction,
                    onActionSelected = {
                        doubleClickAction = it
                        settings.setAction(ClickType.DOUBLE, it)
                    }
                )
            }

            item {
                Text(
                    text = "Preferences",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable Listener Service", fontWeight = FontWeight.Medium)
                        Text(
                            "Listen for headphone connections in the background",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = listenerOn,
                        onCheckedChange = {
                            listenerOn = it
                            settings.listenerEnabled = it
                            if (it) HeadphoneService.start(context) else HeadphoneService.stop(context)
                        }
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Voice Alert", fontWeight = FontWeight.Medium)
                        Text(
                            "Say \"Ready to use\" when connected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = voiceAlerts,
                        onCheckedChange = {
                            voiceAlerts = it
                            settings.voiceAlertEnabled = it
                        }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Local Library",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (!hasAudioPermission) {
                item {
                    Button(
                        onClick = { permissionLauncher.launch(MediaScanner.audioPermission) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Grant Storage/Audio Permission")
                    }
                }
            } else if (playerState.queue.isEmpty()) {
                item {
                    Text(
                        "No music found on device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                itemsIndexed(playerState.queue) { index, song ->
                    val isCurrent = index == playerState.currentIndex
                    SongItem(
                        song = song,
                        isCurrent = isCurrent,
                        isPlaying = isCurrent && playerState.isPlaying,
                        onClick = { HeadphoneService.send(context, HeadphoneService.ACTION_PLAY_INDEX, index) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionDropdown(
    label: String,
    selectedAction: HeadphoneAction,
    onActionSelected: (HeadphoneAction) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selectedAction.label,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            HeadphoneAction.entries.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    onClick = {
                        onActionSelected(action)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun SongItem(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) 
                MaterialTheme.colorScheme.secondaryContainer 
            else 
                MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            if (isCurrent) {
                Text(
                    text = if (isPlaying) "Playing" else "Paused",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
