package com.example.gemini.ui.ide

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class ProjectTemplate(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector
)

val PROJECT_TEMPLATES = listOf(
    ProjectTemplate("python", "Python Project", "Main.py with starter code", Icons.Default.Code),
    ProjectTemplate("node", "Node.js Project", "Index.js & package.json", Icons.Default.Javascript),
    ProjectTemplate("web", "Web App (HTML/CSS/JS)", "Index.html, style.css, app.js", Icons.Default.Html),
    ProjectTemplate("cpp", "C++ Project", "Main.cpp with iostream", Icons.Default.Terminal),
    ProjectTemplate("blank", "Blank Project", "Empty directory with README.md", Icons.Default.FolderOpen)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewProjectDialog(
    onDismiss: () -> Unit,
    onCreateProject: (name: String, template: String) -> Unit
) {
    var projectName by remember { mutableStateOf("") }
    var selectedTemplate by remember { mutableStateOf("python") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Create New Project", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = projectName,
                    onValueChange = { projectName = it },
                    label = { Text("Project Name") },
                    placeholder = { Text("e.g. my-awesome-app") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "Choose Template",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                PROJECT_TEMPLATES.forEach { template ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedTemplate = template.id },
                        shape = RoundedCornerShape(8.dp),
                        color = if (selectedTemplate == template.id) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                        tonalElevation = if (selectedTemplate == template.id) 4.dp else 0.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = template.icon,
                                contentDescription = null,
                                tint = if (selectedTemplate == template.id) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = template.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (selectedTemplate == template.id) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                Text(
                                    text = template.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (selectedTemplate == template.id) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (projectName.isNotBlank()) {
                        onCreateProject(projectName.trim(), selectedTemplate)
                    }
                },
                enabled = projectName.isNotBlank()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
