package com.kitsumed.shizucallrecorder.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.kitsumed.shizucallrecorder.data.AppPreferences
import java.io.File
import java.text.DateFormat
import java.util.Date

private enum class RecordingSort(val label: String) { DATE("التاريخ"), SIZE("الحجم"), TYPE("النوع"), NAME("الاسم"), DIRECTION("الاتجاه") }
private data class RecordingItem(val name:String, val uri:android.net.Uri, val bytes:Long, val modified:Long, val type:String)

@Composable
fun HomeScreen(preferences: AppPreferences, updateTrigger: Int, settings: @Composable () -> Unit) {
    var selected by remember { mutableIntStateOf(0) }
    Scaffold(bottomBar = { NavigationBar {
        NavigationBarItem(selected == 0, { selected = 0 }, icon = { Icon(Icons.Default.MusicNote, null) }, label = { Text("التسجيلات") })
        NavigationBarItem(selected == 1, { selected = 1 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("الإعدادات") })
    } }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (selected == 0) RecordingsScreen(preferences, updateTrigger) else settings()
        }
    }
}

@Composable
private fun RecordingsScreen(preferences: AppPreferences, updateTrigger: Int) {
    val context = LocalContext.current
    var sort by remember { mutableStateOf(RecordingSort.DATE) }
    var ascending by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val recordings = remember(updateTrigger, preferences.getRecordingFolderUri()) {
        val uri = preferences.getRecordingFolderUri()
        val docs = if (uri == null) File(context.getExternalFilesDir(null), "CallRecordings").listFiles()?.filter { it.isFile }?.map {
            RecordingItem(it.name, android.net.Uri.fromFile(it), it.length(), it.lastModified(), it.extension)
        } ?: emptyList() else DocumentFile.fromTreeUri(context, uri)?.listFiles()?.filter { it.isFile }?.map {
            RecordingItem(it.name ?: "", it.uri, it.length(), it.lastModified(), it.type ?: "")
        } ?: emptyList()
        docs
    }
    val sorted = remember(recordings, sort, ascending) {
        val result = when(sort) {
            RecordingSort.DATE -> recordings.sortedBy { it.modified }
            RecordingSort.SIZE -> recordings.sortedBy { it.bytes }
            RecordingSort.TYPE -> recordings.sortedBy { it.type.lowercase() }
            RecordingSort.NAME -> recordings.sortedBy { it.name.lowercase() }
            RecordingSort.DIRECTION -> recordings.sortedBy { if (it.name.contains("incoming", true) || it.name.contains("inbound", true)) 0 else 1 }
        }
        if (ascending) result else result.reversed()
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("التسجيلات", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "خيارات الفرز") }
                DropdownMenu(expanded, { expanded = false }) {
                    RecordingSort.entries.forEach { option -> DropdownMenuItem(text = { Text("حسب ${option.label}") }, onClick = { sort = option; expanded = false }) }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(if (ascending) "الاتجاه: تصاعدي" else "الاتجاه: تنازلي") }, onClick = { ascending = !ascending; expanded = false })
                }
            }
        }
        Text("الترتيب: ${sort.label} · ${if (ascending) "تصاعدي" else "تنازلي"}", style = MaterialTheme.typography.bodySmall)
        if (sorted.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("لا توجد تسجيلات بعد") }
        else LazyColumn { items(sorted, key = { it.uri.toString() }) { item ->
            ListItem(headlineContent = { Text(item.name) }, supportingContent = { Text("${DateFormat.getDateTimeInstance().format(Date(item.modified))} · ${formatSize(item.bytes)} · ${item.type}") }, modifier = Modifier.clickable {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(item.uri, "audio/*").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            })
        } }
    }
}
private fun formatSize(bytes: Long): String = when { bytes < 1024 -> "$bytes B"; bytes < 1024*1024 -> "${bytes/1024} KB"; else -> "${bytes/(1024*1024)} MB" }
