package com.mediasaver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun SettingsDialog(
    settings: Settings.State,
    onChange: ((Settings.State) -> Settings.State) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {

                SectionLabel("Appearance")
                ChipRow(
                    options = Settings.ThemeMode.entries.map { it to it.name.lowercase().replaceFirstChar(Char::uppercase) },
                    selected = settings.theme,
                    onSelect = { choice -> onChange { it.copy(theme = choice) } },
                )
                Spacer(Modifier.height(6.dp))
                ChipRow(
                    options = Settings.Accent.entries.map { it to it.name.lowercase().replaceFirstChar(Char::uppercase) },
                    selected = settings.accent,
                    onSelect = { choice -> onChange { it.copy(accent = choice) } },
                )

                SectionLabel("Downloading")
                SwitchRow("Download best quality automatically", settings.autoDownloadBest) { v ->
                    onChange { it.copy(autoDownloadBest = v) }
                }
                Text(
                    "Skips the quality list: as soon as a link resolves, the best " +
                        "video and audio available starts downloading.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SectionLabel("Playing")
                SwitchRow("Loop videos", settings.loopPlayback) { v ->
                    onChange { it.copy(loopPlayback = v) }
                }
                Text(
                    "Restarts from the beginning when a video ends.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SectionLabel("Library")
                ChipRow(
                    options = Settings.Layout.entries.map { it to it.name.lowercase().replaceFirstChar(Char::uppercase) },
                    selected = settings.layout,
                    onSelect = { choice -> onChange { it.copy(layout = choice) } },
                )
                Spacer(Modifier.height(6.dp))
                ChipRow(
                    options = Settings.SortBy.entries.map {
                        it to when (it) {
                            Settings.SortBy.DATE -> "Date"
                            Settings.SortBy.SIZE -> "Size"
                            Settings.SortBy.NAME -> "Name"
                        }
                    },
                    selected = settings.sortBy,
                    onSelect = { choice -> onChange { it.copy(sortBy = choice) } },
                )

                SwitchRow("Newest / largest first", settings.sortDescending) { v ->
                    onChange { it.copy(sortDescending = v) }
                }
                SwitchRow("Group by site", settings.groupByDomain) { v ->
                    onChange { it.copy(groupByDomain = v) }
                }
                SwitchRow("Show thumbnails", settings.showThumbnails) { v ->
                    onChange { it.copy(showThumbnails = v) }
                }
                SwitchRow("Show full file path", settings.showFullPath) { v ->
                    onChange { it.copy(showFullPath = v) }
                }

                SectionLabel("Download folder")
                var folder by remember(settings.folder) { mutableStateOf(settings.folder) }
                OutlinedTextField(
                    value = folder,
                    onValueChange = { value ->
                        folder = value
                        onChange { it.copy(folder = value) }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Folder name") },
                )
                Text(
                    "Saved to Movies/${Settings.safeFolder(folder)}, and the matching " +
                        "Music, Pictures or Download folder by type. Existing files stay " +
                        "where they are.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    // More options than fit the dialog width, so the row scrolls rather than
    // truncating the labels at the end.
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        // The whole row toggles, not just the switch: aiming for a small switch
        // at the edge of a dialog is a poor target, and tapping the label doing
        // nothing reads as the setting being broken.
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Cookies are the only way to reach posts a site will not serve to a signed-out
 * client. This explains how to get them from the phone itself, since Android
 * gives no app access to another app's or browser's cookie store.
 */
@Composable
fun CookiesDialog(
    signedIn: Set<String>,
    onPasteSave: (String, CookieStore.Site?) -> Unit,
    onImportFile: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pasted by remember { mutableStateOf("") }
    var site by remember { mutableStateOf(CookieStore.SITES.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Site sign-ins") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (signedIn.isNotEmpty()) {
                    Text(
                        "Signed in: " + CookieStore.SITES.filter { it.key in signedIn }
                            .joinToString { it.label },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(10.dp))
                }

                Text(
                    "X hides posts marked sensitive from signed-out apps, and Vimeo and " +
                        "Reddit hide everything. Supplying your own session makes those " +
                        "readable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SectionLabel("Getting cookies on the phone")
                Text(
                    "Android stops any app from reading another app's or browser's " +
                        "cookies, so the X and Instagram apps cannot be read directly. A " +
                        "bookmarklet will not work either: the session cookie is HttpOnly " +
                        "and invisible to page JavaScript.\n\n" +
                        "What does work, entirely on the phone:\n" +
                        "1. Install Firefox for Android (or Kiwi Browser).\n" +
                        "2. Add the \"Get cookies.txt LOCALLY\" extension.\n" +
                        "3. Sign in to the site in that browser.\n" +
                        "4. Export cookies for the site, then paste the text below or " +
                        "import the saved file.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SectionLabel("Paste cookies")
                ChipRow(
                    options = CookieStore.SITES.map { it to it.label.substringBefore(' ') },
                    selected = site,
                    onSelect = { site = it },
                )
                Text(
                    "The site above is only needed when pasting a \"name=value; ...\" " +
                        "string. A full cookies.txt already names its own domains.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    label = { Text("cookies.txt contents") },
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onPasteSave(pasted, site) },
                    enabled = pasted.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Check and save") }
                Text(
                    "Checked before saving: the text has to contain a recognised login " +
                        "cookie. That cannot prove the session is still live, only that a " +
                        "login is present.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onImportFile, modifier = Modifier.fillMaxWidth()) {
                    Text("Import a cookies.txt file instead")
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
