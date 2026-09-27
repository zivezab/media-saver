package com.mediasaver

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import java.text.DateFormat
import java.util.Date

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
    // The viewer holds the list it was opened from, frozen at that moment, so
    // swiping moves through exactly what was on screen - and a later re-sort
    // does not shuffle the deck underfoot.
    var viewerList by remember { mutableStateOf<List<SavedItem>>(emptyList()) }
    var viewerIndex by remember { mutableStateOf(-1) }

    // Which items of a multi-item post are ticked. Everything starts ticked, so
    // the common case - take it all - needs no extra taps.
    val selectedItems = remember(state.info) {
        mutableStateListOf<Int>().apply {
            state.info?.galleryItems?.map { it.position }?.let { addAll(it) }
        }
    }
    var query by remember { mutableStateOf("") }

    // While a lookup is running the field is read-only, so the URL cannot change
    // underneath the request that is already in flight.
    val busy = state.looking
    val ready = init is Extractor.Init.Ready

    // A play request from a "tap to play" notification, held in the ViewModel
    // so a rebuilt Activity does not replay it.
    LaunchedEffect(state.playRequest) {
        state.playRequest?.let { requested ->
            viewerList = listOf(requested)
            viewerIndex = 0
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
            // Grouped by site when that is on, exactly as the cards are laid out.
            .let { libraryOrder(it, settings) }
    }
    // What the viewer walks: the cards on screen, so a folded group's items are
    // skipped even though the library still lists the group.
    val onScreen = remember(visible, settings, query) { onScreenOrder(visible, settings, query) }
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
                title = { Text("Media Saver") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
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
                .padding(padding),
            contentPadding = PaddingValues(
                start = Space.lg,
                end = Space.lg,
                top = Space.sm,
                // Clears the gesture bar, so the last card is never half-hidden.
                bottom = Space.xl * 2,
            ),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            item {
                OutlinedTextField(
                    value = state.url,
                    onValueChange = viewModel::onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Paste a link to a post or video") },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    shape = MaterialTheme.shapes.medium,
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.md),
                    modifier = Modifier.height(52.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxHeight(),
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
                        modifier = Modifier.weight(1f).fillMaxHeight(),
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
                if (info.galleryItems.size >= 2) {
                    val total = info.galleryItems.size
                    item(key = "picker-header") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Or choose which to save", style = MaterialTheme.typography.titleMedium)
                            val allTicked = selectedItems.size == total
                            TextButton(onClick = {
                                selectedItems.clear()
                                if (!allTicked) selectedItems.addAll(info.galleryItems.map { it.position })
                            }) { Text(if (allTicked) "Select none" else "Select all") }
                        }
                    }
                    // Rows of three: the screen is one lazy list, which cannot
                    // hold a lazy grid.
                    info.galleryItems.chunked(3).forEachIndexed { rowIndex, row ->
                        item(key = "picker-row-$rowIndex") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { media ->
                                    PickerTile(
                                        media = media,
                                        selected = media.position in selectedItems,
                                        onToggle = {
                                            if (media.position in selectedItems) selectedItems.remove(media.position)
                                            else selectedItems.add(media.position)
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    item(key = "picker-action") {
                        val count = selectedItems.size
                        OutlinedButton(
                            onClick = {
                                DownloadService.enqueue(
                                    context = context,
                                    url = info.url,
                                    selector = Extractor.gallerySelector(selectedItems.toList()),
                                    kind = Formats.Kind.IMAGE,
                                    label = "${info.title} - $count of $total",
                                )
                            },
                            // Everything ticked is what "Download all" above is for.
                            enabled = count in 1 until total,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                when (count) {
                                    0 -> "Tick the items you want"
                                    total -> "Untick any you don't want"
                                    else -> "Download selected ($count)"
                                }
                            )
                        }
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
                    SectionHeader("Downloads", modifier = Modifier.padding(top = Space.sm)) {
                        if (jobs.any { !it.active }) {
                            TextButton(onClick = DownloadRepository::clearFinished) { Text("Clear finished") }
                        }
                    }
                }
                items(jobs, key = { it.id }) { job ->
                    JobCard(job) { uri ->
                        val known = onScreen.indexOfFirst { it.uri == uri }
                        if (known >= 0) {
                            viewerList = onScreen
                            viewerIndex = known
                            return@JobCard
                        }
                        viewerList = listOf(
                            saved.firstOrNull { it.uri == uri } ?: SavedItem(
                                id = job.id,
                                title = job.label,
                                displayName = job.savedAs ?: job.label,
                                uri = uri,
                                mimeType = job.mimeType ?: "video/*",
                                location = job.savedLocation.orEmpty(),
                                sizeBytes = 0,
                                savedAt = System.currentTimeMillis(),
                            )
                        )
                        viewerIndex = 0
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
                onToggleFavorite = { DownloadHistory.toggleFavorite(context, it.id) },
                onToggleGroup = { domain ->
                    Settings.update(context) {
                        val next = it.collapsedDomains.toMutableSet()
                        if (!next.remove(domain)) next += domain
                        it.copy(collapsedDomains = next)
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
                onPlay = { tapped ->
                    viewerList = onScreen
                    viewerIndex = onScreen.indexOfFirst { it.uri == tapped.uri }.coerceAtLeast(0)
                },
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

    if (viewerIndex in viewerList.indices) {
        val item = viewerList[viewerIndex]
        val close = { viewerIndex = -1 }
        val openOutside = {
            SavedMedia.open(context, item.uri, item.mimeType)
            viewerIndex = -1
        }
        // Swiping past either end simply stops, rather than wrapping around.
        val next = if (viewerIndex < viewerList.lastIndex) ({ viewerIndex += 1 }) else null
        val previous = if (viewerIndex > 0) ({ viewerIndex -= 1 }) else null

        if (item.mimeType.startsWith("image/")) {
            ImageViewer(
                item = item,
                position = viewerIndex + 1,
                total = viewerList.size,
                onNext = next,
                onPrevious = previous,
                onOpenExternally = openOutside,
                onDismiss = close,
            )
        } else {
            PlayerSheet(
                queue = viewerList,
                index = viewerIndex,
                onIndexChange = { viewerIndex = it },
                onOpenExternally = openOutside,
                onDismiss = close,
            )
        }
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
                Column {
                    Text(
                        (if (removable.size == 1) "1 file matches" else "${removable.size} files match") +
                            " something you already have - same size and name. The newest " +
                            "copy of each is kept; the others are deleted from the phone. " +
                            "This cannot be undone.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    // Every file named, so nothing is deleted sight unseen.
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        duplicateGroups.forEachIndexed { index, group ->
                            if (index > 0) item(key = "gap-$index") { Spacer(Modifier.height(12.dp)) }
                            item(key = "keep-${group.first().id}") {
                                DuplicateRow(group.first(), keep = true)
                            }
                            items(group.drop(1), key = { "delete-${it.id}" }) {
                                DuplicateRow(it, keep = false)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val removed = DownloadHistory.deleteAll(context, removable.map { it.id })
                    confirmDedupe = false
                    Toast.makeText(context, "Removed $removed duplicates", Toast.LENGTH_SHORT).show()
                }) { Text("Delete ${removable.size}") }
            },
            dismissButton = { TextButton(onClick = { confirmDedupe = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DuplicateRow(item: SavedItem, keep: Boolean) {
    val colour = if (keep) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(
            if (keep) "KEEP" else "DELETE",
            color = colour,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(52.dp).padding(top = 2.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                item.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (keep) FontWeight.Normal else FontWeight.Medium,
            )
            Text(
                listOfNotNull(
                    Formats.humanSize(item.sizeBytes),
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(item.savedAt)),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Always the full path: two copies often differ only in folder.
            Text(
                item.fullPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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

/** One item of a multi-item post, with a tick to include it or leave it out. */
@Composable
private fun PickerTile(
    media: GalleryDl.Photo,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = if (media.isVideo) "Video ${media.position}" else "Photo ${media.position}"
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClickLabel = if (selected) "Leave out" else "Include") { onToggle() },
        contentAlignment = Alignment.Center,
    ) {
        if (!media.isVideo) {
            AsyncImage(
                model = media.previewUrl,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
        } else {
            // No preview image comes with a video item, so say what it is.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Movie, contentDescription = label)
                if (media.duration > 0) {
                    Text(
                        Formats.humanDuration(media.duration.toInt()),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        Icon(
            if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(Space.sm)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50))
                .size(22.dp),
        )
    }
}

@Composable
private fun Banner(text: String, error: Boolean = false) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.md),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MediaCard(info: MediaInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(modifier = Modifier.padding(Space.md)) {
            Box(
                modifier = Modifier
                    .size(width = 104.dp, height = 72.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (info.thumbnail != null) {
                    AsyncImage(
                        model = info.thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Filled.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(Space.md))
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(
                    info.title,
                    style = MaterialTheme.typography.titleMedium,
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
                    Chip(
                        bits.joinToString(" · "),
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRow(option: Formats.Option, onClick: () -> Unit) {
    // The recommended option is the filled one; the rest are quieter cards, so
    // the eye lands on the choice most people want.
    val recommended = option.recommended
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (recommended) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    option.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (recommended) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
                if (option.subtitle.isNotBlank()) {
                    Text(
                        option.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (recommended) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(Space.md))
            Icon(
                Icons.Filled.Download,
                contentDescription = null,
                tint = if (recommended) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun JobCard(job: DownloadJob, onPlay: (String) -> Unit) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(Space.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    job.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Space.md))
                // The state of a download is worth spotting at a glance, so it
                // is a coloured chip rather than another line of grey text.
                when (job.status) {
                    DownloadJob.Status.DONE -> Chip(
                        "Done",
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    DownloadJob.Status.FAILED -> Chip(
                        "Failed",
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    DownloadJob.Status.CANCELLED -> Chip("Stopped")
                    else -> Chip("${(job.progress * 100).toInt()}%")
                }
            }

            Spacer(Modifier.height(Space.md))

            val bar = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(MaterialTheme.shapes.extraSmall)
            if (job.status == DownloadJob.Status.SAVING || (job.active && job.progress <= 0f)) {
                LinearProgressIndicator(modifier = bar)
            } else {
                LinearProgressIndicator(progress = { job.progress }, modifier = bar)
            }

            Spacer(Modifier.height(Space.md))

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
                Spacer(Modifier.height(Space.md))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    modifier = Modifier.height(44.dp),
                ) {
                    Button(
                        onClick = { onPlay(uri) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) { Text(if (job.mimeType?.startsWith("image/") == true) "View" else "Play") }
                    OutlinedButton(
                        onClick = { SavedMedia.share(context, uri, job.mimeType) },
                        modifier = Modifier.fillMaxHeight(),
                    ) { Text("Share") }
                }
            }
        }
    }
}
