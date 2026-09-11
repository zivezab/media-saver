package com.mediasaver

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaverScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val jobs by DownloadRepository.jobs.collectAsState()
    val init by Extractor.init.collectAsState()
    val saved by DownloadHistory.items.collectAsState()
    val signedIn by CookieStore.signedIn.collectAsState()
    val settings by Settings.state.collectAsState()

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    var showAccounts by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SavedItem?>(null) }
    var confirmDedupe by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<SavedItem?>(null) }
    var query by remember { mutableStateOf("") }

    // While a lookup is running the field is read-only, so the URL cannot change
    // underneath the request that is already in flight.
    val busy = state.looking
    val ready = init is Extractor.Init.Ready

    // A play request from a "tap to play" notification, held in the ViewModel
    // so a rebuilt Activity does not replay it.
    LaunchedEffect(state.playRequest) {
        state.playRequest?.let {
            playing = it
            viewModel.playRequestHandled()
        }
    }

    val visible = remember(saved, query, settings) {
        saved.filter { DownloadHistory.matches(it, query) }
            .let { list ->
                val sorted = when (settings.sortBy) {
                    Settings.SortBy.DATE -> list.sortedBy { it.savedAt }
                    Settings.SortBy.SIZE -> list.sortedBy { it.sizeBytes }
                    Settings.SortBy.NAME -> list.sortedBy { it.displayName.lowercase() }
                }
                if (settings.sortDescending) sorted.reversed() else sorted
            }
    }
    val duplicateGroups = remember(saved) { DownloadHistory.duplicateGroups(saved) }
    val duplicateCount = duplicateGroups.sumOf { it.size - 1 }

    val importCookies = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val result = runCatching { CookieStore.importFrom(context, uri) }.getOrNull()
            Toast.makeText(context, importMessage(result), Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Media Saver", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { showAccounts = true }) {
                        Icon(
                            Icons.Filled.AccountCircle,
                            contentDescription = "Site sign-ins",
                            tint = if (signedIn.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.url,
                    onValueChange = viewModel::onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Paste a link to a post or video") },
                    singleLine = true,
                    readOnly = busy,
                    enabled = !busy,
                    supportingText = if (busy) {
                        { Text("Reading the link - the box unlocks when it finishes") }
                    } else null,
                    trailingIcon = {
                        if (state.url.isNotEmpty() && !busy) {
                            IconButton(onClick = viewModel::clear) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onGo = { keyboard?.hide(); viewModel.probe() },
                    ),
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            val text = clipboard.getText()?.text
                            val url = Extractor.extractUrl(text)
                            if (url != null) viewModel.submitSharedUrl(url)
                            else viewModel.onUrlChange(text.orEmpty())
                        },
                        enabled = !busy,
                    ) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Paste")
                    }
                    Button(
                        onClick = { keyboard?.hide(); viewModel.probe() },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Reading link")
                        } else {
                            Text("Find media")
                        }
                    }
                }
            }

            when (val i = init) {
                is Extractor.Init.Loading ->
                    item { Banner("Getting the downloader ready. This takes a few seconds the first time.") }
                is Extractor.Init.Updating ->
                    item { Banner("Updating the downloader so sites keep working. Just a moment.") }
                is Extractor.Init.Failed -> item { Banner(i.message, error = true) }
                else -> Unit
            }

            state.notice?.let { item { Banner(it) } }
            state.error?.let { item { Banner(it, error = true) } }

            state.info?.let { info ->
                info.warning?.let { item { Banner(it) } }
                item { MediaCard(info) }
                items(info.options) { option ->
                    OptionRow(option) {
                        DownloadService.enqueue(
                            context = context,
                            url = info.url,
                            selector = option.selector,
                            kind = option.kind,
                            label = "${info.title} - ${option.title}",
                        )
                    }
                }
                if (info.allFormats.size > 1) {
                    item {
                        TextButton(onClick = viewModel::toggleAllFormats) {
                            Text(if (state.showAllFormats) "Hide formats" else "Show all formats")
                        }
                    }
                }
                if (state.showAllFormats) {
                    items(info.allFormats) { row ->
                        OptionRow(
                            Formats.Option(
                                selector = row.formatId,
                                title = row.label,
                                subtitle = listOfNotNull(
                                    row.ext.uppercase().ifEmpty { null },
                                    row.sizeHuman,
                                    row.note.ifEmpty { null },
                                ).joinToString(" · "),
                                kind = row.kind,
                            )
                        ) {
                            DownloadService.enqueue(
                                context = context,
                                url = info.url,
                                selector = row.formatId,
                                kind = row.kind,
                                label = "${info.title} - ${row.label}",
                            )
                        }
                    }
                }
            }

            if (jobs.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Downloads", style = MaterialTheme.typography.titleSmall)
                        if (jobs.any { !it.active }) {
                            TextButton(onClick = DownloadRepository::clearFinished) { Text("Clear finished") }
                        }
                    }
                }
                items(jobs, key = { it.id }) { job ->
                    JobCard(job) { uri ->
                        playing = saved.firstOrNull { it.uri == uri }
                            ?: SavedItem(
                                id = job.id,
                                title = job.label,
                                displayName = job.savedAs ?: job.label,
                                uri = uri,
                                mimeType = job.mimeType ?: "video/*",
                                location = job.savedLocation.orEmpty(),
                                sizeBytes = 0,
                                savedAt = System.currentTimeMillis(),
                            )
                    }
                }
            }

            librarySection(
                entries = visible,
                settings = settings,
                query = query,
                onQueryChange = { query = it },
                duplicateCount = duplicateCount,
                onDedupe = { confirmDedupe = true },
                onSort = { choice ->
                    Settings.update(context) {
                        if (it.sortBy == choice) it.copy(sortDescending = !it.sortDescending)
                        else it.copy(sortBy = choice)
                    }
                },
                onToggleLayout = {
                    Settings.update(context) {
                        it.copy(
                            layout = if (it.layout == Settings.Layout.GRID) Settings.Layout.LIST
                            else Settings.Layout.GRID
                        )
                    }
                },
                onPlay = { playing = it },
                onShare = { SavedMedia.share(context, it.uri, it.mimeType) },
                onDelete = { pendingDelete = it },
            )

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showSettings) {
        SettingsDialog(
            settings = settings,
            onChange = { transform -> Settings.update(context, transform) },
            onDismiss = { showSettings = false },
        )
    }

    if (showAccounts) {
        CookiesDialog(
            signedIn = signedIn,
            onPasteSave = { text, site ->
                val lines = CookieStore.parseCookies(text, site)
                val found = CookieStore.sitesIn(lines)
                if (lines.isEmpty()) {
                    Toast.makeText(
                        context,
                        "That does not look like cookies. Paste a cookies.txt export, or a " +
                            "\"name=value; ...\" string.",
                        Toast.LENGTH_LONG,
                    ).show()
                } else if (found.isEmpty()) {
                    // Refuse rather than save something that cannot work.
                    Toast.makeText(
                        context,
                        "No login found in that text - it has no session cookie for X, " +
                            "Instagram, Reddit or Vimeo. Sign in to the site first, then " +
                            "export again. Nothing was saved.",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    val result = CookieStore.saveLines(context, lines)
                    showAccounts = false
                    Toast.makeText(context, importMessage(result), Toast.LENGTH_LONG).show()
                }
            },
            onImportFile = {
                showAccounts = false
                importCookies.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
            },
            onSignOut = {
                CookieStore.signOut(context)
                showAccounts = false
            },
            onDismiss = { showAccounts = false },
        )
    }

    playing?.let { item ->
        if (item.mimeType.startsWith("image/")) {
            ImageViewer(
                item = item,
                onOpenExternally = {
                    SavedMedia.open(context, item.uri, item.mimeType)
                    playing = null
                },
                onDismiss = { playing = null },
            )
            return@let
        }
        PlayerSheet(
            item = item,
            onOpenExternally = {
                SavedMedia.open(context, item.uri, item.mimeType)
                playing = null
            },
            onDismiss = { playing = null },
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this file?") },
            text = {
                Text(
                    "${item.displayName}\n\nThis removes it from ${item.location} on the " +
                        "phone, not just from this list. It cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = DownloadHistory.delete(context, item.id)
                    pendingDelete = null
                    Toast.makeText(
                        context,
                        if (ok) "Deleted" else "Could not delete that file; removed it from the list.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }

    if (confirmDedupe) {
        val removable = duplicateGroups.flatMap { it.drop(1) }
        AlertDialog(
            onDismissRequest = { confirmDedupe = false },
            title = { Text("Remove duplicates?") },
            text = {
                Text(
                    (if (removable.size == 1)
                        "1 file looks like a repeat of something you already have"
                    else
                        "${removable.size} files look like repeats of things you already have") +
                        " - same size and name. The newest copy of each is kept; the rest " +
                        "are deleted from the phone. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val removed = DownloadHistory.deleteAll(context, removable.map { it.id })
                    confirmDedupe = false
                    Toast.makeText(context, "Removed $removed duplicates", Toast.LENGTH_SHORT).show()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDedupe = false }) { Text("Cancel") } },
        )
    }
}

private fun importMessage(result: CookieStore.ImportResult?): String = when {
    result == null ->
        "That file is not a cookies.txt. Export it again with a \"Get cookies.txt\" extension."
    result.sites.isEmpty() ->
        "Imported ${result.cookies} cookies, but none is a login for X, Instagram, Reddit " +
            "or Vimeo. Sign in to the site in your browser first, then export again."
    else -> {
        val names = CookieStore.SITES.filter { it.key in result.sites }.joinToString { it.label }
        "Imported ${result.cookies} cookies. Signed in to $names."
    }
}

@Composable
private fun Banner(text: String, error: Boolean = false) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MediaCard(info: MediaInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .size(width = 108.dp, height = 72.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (info.thumbnail != null) {
                    AsyncImage(
                        model = info.thumbnail,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Icon(Icons.Filled.Movie, contentDescription = null)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    info.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (info.uploader.isNotBlank()) {
                    Text(
                        info.uploader,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val bits = listOfNotNull(
                    info.source.ifBlank { null },
                    Formats.humanDuration(info.durationSec).ifBlank { null },
                )
                if (bits.isNotEmpty()) {
                    Text(
                        bits.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRow(option: Formats.Option, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (option.recommended) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else CardDefaults.cardColors(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(option.title, style = MaterialTheme.typography.titleSmall)
                if (option.subtitle.isNotBlank()) {
                    Text(
                        option.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(Icons.Filled.Download, contentDescription = null)
        }
    }
}

@Composable
private fun JobCard(job: DownloadJob, onPlay: (String) -> Unit) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    job.label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    when (job.status) {
                        DownloadJob.Status.DONE -> "Done"
                        DownloadJob.Status.FAILED -> "Failed"
                        DownloadJob.Status.CANCELLED -> "Stopped"
                        else -> "${(job.progress * 100).toInt()}%"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Spacer(Modifier.height(8.dp))

            if (job.status == DownloadJob.Status.SAVING || (job.active && job.progress <= 0f)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(8.dp))

            val message = when (job.status) {
                DownloadJob.Status.DONE -> listOfNotNull(job.savedAs, job.savedLocation).joinToString(" · ")
                DownloadJob.Status.FAILED -> job.error ?: "Failed"
                else -> job.detail
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (job.status == DownloadJob.Status.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (job.active) {
                    TextButton(onClick = { DownloadRepository.cancel(job.id) }) { Text("Cancel") }
                }
            }

            val uri = job.savedUri
            if (job.status == DownloadJob.Status.DONE && uri != null) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onPlay(uri) },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (job.mimeType?.startsWith("image/") == true) "View" else "Play") }
                    OutlinedButton(onClick = { SavedMedia.share(context, uri, job.mimeType) }) {
                        Text("Share")
                    }
                }
            }
        }
    }
}
