package it.silleellie.dndsync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumePauseEffectScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(context: Context) {
    val viewModel = viewModel<MainViewModel>()
    val dndAsBedtime by viewModel.dndAsBedtime.collectAsState()
    val bedtimeSync by viewModel.bedtimeSync.collectAsState()
    val powerSaveEnabled by viewModel.powerSaveEnabled.collectAsState()
    val dndPermissionGranted by viewModel.dndPermissionGranted.collectAsState()
    val notificationState by viewModel.notificationState.collectAsState()
    val dndSync by viewModel.dndSync.collectAsState()
    val permissionsGranted = dndPermissionGranted && notificationState
    var isDialogOpen by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val state by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(state){
        viewModel.initiateStates()
    }

    if (isDialogOpen) {
        AlertDialog(
            icon = {
                Icon(Icons.Rounded.Notifications, contentDescription = "Notifications")
            },
            title = {
                Text(text = stringResource(R.string.notifications_permission_title), textAlign = TextAlign.Center)
            },
            text = {
                Text(text = stringResource(R.string.about_notification_permission_desc), textAlign = TextAlign.Center)
            },
            onDismissRequest = {
                isDialogOpen = false
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isDialogOpen = false
                        viewModel.requestNotificationPermission()
                    }
                ) {
                    Text(
                        stringResource(
                            android.R.string.ok
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        isDialogOpen = false
                    }
                ) {
                    Text(
                        stringResource(
                            android.R.string.cancel
                        )
                    )
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    Row(
                        modifier = Modifier.padding(end = 10.dp),
                    ){
                        Icon(
                            painterResource(R.drawable.github),
                            tint = MaterialTheme.colorScheme.onSurface,
                            contentDescription = "Github",
                            modifier = Modifier.clip(CircleShape).clickable {
                                val url = "https://github.com/Silleellie/dnd-bedtime-sync"
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }.padding(4.dp).size(25.dp)
                        )
                    }
                }
            )
        },
        content = { padding ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                CategoryTitle(R.string.phone_sync_header)
                // DND as Bedtime Switch
                ConfigurationItem(
                    leadingText = R.string.sync_dnd_title,
                    supportingText = R.string.sync_dnd_desc,
                    icon = {
                        Icon(
                            painterResource(R.drawable.do_not_disturb_on),
                            contentDescription = "Do Not Disturb"
                        )
                    },
                    checked = dndSync,
                    onCheckedChange = { viewModel.setDndSync(it) },
                    enabled = permissionsGranted
                )

                ConfigurationItem(
                    leadingText = R.string.sync_dnd_as_bedtime_title,
                    supportingText = R.string.sync_dnd_as_bedtime_desc,
                    icon = {
                        Icon(
                            painterResource(R.drawable.rule_settings),
                            contentDescription = "Bedtime"
                        )
                    },
                    checked = dndAsBedtime,
                    onCheckedChange = { viewModel.setDndAsBedtime(it) },
                    enabled = dndSync && permissionsGranted,
                    child = true
                )

                // Bedtime Sync Switch
                ConfigurationItem(
                    leadingText = R.string.sync_bedtime_title,
                    supportingText = R.string.sync_bedtime_desc,
                    icon = {
                        Icon(painterResource(R.drawable.bedtime), contentDescription = "Bedtime")
                    },
                    checked = bedtimeSync,
                    onCheckedChange = {
                        viewModel.setBedtimeSync(it)
                    },
                    enabled = permissionsGranted
                )

                // ConfigurationItem Save Toggle (Read-Only)
                ConfigurationItem(
                    leadingText = R.string.enable_power_saving_title,
                    supportingText = R.string.enable_power_saving_desc,
                    icon = {
                        Icon(
                            painterResource(R.drawable.battery_saver),
                            contentDescription = "Power Saving"
                        )
                    },
                    checked = powerSaveEnabled,
                    onCheckedChange = { viewModel.setPowerSaveState(it) },
                    enabled = bedtimeSync && permissionsGranted,
                    child = true
                )

                CategoryTitle(R.string.watch_sync_header)

                // Watch Sync Switch
                ConfigurationItem(
                    leadingText = R.string.watch_sync_dnd_title,
                    supportingText = R.string.watch_sync_dnd_desc,
                    icon = {
                        Icon(
                            painterResource(R.drawable.do_not_disturb_on),
                            contentDescription = "Do Not Disturb"
                        )
                    },
                    checked = viewModel.watchSync.collectAsState().value,
                    onCheckedChange = {
                        viewModel.setWatchSync(it)
                    },
                    enabled = permissionsGranted
                )

                // Watch Vibrate Switch
                ConfigurationItem(
                    leadingText = R.string.watch_vibrate_title,
                    supportingText = R.string.watch_vibrate_desc,
                    icon = {
                        Icon(
                            painterResource(R.drawable.watch_vibration),
                            contentDescription = "Vibration"
                        )
                    },
                    checked = viewModel.watchVibrate.collectAsState().value,
                    onCheckedChange = {
                        viewModel.setWatchVibrate(it)
                    }
                )

                // DND Permission Setting
                CategoryTitle(R.string.permission_header)
                // No longer needed as notification permissions automatically enables this
                /*ConfigurationItem(
                    icon = {
                        Icon(Icons.Rounded.Lock, contentDescription = "Lock")
                    },
                    leadingText = R.string.dnd_permission_title,
                    supportingText = if (dndPermissionGranted) R.string.dnd_permission_allowed
                    else R.string.dnd_permission_not_allowed,
                    onCheckedChange = {
                        viewModel.requestDNDPermission()
                    },
                    enabled = !dndPermissionGranted,
                    checked = dndPermissionGranted
                )*/
                ConfigurationItem(
                    icon = {
                        Icon(Icons.Rounded.Notifications, contentDescription = "Notifications")
                    },
                    leadingText = R.string.notifications_permission_title,
                    supportingText = if (notificationState) R.string.notifications_permission_allowed
                    else R.string.notifications_permission_not_allowed,
                    onCheckedChange = {
                        isDialogOpen = true
                    },
                    enabled = !notificationState,
                    checked = notificationState
                )
            }
        }
    )
}

@Composable
fun ConfigurationItem(
    checked: Boolean? = null,
    onCheckedChange: (Boolean) -> Unit,
    leadingText: Int,
    supportingText: Int? = null,
    icon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    child: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    var modifier = Modifier
        .then(
            if (child) Modifier
                .padding(start = 20.dp, end = 15.dp)
                .clip(MaterialTheme.shapes.medium) else Modifier
        )
        .clickable(
            enabled = enabled,
            onClick = {
                onCheckedChange(checked != true)
            },
            interactionSource = interactionSource,
            indication = ripple()
        )
        .padding(vertical = 4.dp)

    ListItem(
        headlineContent = { Text(stringResource(leadingText)) },
        supportingContent = {
            if (supportingText != null) {
                Text(stringResource(supportingText))
            }
        },
        trailingContent = {
            if (checked != null) {
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    enabled = enabled,
                    interactionSource = interactionSource
                )
            }
        },
        leadingContent = {
            if (icon != null) {
                icon()
            }
        },
        modifier = modifier
    )
}

@Composable
fun CategoryTitle(title: Int) {
    Text(
        stringResource(title), style = MaterialTheme.typography.labelLarge.copy(
            color = MaterialTheme.colorScheme.primary
        ),
        textAlign = TextAlign.Start,
        modifier = Modifier
            .padding(vertical = 8.dp, horizontal = 16.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
    )
}
