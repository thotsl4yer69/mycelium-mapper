package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.json.JSONObject

/** One bundled photo's credit, parsed from assets/species_images/attributions.json. */
data class ImageAttribution(
    val file: String,
    val scientificName: String,
    val artist: String,
    val license: String,
    val licenseUrl: String,
    val source: String
)

private data class AttributionGroup(val scientificName: String, val items: List<ImageAttribution>)

private fun loadAttributions(context: Context): List<AttributionGroup> = try {
    val json = context.assets.open("species_images/attributions.json")
        .bufferedReader().use { it.readText() }
    val arr = JSONObject(json).getJSONArray("images")
    val list = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        ImageAttribution(
            file = o.optString("file"),
            scientificName = o.optString("scientificName"),
            artist = o.optString("artist").ifBlank { "Unknown" },
            license = o.optString("license").ifBlank { "See source" },
            licenseUrl = o.optString("licenseUrl"),
            source = o.optString("source")
        )
    }
    // Preserve first-seen species order.
    list.groupBy { it.scientificName }
        .map { (name, items) -> AttributionGroup(name, items) }
} catch (e: Exception) {
    emptyList()
}

private fun openUrl(context: Context, url: String) {
    if (url.isBlank()) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttributionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val groups = remember { loadAttributions(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Image credits") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("attributions_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = "Species reference photos are sourced from Wikimedia Commons and used " +
                        "under the licenses below. Tap an entry to view the original file and full " +
                        "license terms.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (groups.isEmpty()) {
                item {
                    Text(
                        text = "No attribution data found.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            groups.forEach { group ->
                item {
                    Text(
                        text = group.scientificName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                items(group.items) { item ->
                    AttributionRow(item, onClick = { openUrl(context, item.source) })
                }
            }
        }
    }
}

@Composable
private fun AttributionRow(item: ImageAttribution, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        )
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = "file:///android_asset/species_images/${item.file}",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2
                )
                Text(
                    text = item.license,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = "Open source",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
