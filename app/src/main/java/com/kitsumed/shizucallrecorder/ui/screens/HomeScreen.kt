package com.kitsumed.shizucallrecorder.ui.screens

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.kitsumed.shizucallrecorder.data.AppPreferences
import kotlinx.coroutines.delay
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class RecordingSort(val label: String) { DATE("التاريخ"), SIZE("الحجم"), TYPE("النوع"), NAME("الاسم"), DIRECTION("الاتجاه") }
private data class RecordingItem(val name:String, val uri:android.net.Uri, val bytes:Long, val modified:Long, val type:String, val duration:Long, val localFile:File? = null)

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
    val localPrefs = remember { context.getSharedPreferences("recordings_history", 0) }
    var sort by remember { mutableStateOf(RecordingSort.DATE) }
    var ascending by remember { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    var favoriteOnly by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var activeUri by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableIntStateOf(1) }
    var actionItem by remember { mutableStateOf<RecordingItem?>(null) }
    var noteItem by remember { mutableStateOf<RecordingItem?>(null) }
    var renameItem by remember { mutableStateOf<RecordingItem?>(null) }
    var noteText by remember { mutableStateOf("") }
    var renameText by remember { mutableStateOf("") }

    DisposableEffect(Unit) { onDispose { runCatching { player?.release() } } }
    LaunchedEffect(playing, activeUri) {
        while (playing) {
            delay(500)
            position = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
            if (duration > 0 && position >= duration) playing = false
        }
    }
    val recordings = remember(updateTrigger, refresh, preferences.getRecordingFolderUri()) {
        val uri = preferences.getRecordingFolderUri()
        val files: List<RecordingItem> = if (uri == null) {
            File(context.getExternalFilesDir(null), "CallRecordings").listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("ogg", "opus", "m4a", "mp4", "3gp", "aac") }?.map { f ->
                RecordingItem(f.name, android.net.Uri.fromFile(f), f.length(), f.lastModified(), f.extension, mediaDuration(context, android.net.Uri.fromFile(f)), f)
            } ?: emptyList()
        } else {
            DocumentFile.fromTreeUri(context, uri)?.listFiles()?.filter { it.isFile && (it.type?.startsWith("audio/") == true || it.name?.substringAfterLast('.', "")?.lowercase() in setOf("ogg", "opus", "m4a", "mp4", "3gp", "aac")) }?.map { f ->
                RecordingItem(f.name ?: "تسجيل صوتي", f.uri, f.length(), f.lastModified(), f.type ?: "audio", mediaDuration(context, f.uri))
            } ?: emptyList()
        }
        files
    }
    val favorites = remember(refresh) { localPrefs.getStringSet("favorites", emptySet()).orEmpty() }
    val visible = recordings.filter { (!favoriteOnly || it.uri.toString() in favorites) && (query.isBlank() || it.name.contains(query, true)) }
    val ordered = remember(visible, sort, ascending) {
        val sorted = when (sort) {
            RecordingSort.DATE -> visible.sortedBy { it.modified }
            RecordingSort.SIZE -> visible.sortedBy { it.bytes }
            RecordingSort.TYPE -> visible.sortedBy { callDirection(it.name) + it.type }
            RecordingSort.NAME -> visible.sortedBy { displayName(it.name).lowercase(Locale.getDefault()) }
            RecordingSort.DIRECTION -> visible.sortedBy { callDirection(it.name) }
        }
        if (ascending) sorted else sorted.reversed()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("سجل التسجيلات", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { searchExpanded = !searchExpanded; if (!searchExpanded) query = "" }) { Icon(Icons.Default.Search, "بحث") }
            IconButton(onClick = { favoriteOnly = !favoriteOnly }) { Icon(if (favoriteOnly) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "المفضلة", tint = if (favoriteOnly) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
            Box {
                IconButton(onClick = { sortExpanded = true }) { Icon(Icons.Default.MoreVert, "خيارات الفرز") }
                DropdownMenu(sortExpanded, { sortExpanded = false }) {
                    RecordingSort.entries.forEach { option -> DropdownMenuItem(text = { Text("حسب ${option.label}") }, onClick = { sort = option; sortExpanded = false }) }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("الترتيب: ${if (ascending) "تصاعدي" else "تنازلي"}") }, onClick = { ascending = !ascending; sortExpanded = false })
                }
            }
        }
        if (searchExpanded) OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("ابحث بالاسم أو الرقم") })
        Text("${ordered.size} تسجيلات · حسب ${sort.label} · ${if (ascending) "تصاعدي" else "الأحدث أولاً"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ordered.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (favoriteOnly) "لا توجد تسجيلات مفضلة" else "لا توجد تسجيلات بعد") }
        else LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
            val sections = ordered.groupBy { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it.modified)) }
            sections.forEach { (dateKey, entries) ->
                item(key = "date-$dateKey") { Text(DateFormat.getDateInstance(DateFormat.FULL).format(Date(entries.first().modified)), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp)) }
                items(entries, key = { it.uri.toString() }) { item ->
                    val directionIncoming = callDirection(item.name) == "in"
                    var menuOpen by remember(item.uri) { mutableStateOf(false) }
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column {
                            Row(Modifier.fillMaxWidth().clickable { if (activeUri == item.uri.toString()) { if (playing) { player?.pause(); playing = false } else { player?.start(); playing = true } } else startPlayback(item, context, { old -> player = old }, { activeUri = it }, { duration = it; position = 0L }, { playing = true }) }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (directionIncoming) Icons.Default.CallReceived else Icons.Default.CallMade, null, tint = if (directionIncoming) Color(0xFF4F8CFF) else Color(0xFF43A66B), modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(displayName(item.name), style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.weight(1f))
                                        if (item.uri.toString() in favorites) Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                                    }
                                    Text("${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.modified))} · ${formatSize(item.bytes)} · ${item.name.takeIf { it.any(Char::isDigit) } ?: item.type}", style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(formatDuration(item.duration), style = MaterialTheme.typography.labelSmall)
                                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "خيارات التسجيل") }
                                DropdownMenu(menuOpen, { menuOpen = false }) {
                                    DropdownMenuItem(text = { Text(if (item.uri.toString() in favorites) "إزالة من المفضلة" else "إضافة إلى المفضلة") }, onClick = { val set = localPrefs.getStringSet("favorites", emptySet()).orEmpty().toMutableSet(); if (!set.add(item.uri.toString())) set.remove(item.uri.toString()); localPrefs.edit().putStringSet("favorites", set).apply(); refresh++; menuOpen = false })
                                    DropdownMenuItem(text = { Text("إضافة / تعديل ملاحظة") }, onClick = { noteItem = item; noteText = localPrefs.getString("note:${item.uri}", "").orEmpty(); menuOpen = false })
                                    DropdownMenuItem(text = { Text("إعادة تسمية") }, onClick = { renameItem = item; renameText = item.name.substringBeforeLast('.'); menuOpen = false })
                                    DropdownMenuItem(text = { Text("مشاركة") }, onClick = { runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/*").putExtra(Intent.EXTRA_STREAM, item.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "مشاركة التسجيل")) }; menuOpen = false })
                                    DropdownMenuItem(text = { Text("حذف") }, onClick = { actionItem = item; menuOpen = false })
                                }
                            }
                            if (activeUri == item.uri.toString()) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Slider(value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f, onValueChange = { position = (it * duration).toLong(); runCatching { player?.seekTo(position.toInt()) } }, valueRange = 0f..1f)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                                        TextButton(onClick = { speed = (speed + 1) % 5; applySpeed(player, speed) }) { Text(listOf("AUTO", "×1", "×1.25", "×1.5", "×2")[speed]) }
                                        IconButton(onClick = { seek(player, -10_000, duration, { position = it }) }) { Icon(Icons.Default.SkipPrevious, "إرجاع 10 ثوان") }
                                        IconButton(onClick = { if (playing) { player?.pause(); playing = false } else { player?.start(); playing = true } }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "إيقاف مؤقت" else "تشغيل") }
                                        IconButton(onClick = { seek(player, 10_000, duration, { position = it }) }) { Icon(Icons.Default.SkipNext, "تقديم 10 ثوان") }
                                        Text("${formatDuration(position)} / ${formatDuration(duration)}", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            localPrefs.getString("note:${item.uri}", null)?.takeIf { it.isNotBlank() }?.let { note -> Text("ملاحظة: $note", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 52.dp, end = 12.dp, bottom = 8.dp)) }
                        }
                    }
                }
            }
        }
    }
    actionItem?.let { item -> AlertDialog(onDismissRequest = { actionItem = null }, title = { Text("حذف التسجيل؟") }, text = { Text("سيتم حذف ${item.name} نهائيًا.") }, confirmButton = { TextButton(onClick = { runCatching { item.localFile?.delete() ?: DocumentFile.fromSingleUri(context, item.uri)?.delete() }; if (activeUri == item.uri.toString()) { player?.release(); player = null; activeUri = null; playing = false }; refresh++; actionItem = null }) { Text("حذف") } }, dismissButton = { TextButton(onClick = { actionItem = null }) { Text("إلغاء") } }) }
    noteItem?.let { item -> AlertDialog(onDismissRequest = { noteItem = null }, title = { Text("ملاحظة التسجيل") }, text = { OutlinedTextField(noteText, { noteText = it }, label = { Text("ملاحظة") }) }, confirmButton = { TextButton(onClick = { localPrefs.edit().putString("note:${item.uri}", noteText).apply(); noteItem = null; refresh++ }) { Text("حفظ") } }, dismissButton = { TextButton(onClick = { noteItem = null }) { Text("إلغاء") } }) }
    renameItem?.let { item -> AlertDialog(onDismissRequest = { renameItem = null }, title = { Text("إعادة تسمية") }, text = { OutlinedTextField(renameText, { renameText = it }, label = { Text("الاسم") }) }, confirmButton = { TextButton(onClick = {
        val extension = item.name.substringAfterLast('.', "")
        val newName = if (extension.isBlank()) renameText else "$renameText.$extension"
        runCatching {
            item.localFile?.let { it.renameTo(File(it.parentFile, newName)) }
                ?: DocumentFile.fromSingleUri(context, item.uri)?.renameTo(newName)
        }
        renameItem = null
        refresh++
    }) { Text("حفظ") } }, dismissButton = { TextButton(onClick = { renameItem = null }) { Text("إلغاء") } }) }
}

private fun callDirection(name: String) = when { name.contains("in", true) -> "in"; name.contains("out", true) -> "out"; else -> "unknown" }
private fun displayName(name: String): String = name.substringBeforeLast('.').replace(Regex("^\\d{4}[-_]?\\d{2}[-_]?\\d{2}[_-]?\\d{0,6}[_-]?"), "").replace("_", " ").ifBlank { name.substringBeforeLast('.') }
private fun formatSize(bytes: Long): String = when { bytes < 1024 -> "$bytes B"; bytes < 1024*1024 -> String.format(Locale.getDefault(), "%.1f kB", bytes / 1024.0); else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0*1024)) }
private fun formatDuration(ms: Long): String = String.format(Locale.getDefault(), "%02d:%02d", ms / 60_000, (ms / 1000) % 60)
private fun mediaDuration(context: android.content.Context, uri: android.net.Uri): Long = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } finally {
        retriever.release()
    }
}.getOrDefault(0L)
private fun startPlayback(item: RecordingItem, context: android.content.Context, setPlayer: (MediaPlayer?) -> Unit, setUri: (String) -> Unit, setDuration: (Long) -> Unit, setPlaying: () -> Unit) {
    runCatching { setPlayer(null); MediaPlayer().apply { setDataSource(context, item.uri); setOnCompletionListener { it.pause() }; prepare(); setPlayer(this); setUri(item.uri.toString()); setDuration(duration.toLong()); start(); setPlaying() } }
}
private fun applySpeed(player: MediaPlayer?, speed: Int) {
    if (Build.VERSION.SDK_INT >= 23 && player != null) runCatching {
        val params = player.playbackParams
        params.speed = listOf(1f, 1f, 1.25f, 1.5f, 2f)[speed]
        player.playbackParams = params
    }
}
private fun seek(player: MediaPlayer?, delta: Int, duration: Long, update: (Long) -> Unit) { val target = ((player?.currentPosition ?: 0) + delta).coerceIn(0, duration.toInt()); runCatching { player?.seekTo(target) }; update(target.toLong()) }
