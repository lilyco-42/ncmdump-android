package com.ncmdump

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ncmdump.i18n.TranslationService
import com.ncmdump.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

private val Purple40 = Color(0xFF6650a4)
private val PurpleGrey40 = Color(0xFF625b71)
private val Pink40 = Color(0xFF7D5260)

class MainActivity : ComponentActivity() {

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.addFiles(uris)
        }
    }

    private val importTranslationLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importTranslation(uri)
        }
    }

    private val createTemplateLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.generateTemplate(this, uri)
        }
    }

    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize translation service
        TranslationService.init(this, languageCode = "zh")

        viewModel = MainViewModel(applicationContext)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Purple40,
                    secondary = PurpleGrey40,
                    tertiary = Pink40,
                )
            ) {
                MainScreen(
                    viewModel = viewModel,
                    onPickFiles = { filePickerLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                    onImportTranslation = { importTranslationLauncher.launch(arrayOf("application/json", "*/*")) },
                    onGenerateTemplate = { createTemplateLauncher.launch("translation_template.json") },
                )
            }
        }
    }
}

data class SelectedFile(
    val uri: Uri,
    val fileName: String,
    val status: FileStatus = FileStatus.PENDING,
)

enum class FileStatus {
    PENDING, DECRYPTING, SUCCESS, ERROR
}

class MainViewModel(context: android.content.Context) {

    private val appContext = context.applicationContext

    var files by mutableStateOf(listOf<SelectedFile>())
        private set

    var isDecrypting by mutableStateOf(false)
        private set

    var outputDir by mutableStateOf<File?>(null)
        private set

    init {
        outputDir = File(appContext.cacheDir, "ncmdump_output").also { it.mkdirs() }
    }

    fun addFiles(uris: List<Uri>) {
        val newFiles = uris.mapNotNull { uri ->
            val name = getFileName(uri)
            if (name != null) SelectedFile(uri, name) else null
        }
        files = files + newFiles
    }

    fun removeFile(index: Int) {
        files = files.toMutableList().apply { removeAt(index) }
    }

    fun clearFiles() {
        files = emptyList()
    }

