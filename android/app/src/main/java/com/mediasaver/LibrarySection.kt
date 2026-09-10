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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/** Everything the app has saved: searchable, sortable, groupable, removable. */
fun LazyListScope.librarySection(
    entries: List<SavedItem>,
    settings: Settings.State,
    query: String,
    onQueryChange: (String) -> Unit,
    duplicateCount: Int,
    onDedupe: () -> Unit,
    onSort: (Settings.SortBy) -> Unit,
    onToggleLayout: () -> Unit,
    onPlay: (SavedItem) -> Unit,
    onShare: (SavedItem) -> Unit,
    onDelete: (SavedItem) -> Unit,
) {
    if (entries.isEmpty() && query.isBlank()) return

    item(key = "library-header") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Saved on this phone", style = MaterialTheme.typography.titleSmall)
            Row {
                SortMenu(settings, onSort)
                IconButton(onClick = onToggleLayout) {
                    Icon(
                        if (settings.layout == Settings.Layout.GRID) Icons.Filled.ViewList
                        else Icons.Filled.GridView,
                        contentDescription = "Switch between list and grid",
                    )
                }
            }
        }
    }

    item(key = "library-search") {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            placeholder = { Text("Search saved media - * and ? work") },
        )
    }

    if (duplicateCount > 0) {
        item(key = "library-dupes") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "$duplicateCount duplicate ${if (duplicateCount == 1) "file" else "files"}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDedupe) { Text("Remove duplicates") }
                }
            }
        }
    }

    if (entries.isEmpty()) {
        item(key = "library-empty") {
            Text(
                "Nothing matches that search.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    if (settings.groupByDomain) {
        val grouped = entries.groupBy { it.sourceDomain.ifBlank { "Other" } }.toList()
        val groups = if (settings.sortBy == Settings.SortBy.DATE) {
            // Alphabetical group order would bury the newest download under
            // whichever site happens to sort first, which defeats sorting by
            // date entirely.
            val byRecency = grouped.sortedBy { (_, items) -> items.maxOf { it.savedAt } }
            if (settings.sortDescending) byRecency.reversed() else byRecency
        } else {
            grouped.sortedBy { it.first.lowercase() }
        }
        groups.forEach { (domain, groupItems) ->
            item(key = "group-$domain") {
                Text(
                    "$domain  ·  ${groupItems.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            emitItems(groupItems, settings, domain, onPlay, onShare, onDelete)
        }
    } else {
        emitItems(entries, settings, "all", onPlay, onShare, onDelete)
    }
}

private fun LazyListScope.emitItems(
    entries: List<SavedItem>,
    settings: Settings.State,
    keyPrefix: String,
    onPlay: (SavedItem) -> Unit,
    onShare: (SavedItem) -> Unit,
    onDelete: (SavedItem) -> Unit,
) {
    if (settings.layout == Settings.Layout.GRID) {
        // The whole screen is one lazy list, which cannot nest a lazy grid, so
        // the grid is emitted as rows of two.
        entries.chunked(2).forEachIndexed { index, row ->
            item(key = "$keyPrefix-row-$index") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { entry ->
                        Column(Modifier.weight(1f)) {
                            GridCard(entry, settings, onPlay, onShare, onDelete)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    } else {
        items(entries, key = { "$keyPrefix-${it.id}" }) { entry ->
            ListCard(entry, settings, onPlay, onShare, onDelete)
        }
    }
}

@Composable
private fun SortMenu(settings: Settings.State, onSort: (Settings.SortBy) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.Sort, contentDescription = "Sort")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Settings.SortBy.entries.forEach { option ->
                val label = when (option) {
                    Settings.SortBy.DATE -> "Date modified"
                    Settings.SortBy.SIZE -> "File size"
                    Settings.SortBy.NAME -> "Name"
                }
                val marker = if (settings.sortBy == option) {
                    if (settings.sortDescending) "  ↓" else "  ↑"
                } else ""
                DropdownMenuItem(
                    text = { Text(label + marker) },
                    onClick = { onSort(option); open = false },
                )
            }
        }
    }
}

@Composable
private fun ListCard(
    item: SavedItem,
    settings: Settings.State,
    onPlay: (SavedItem) -> Unit,
    onShare: (SavedItem) -> Unit,
    onDelete: (SavedItem) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MediaThumbnail(
                    item = item,
                    enabled = settings.showThumbnails,
                    modifier = Modifier
                        .size(width = 96.dp, height = 64.dp)
                        .clip(RoundedCornerShape(8.dp)),
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
                        subtitleFor(item, settings),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onDelete(item) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onPlay(item) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Play")
                }
                OutlinedButton(onClick = { onShare(item) }) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
            }
        }
    }
}

@Composable
private fun GridCard(
    item: SavedItem,
    settings: Settings.State,
    onPlay: (SavedItem) -> Unit,
    onShare: (SavedItem) -> Unit,
    onDelete: (SavedItem) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            MediaThumbnail(
                item = item,
                enabled = settings.showThumbnails,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
            )
            Column(Modifier.padding(10.dp)) {
                Text(
                    item.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitleFor(item, settings),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = { onPlay(item) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                    }
                    IconButton(onClick = { onShare(item) }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = { onDelete(item) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

private fun subtitleFor(item: SavedItem, settings: Settings.State): String {
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.savedAt))
    val bits = listOfNotNull(
        Formats.humanSize(item.sizeBytes),
        date,
        if (settings.showFullPath) item.fullPath else item.location,
    )
    return bits.joinToString(" · ")
}
