package org.bibletranslationtools.bttrecorder2.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.bibletranslationtools.shared.resources.Res
import org.bibletranslationtools.shared.resources.action_back
import org.bibletranslationtools.shared.resources.cd_close_search
import org.bibletranslationtools.shared.resources.cd_search
import org.bibletranslationtools.shared.resources.wizard_new_project_title
import org.bibletranslationtools.shared.resources.wizard_search_book
import org.bibletranslationtools.shared.resources.wizard_search_language
import org.bibletranslationtools.shared.resources.wizard_search_source
import org.jetbrains.compose.resources.stringResource
import org.bibletranslationtools.bttrecorder2.ui.screens.wizard.BookSelectionScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.wizard.SourceSelectionScreen
import org.bibletranslationtools.bttrecorder2.ui.screens.wizard.TargetLanguageSelectionScreen
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.ProjectCreationViewModel
import org.bibletranslationtools.bttrecorder2.ui.viewmodels.WizardStep

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectWizardScreen(
    viewModel: ProjectCreationViewModel,
    onBackClick: () -> Unit,
    onProjectCreated: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.isCreated) {
        onProjectCreated()
    }

    // Search state lives at the wizard level. Each step renders its own list so
    // we reset the query whenever the wizard step changes — searching for
    // "Genesis" while on the language step then advancing should NOT carry
    // that text over to the book step.
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }

    LaunchedEffect(uiState.currentStep) {
        isSearchActive = false
        searchQuery = ""
    }
    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            // Defer to the next frame so the TextField has been composed before
            // we ask it for focus.
            try { searchFocus.requestFocus() } catch (_: IllegalStateException) {}
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    if (isSearchActive) {
                        WizardSearchField(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            focusRequester = searchFocus,
                            placeholder = when (uiState.currentStep) {
                                WizardStep.SOURCE -> stringResource(Res.string.wizard_search_source)
                                WizardStep.TARGET_LANGUAGE -> stringResource(Res.string.wizard_search_language)
                                WizardStep.BOOK -> stringResource(Res.string.wizard_search_book)
                            }
                        )
                    } else {
                        Text(stringResource(Res.string.wizard_new_project_title))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            isSearchActive -> {
                                isSearchActive = false
                                searchQuery = ""
                            }
                            uiState.currentStep == WizardStep.SOURCE -> onBackClick()
                            else -> viewModel.navigateBack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back)
                        )
                    }
                },
                actions = {
                    // Search helps on every step now that the source list surfaces the
                    // bundled gateway sources (previously it was effectively a single row).
                    IconButton(onClick = {
                        if (isSearchActive) {
                            // Toggling off — drop the query so the list resets.
                            searchQuery = ""
                        }
                        isSearchActive = !isSearchActive
                    }) {
                        Icon(
                            imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (isSearchActive) {
                                stringResource(Res.string.cd_close_search)
                            } else {
                                stringResource(Res.string.cd_search)
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                when (uiState.currentStep) {
                    WizardStep.SOURCE -> {
                        SourceSelectionScreen(
                            sources = uiState.sources,
                            availableSources = uiState.availableSources,
                            searchQuery = searchQuery,
                            onSourceSelected = { viewModel.selectSource(it) },
                            onAvailableSourceSelected = { viewModel.selectAvailableSource(it) }
                        )
                    }
                    WizardStep.TARGET_LANGUAGE -> {
                        TargetLanguageSelectionScreen(
                            languages = uiState.targetLanguages,
                            searchQuery = searchQuery,
                            onLanguageSelected = { viewModel.selectTarget(it) }
                        )
                    }
                    WizardStep.BOOK -> {
                        BookSelectionScreen(
                            books = uiState.availableBooks,
                            searchQuery = searchQuery,
                            onBookSelected = { viewModel.selectBook(it) }
                        )
                    }
                }
            }

            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                )
            }
        }
    }
}

/**
 * The inline search field rendered inside the TopAppBar's title slot when
 * search is active. Uses transparent TextField colors so the field reads as
 * part of the bar instead of a separate input control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WizardSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    placeholder: String
) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    TextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = {
            Text(
                placeholder,
                color = onPrimary.copy(alpha = 0.65f)
            )
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            cursorColor = onPrimary,
            focusedTextColor = onPrimary,
            unfocusedTextColor = onPrimary
        )
    )
}