    private fun getFileName(uri: Uri): String? {
        val cursor = appContext.contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) it.getString(idx) else null
            } else null
        }
    }

    suspend fun decryptAll(onProgress: (Int, FileStatus) -> Unit) {
        isDecrypting = true
        val outputDir = outputDir ?: File(appContext.cacheDir, "ncmdump_output").also { it.mkdirs() }
        val successDir = File(appContext.cacheDir, "ncmdump_done").also { it.mkdirs() }

        for (i in files.indices) {
            if (!isDecrypting) break

            val file = files[i]
            if (file.status != FileStatus.PENDING) continue

            withContext(Dispatchers.Main) { onProgress(i, FileStatus.DECRYPTING) }

            try {
                var decryptSuccess = false

                withContext(Dispatchers.IO) {
                    val tempInput = File(appContext.cacheDir, "input_${i}_${file.fileName}")
                    appContext.contentResolver.openInputStream(file.uri)?.use { input ->
                        FileOutputStream(tempInput).use { output ->
                            input.copyTo(output)
                        }
                    }

                    val result = NcmDecryptor.decrypt(
                        inputPath = tempInput.absolutePath,
                        outputDir = outputDir.absolutePath,
                    )

                    if (result.success && result.outputPath != null) {
                        copyToDownloads(result.outputPath, result.metadata)
                        val doneFile = File(successDir, file.fileName)
                        tempInput.renameTo(doneFile)
                        decryptSuccess = true
                    } else {
                        tempInput.delete()
                    }
                }

                withContext(Dispatchers.Main) {
                    val mutable = files.toMutableList()
                    mutable[i] = file.copy(status = if (decryptSuccess) FileStatus.SUCCESS else FileStatus.ERROR)
                    files = mutable
                    onProgress(i, if (decryptSuccess) FileStatus.SUCCESS else FileStatus.ERROR)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val mutable = files.toMutableList()
                    mutable[i] = file.copy(status = FileStatus.ERROR)
                    files = mutable
                    onProgress(i, FileStatus.ERROR)
                }
            }
        }

        isDecrypting = false
    }

    private fun copyToDownloads(sourcePath: String, metadata: NcmDecryptor.MusicMetadata?) {
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) return

        val fileName = sourceFile.name
        val mimeType = if (fileName.endsWith(".mp3")) "audio/mpeg" else "audio/flac"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/ncmdump")
                if (metadata != null) {
                    put(MediaStore.Audio.Media.TITLE, metadata.name)
                    put(MediaStore.Audio.Media.ARTIST, metadata.artist)
                    put(MediaStore.Audio.Media.ALBUM, metadata.album)
                }
            }

            val uri = appContext.contentResolver.insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values
            )

            if (uri != null) {
                appContext.contentResolver.openOutputStream(uri)?.use { output ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC
            )
            val ncmDir = File(downloadsDir, "ncmdump")
            ncmDir.mkdirs()
            val destFile = File(ncmDir, fileName)
            sourceFile.copyTo(destFile, overwrite = true)
        }
    }

    var snackbarMessage by mutableStateOf<String?>(null)
        private set

    fun importTranslation(uri: Uri) {
        val code = TranslationService.importTranslation(uri)
        if (code != null) {
            TranslationService.translator.setLanguage(code)
            snackbarMessage = tr("translation.importSuccess", code)
        } else {
            snackbarMessage = tr("translation.importFailed")
        }
    }

    fun generateTemplate(context: android.content.Context, uri: Uri) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                val template = buildTranslationTemplate()
                output.write(template.toByteArray(StandardCharsets.UTF_8))
            }
            snackbarMessage = tr("translation.templateGenerated")
        } catch (e: Exception) {
            snackbarMessage = tr("translation.templateFailed")
        }
    }

    private fun buildTranslationTemplate(): String {
        val keys = listOf(
            "app.name", "app.subtitle",
            "button.selectFiles", "button.clear", "button.decryptAll",
            "button.cancel", "button.remove",
            "status.pending", "status.decrypting", "status.success", "status.error",
            "stats.total", "stats.pending", "stats.done", "stats.failed",
            "message.noFiles", "message.decryptComplete",
            "message.decrypted", "message.failed", "message.decryptingHint",
            "label.outputDir",
            "lang.zh", "lang.en",
        )
        val sb = StringBuilder()
        sb.appendLine("{")
        // Group by prefix for prettier output
        val grouped = keys.groupBy { it.substringBefore(".") }
        val groupEntries = grouped.entries.toList()
        for ((gIdx, entry) in groupEntries.withIndex()) {
            val prefix = entry.key
            val groupKeys = entry.value
            sb.appendLine("  \"$prefix\": {")
            val subKeys = groupKeys.map { it.substringAfter(".") }
            for ((sIdx, sk) in subKeys.withIndex()) {
                val comma = if (sIdx < subKeys.size - 1) "," else ""
                sb.appendLine("    \"$sk\": \"\"$comma")
            }
            val comma = if (gIdx < groupEntries.size - 1) "," else ""
            sb.appendLine("  }$comma")
        }
        sb.appendLine("}")
        return sb.toString()
    }

    fun clearSnackbar() {
        snackbarMessage = null
    }

    fun cancelDecryption() {
        isDecrypting = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onPickFiles: () -> Unit,
    onImportTranslation: () -> Unit,
    onGenerateTemplate: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var languageExpanded by remember { mutableStateOf(false) }
    var currentLang by remember { mutableStateOf(TranslationService.translator.languageCode) }

    // Read language state to make all tr() calls reactive to language changes
    @Suppress("UNUSED_EXPRESSION")
    currentLang

    // Show snackbar when viewModel.snackbarMessage changes
    LaunchedEffect(viewModel.snackbarMessage) {
        viewModel.snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MusicNote, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(tr("app.name"))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { languageExpanded = true }) {
                            Icon(Icons.Default.Language, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = languageExpanded,
                            onDismissRequest = { languageExpanded = false },
                        ) {
                            TranslationService.translator.availableLanguages().forEach { code ->
                                DropdownMenuItem(
                                    text = { Text(tr("lang.$code")) },
                                    onClick = {
                                        TranslationService.translator.setLanguage(code)
                                        currentLang = code
                                        languageExpanded = false
                                    },
                                    leadingIcon = {
                                        if (code == currentLang) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(tr("translation.import")) },
                                onClick = {
                                    languageExpanded = false
                                    onImportTranslation()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.FileOpen, contentDescription = null)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("translation.generateTemplate")) },
                                onClick = {
                                    languageExpanded = false
                                    onGenerateTemplate()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Description, contentDescription = null)
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                )
            )
        },
        floatingActionButton = {
            if (viewModel.files.isNotEmpty() && !viewModel.isDecrypting) {
                ExtendedFloatingActionButton(
                    onClick = {
                        scope.launch {
                            viewModel.decryptAll { index, status ->
                                if (status == FileStatus.SUCCESS) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            tr("message.decrypted", viewModel.files[index].fileName)
                                        )
                                    }
                                } else if (status == FileStatus.ERROR) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            tr("message.failed", viewModel.files[index].fileName)
                                        )
                                    }
                                }
                            }
                            snackbarHostState.showSnackbar(tr("message.decryptComplete"))
                        }
                    },
                    icon = { Icon(Icons.Default.Download, contentDescription = null) },
                    text = { Text(tr("button.decryptAll")) },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onPickFiles,
                    enabled = !viewModel.isDecrypting,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(tr("button.selectFiles"))
                }

                if (viewModel.files.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { viewModel.clearFiles() },
                        enabled = !viewModel.isDecrypting,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        )
                    ) {
                        Icon(Icons.Default.ClearAll, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(tr("button.clear"))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (viewModel.files.isNotEmpty()) {
                val pendingCount = viewModel.files.count { it.status == FileStatus.PENDING }
                val successCount = viewModel.files.count { it.status == FileStatus.SUCCESS }
                val errorCount = viewModel.files.count { it.status == FileStatus.ERROR }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatChip(tr("stats.total"), viewModel.files.size, Purple40)
                    StatChip(tr("stats.pending"), pendingCount, Color.Gray)
                    StatChip(tr("stats.done"), successCount, Color(0xFF4CAF50))
                    StatChip(tr("stats.failed"), errorCount, Color(0xFFF44336))
                }

                Spacer(Modifier.height(12.dp))
            }

            AnimatedVisibility(visible = viewModel.isDecrypting) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = tr("message.decryptingHint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (viewModel.files.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.QueueMusic,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            tr("message.noFiles"),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(viewModel.files.withIndex().toList()) { (index, file) ->
                        FileCard(
                            file = file,
                            onRemove = { viewModel.removeFile(index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatChip(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun FileCard(file: SelectedFile, onRemove: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (file.status) {
                FileStatus.SUCCESS -> Color(0xFFE8F5E9)
                FileStatus.ERROR -> Color(0xFFFFEBEE)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when (file.status) {
                    FileStatus.PENDING -> Icons.Default.AudioFile
                    FileStatus.DECRYPTING -> Icons.Default.HourglassTop
                    FileStatus.SUCCESS -> Icons.Default.CheckCircle
                    FileStatus.ERROR -> Icons.Default.Error
                },
                contentDescription = null,
                tint = when (file.status) {
                    FileStatus.SUCCESS -> Color(0xFF4CAF50)
                    FileStatus.ERROR -> Color(0xFFF44336)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = tr("status.${file.status.name.lowercase()}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (file.status == FileStatus.PENDING) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = tr("button.remove"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
