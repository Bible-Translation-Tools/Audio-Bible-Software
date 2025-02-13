package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.*
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.TopAppBarDefaults
import org.bibletranslationtools.bttrecorder2.ui.components.ProjectCard

// Sample data class (replace with your actual data)
data class Project(val language: String, val book: String, val progress: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectManagementScreen(
    onNewProjectClick: () -> Unit,
    onProjectClick: (Project) -> Unit,
    projects: List<Project> // Pass the project data here
) {

    //val context = LocalContext.current
    val toolbarColor = MaterialTheme.colorScheme.primary // Example color
    val backgroundColor = MaterialTheme.colorScheme.background // Example color
    val fabColor = MaterialTheme.colorScheme.secondary // Example color
    val textColor = MaterialTheme.colorScheme.onPrimary // Example color
    val buttonColor = MaterialTheme.colorScheme.primary
    val buttonTextColor = MaterialTheme.colorScheme.onPrimary

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Project Management",
                        color = textColor
                    )
                }, // Replace with your title
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = toolbarColor
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewProjectClick,
                containerColor = fabColor,
                contentColor = Color.White
            ) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = "New Project")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(backgroundColor)
        ) {

            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                // Language Sort
                Row(
                    modifier = Modifier.weight(0.4f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.RecordVoiceOver,
                        contentDescription = "Language Sort",
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Language")
                }

                // Book Sort
                Row(
                    modifier = Modifier.weight(0.3f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.LibraryBooks,
                        contentDescription = "Book Sort",
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Book")
                }

                // Progress Sort (replace with your icon)
                Row(
                    modifier = Modifier.weight(0.3f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Book,
                        contentDescription = "Progress Sort",
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Progress")
                }
            }

            // Project List (using LazyColumn for better performance)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(projects) { project ->
                    ProjectCard(project, onProjectClick, {}, {})
                }
            }
        }
    }
}