package com.mediasaver

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.unit.Dp
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
    onToggleGroup: (String) -> Unit,
    onPlay: (SavedItem) -> Unit,
    onShare: (SavedItem) -> Unit,
    onDelete: (SavedItem) -> Unit,
    onToggleFavorite: (SavedItem) -> Unit,
) {
    if (entries.isEmpty() && query.isBlank()) return

    item(key = "library-header") {
        SectionHeader("Saved on this phone", modifier = Modifier.padding(top = Space.sm)) {
            SortMenu(settings, onSort)
            IconButton(onClick = onToggleLayout) {
                Icon(
                    if (settings.layout == Settings.Layout.GRID) Icons.Filled.ViewList
                    else Icons.Filled.GridView,
                    contentDescription = "Switch between list and grid",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    item(key = "library-search") {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            textStyle = MaterialTheme.typography.bodyLarge,
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            placeholder = { Text("Search saved media") },
        )
    }

    if (duplicateCount > 0) {
        item(key = "library-dupes") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Space.lg, end = Space.sm, top = Space.xs, bottom = Space.xs),
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
                "Nothing matches that search. Wildcards * and ? work too.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Space.md),
            )
        }
        return
    }

    libraryGroups(entries, settings).forEach { (group, groupItems) ->
        // UNGROUPED is the whole library in one lump; it gets no header, and
        // nothing to fold.
        if (group == UNGROUPED) {
            emitItems(groupItems, settings, group, onPlay, onShare, onDelete, onToggleFavorite)
            return@forEach
        }
        val folded = isFolded(group, settings, query)
        item(key = "group-$group") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onToggleGroup(group) }
                    .heightIn(min = 44.dp)
                    .padding(horizontal = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Icon(
                    if (folded) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore,
                    contentDescription = if (folded) "Unfold $group" else "Fold $group",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    group,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Chip(
                    groupItems.size.toString(),
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        if (!folded) {
            emitItems(groupItems, settings, group, onPlay, onShare, onDelete, onToggleFavorite)
        }
    }
}

/**
 * A folded group shows only its header. A search unfolds everything: hiding
 * matches inside a folded group would look like the search had missed them.
 */
fun isFolded(group: String, settings: Settings.State, query: String): Boolean =
    group != UNGROUPED && query.isBlank() && group in settings.collapsedDomains

/** The group an item is filed under, matching [libraryGroups]. */
fun groupOf(item: SavedItem, settings: Settings.State): String = when {
    item.favorite -> FAVOURITES
    settings.groupByDomain -> item.sourceDomain.ifBlank { "Other" }
    else -> UNGROUPED
}

/**
 * The library as it appears on screen: grouped by site when that setting is on.
 * The viewer walks this same order, so swiping always lands on the card that
 * sits next to the one tapped rather than skipping between groups.
 */
fun libraryGroups(entries: List<SavedItem>, settings: Settings.State): List<Pair<String, List<SavedItem>>> {
    // Favourites are pinned to the top in their own section, and left out of
    // their site's group: one item in two places reads as a duplicate.
    val favourites = entries.filter { it.favorite }
    val rest = entries.filterNot { it.favorite }
    val head = if (favourites.isEmpty()) emptyList() else listOf(FAVOURITES to favourites)
    return head + siteGroups(rest, settings)
}

private fun siteGroups(
    entries: List<SavedItem>,
    settings: Settings.State,
): List<Pair<String, List<SavedItem>>> {
    if (entries.isEmpty()) return emptyList()
    if (!settings.groupByDomain) return listOf(UNGROUPED to entries)
    val grouped = entries.groupBy { it.sourceDomain.ifBlank { "Other" } }.toList()
    return if (settings.sortBy == Settings.SortBy.DATE) {
        // Alphabetical group order would bury the newest download under
        // whichever site happens to sort first, which defeats sorting by
        // date entirely.
        val byRecency = grouped.sortedBy { (_, items) -> items.maxOf { it.savedAt } }
        if (settings.sortDescending) byRecency.reversed() else byRecency
    } else {
        grouped.sortedBy { it.first.lowercase() }
    }
}

/** Every item, in the order the cards are laid out. */
fun libraryOrder(entries: List<SavedItem>, settings: Settings.State): List<SavedItem> =
    libraryGroups(entries, settings).flatMap { it.second }

/**
 * The items actually on screen - a folded group shows only its header, so the
 * viewer does not swipe through what it hides.
 */
fun onScreenOrder(
    entries: List<SavedItem>,
    settings: Settings.State,
    query: String,
): List<SavedItem> =
    libraryOrder(entries, settings).filterNot { isFolded(groupOf(it, settings), settings, query) }

/** The pinned section's name, and the name for "not grouped at all". */
const val FAVOURITES = "Favourites"
const val UNGROUPED = "all"

private fun LazyListScope.emitItems(
    entries: List<SavedItem>,
    settings: Settings.State,
    keyPrefix: String,
    onPlay: (SavedItem) -> Unit,
    onShare: (SavedItem) -> Unit,
    onDelete: (SavedItem) -> Unit,
    onToggleFavorite: (SavedItem) -> Unit,
) {
    if (settings.layout == Settings.Layout.GRID) {
        // The whole screen is one lazy list, which cannot nest a lazy grid, so
        // the grid is emitted as rows of two.
        entries.chunked(2).forEachIndexed { index, row ->
            item(key = "$keyPrefix-row-$index") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { entry ->
                        Column(Modifier.weight(1f)) {
                            GridCard(entry, settings, onPlay, onShare, onDelete, onToggleFavorite)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    } else {
        items(entries, key = { "$keyPrefix-${it.id}" }) { entry ->
            ListCard(entry, settings, onPlay, onShare, onDelete, onToggleFavorite)
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
    onToggleFavorite: (SavedItem) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(Space.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MediaThumbnail(
                    item = item,
                    enabled = settings.showThumbnails,
                    showPlayBadge = true,
                    modifier = Modifier
                        .size(width = 96.dp, height = 64.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onPlay(item) },
                )
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        item.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // One line: the file name is what identifies the item, and
                    // a wrapping size-and-path line buried it.
                    Text(
                        subtitleFor(item, settings),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(Space.sm))
            // Every action lives on this row, which leaves the row above for
            // the name and its details alone - at a large font size on a narrow
            // phone, icons beside the text squeezed it down to "17.8 KB · S...".
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = { onPlay(item) },
                    modifier = Modifier.weight(1f).height(42.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.sm))
                    Text(
                        if (item.isImage) "View" else "Play",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onShare(item) }) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = "Share",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FavoriteButton(item, onToggleFavorite)
                IconButton(onClick = { onDelete(item) }) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = "Delete",
                        // Quiet until it matters: the confirmation dialog is
                        // where deleting turns red.
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
    onToggleFavorite: (SavedItem) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column {
            MediaThumbnail(
                item = item,
                enabled = settings.showThumbnails,
                showPlayBadge = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(118.dp)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .clickable { onPlay(item) },
            )
            Column(
                modifier = Modifier.padding(start = Space.md, end = Space.xs, top = Space.sm),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Two cards sit side by side, so these icons are the smaller
                // 20dp size and share the width evenly.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = { onPlay(item) }) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = if (item.isImage) "View" else "Play",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(onClick = { onShare(item) }) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = "Share",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    FavoriteButton(item, onToggleFavorite, size = 20.dp)
                    IconButton(onClick = { onDelete(item) }) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
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
        // The folder is the same for nearly everything, so it earns its place
        // on this one line only when the user asks to see paths.
        if (settings.showFullPath) item.fullPath else null,
    )
    return bits.joinToString(" · ")
}

/** Pins an item to the Favourites section, or takes it back out. */
@Composable
private fun FavoriteButton(
    item: SavedItem,
    onToggle: (SavedItem) -> Unit,
    size: Dp = 24.dp,
) {
    IconButton(onClick = { onToggle(item) }) {
        Icon(
            if (item.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
            contentDescription = if (item.favorite) "Remove from favourites" else "Add to favourites",
            tint = if (item.favorite) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size),
        )
    }
}
