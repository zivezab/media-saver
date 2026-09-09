package com.mediasaver

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Intent
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
fun SaverScreen(
    viewModel: MainViewModel,
    consumeSharedUrl: () -> String?,
) {
    val state by viewModel.state.collectAsState()
    val jobs by DownloadRepository.jobs.collectAsState()
    val saved by DownloadHistory.items.collectAsState()
    val signedIn by CookieStore.signedIn.collectAsState()
    var showAccounts by remember { mutableStateOf(false) }
    val init by Extractor.init.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // A link shared from another app is looked up as soon as the extractor is
    // ready, so the user does not have to press anything.
    LaunchedEffect(init) {
        if (init is Extractor.Init.Ready) {
            consumeSharedUrl()?.let { viewModel.submitSharedUrl(it) }
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
                    trailingIcon = {
                        if (state.url.isNotEmpty()) {
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
                    OutlinedButton(onClick = {
                        val text = clipboard.getText()?.text
                        val url = Extractor.extractUrl(text)
                        if (url != null) viewModel.submitSharedUrl(url)
                        else viewModel.onUrlChange(text.orEmpty())
                    }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Paste")
                    }
                    Button(
                        onClick = { keyboard?.hide(); viewModel.probe() },
                        enabled = !state.looking && init is Extractor.Init.Ready,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (state.looking) {
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

            state.error?.let { item { Banner(it, error = true) } }

            state.info?.let { info ->
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
                items(jobs, key = { it.id }) { job -> JobCard(job) }
            }

            if (saved.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Saved on this phone", style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { DownloadHistory.clear(context) }) { Text("Clear list") }
                    }
                }
                items(saved, key = { it.id }) { item -> SavedRow(item) }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    val importCookies = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val count = runCatching { CookieStore.importFrom(context, uri) }.getOrNull()
            Toast.makeText(
                context,
                if (count != null) "Imported $count cookies"
                else "That file is not in Netscape cookies.txt format.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    if (showAccounts) {
        AccountsDialog(
            signedIn = signedIn,
            onImport = {
                showAccounts = false
                importCookies.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
            },
            onDismiss = { showAccounts = false },
            onSignIn = { site ->
                showAccounts = false
                context.startActivity(
                    Intent(context, LoginActivity::class.java)
                        .putExtra(LoginActivity.EXTRA_SITE, site.key)
                )
            },
            onSignOut = {
                CookieStore.signOut(context)
                showAccounts = false
            },
        )
    }
}

/**
 * Signing in is what makes login-walled and sensitive posts readable: those are
 * gated on having an account, not on anything the downloader can work around.
 */
@Composable
private fun AccountsDialog(
    signedIn: Set<String>,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
    onSignIn: (CookieStore.Site) -> Unit,
    onSignOut: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Site sign-ins") },
        text = {
            Column {
                Text(
                    "X hides posts marked sensitive from signed-out apps, and Vimeo " +
                        "and Reddit hide everything. Giving the downloader your own " +
                        "session makes those readable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(14.dp))
                Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                    Text("Import a cookies.txt file")
                }
                Text(
                    "On a computer, sign in to the site in your browser, export " +
                        "cookies.txt with a \"Get cookies.txt\" extension, copy it to " +
                        "this phone, and pick it here.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (signedIn.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Signed in: " + CookieStore.SITES
                            .filter { it.key in signedIn }
                            .joinToString { it.label },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Stored only on this phone, in the app's private storage. It is " +
                            "a live session, so sign out if you hand the phone on.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text("Or try signing in here", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Most large sites refuse to show a login inside another app, as a " +
                        "phishing defence - X, Instagram, Reddit and Vimeo all currently " +
                        "render a blank page. Worth a try for other sites.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                CookieStore.SITES.forEach { site ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            site.label,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onSignIn(site) }) { Text("Open") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            if (signedIn.isNotEmpty()) {
                TextButton(onClick = onSignOut) { Text("Sign out of all") }
            }
        },
    )
}

@Composable
private fun SavedRow(item: SavedItem) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (item.isAudio) Icons.Filled.Audiotrack else Icons.Filled.Movie,
                    contentDescription = null,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(Formats.humanSize(item.sizeBytes), item.location)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { DownloadHistory.remove(context, item.id) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Remove from list",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            PlayShareRow(
                onPlay = { SavedMedia.open(context, item.uri, item.mimeType) },
                onShare = { SavedMedia.share(context, item.uri, item.mimeType) },
            )
        }
    }
}

@Composable
private fun PlayShareRow(onPlay: () -> Unit, onShare: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onPlay, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Play")
        }
        OutlinedButton(onClick = onShare) {
            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Share")
        }
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
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
private fun JobCard(job: DownloadJob) {
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
                LinearProgressIndicator(
                    progress = { job.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(8.dp))

            val message = when (job.status) {
                DownloadJob.Status.DONE ->
                    listOfNotNull(job.savedAs, job.savedLocation).joinToString(" · ")
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
                    maxLines = 2,
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
                PlayShareRow(
                    onPlay = { SavedMedia.open(context, uri, job.mimeType) },
                    onShare = { SavedMedia.share(context, uri, job.mimeType) },
                )
            }
        }
    }
}
