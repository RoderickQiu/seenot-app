package com.roderickqiu.seenot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roderickqiu.seenot.data.MonitoringAppRepository
import com.roderickqiu.seenot.data.Rule
import com.roderickqiu.seenot.ui.theme.SeeNotTheme
import com.roderickqiu.seenot.ui.theme.YellowGrey40
import com.roderickqiu.seenot.ui.theme.YellowGrey80
import com.roderickqiu.seenot.utils.RuleFormatter

data class MonitoringApp(
    val name: String,
    val isEnabled: Boolean = true,
    val rules: List<Rule>
)


class MainActivity : ComponentActivity() {
    private val repository = MonitoringAppRepository()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SeeNotTheme {
                val context = LocalContext.current
                val monitoringApps = repository.getAllApps()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = {
                                Text("SeeNot")
                            }
                        )
                    },
                    floatingActionButton = {
                        FloatingActionButton(
                            onClick = { /* Handle FAB click */ }
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = context.getString(R.string.add_app)
                            )
                        }
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = context.getString(R.string.monitoring_software_list),
                            fontSize = 16.sp,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 80.dp)
                        ) {
                            items(monitoringApps) { app ->
                                MonitoringAppItem(app = app)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MonitoringAppItem(app: MonitoringApp) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val isDarkTheme = isSystemInDarkTheme()
    var showMenu by remember { mutableStateOf(false) }

    // Unified color scheme
    val iconColor = if (app.isEnabled) {
        if (isDarkTheme) YellowGrey80 else YellowGrey40
    } else {
        colorScheme.outline.copy(alpha = 0.6f) // Gray color for disabled state
    }

    val textColor = if (app.isEnabled) {
        Color.White
    } else {
        colorScheme.onSurface.copy(alpha = 0.6f)
    }

    // Auto-generate icon (take first character of app name)
    val icon = app.name.take(1)

    // Auto-add status suffix
    val displayName = if (!app.isEnabled) {
        "${app.name} ${context.getString(R.string.status_disabled)}"
    } else {
        app.name
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(iconColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = icon,
                    color = textColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // App info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = displayName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (app.isEnabled) colorScheme.onSurface else colorScheme.onSurface.copy(
                        alpha = 0.6f
                    )
                )

                Spacer(modifier = Modifier.height(4.dp))

                app.rules.forEach { rule ->
                    Text(
                        text = RuleFormatter.formatRule(context, rule),
                        fontSize = 14.sp,
                        color = colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 2, // Allow more lines to display
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // More options dropdown menu
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = context.getString(R.string.more_options),
                        tint = colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(context.getString(R.string.edit)) },
                        onClick = {
                            showMenu = false
                            // TODO: Handle edit action
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(context.getString(R.string.delete)) },
                        onClick = {
                            showMenu = false
                            // TODO: Handle delete action
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null
                            )
                        }
                    )
                }
            }
        }
    }
}