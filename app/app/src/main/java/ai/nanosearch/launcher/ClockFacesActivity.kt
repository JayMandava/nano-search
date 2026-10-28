@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ai.nanosearch.launcher

import ai.nanosearch.launcher.clocks.ClockFaces
import ai.nanosearch.launcher.clocks.ClockFaceView
import ai.nanosearch.launcher.clocks.ClockMotionPolicy
import ai.nanosearch.launcher.clocks.PackClockApply
import ai.nanosearch.launcher.clocks.packs.*
import ai.nanosearch.launcher.ui.NanoTheme
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.content.Context
import android.view.View
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.platform.LocalLifecycleOwner
import java.text.NumberFormat

/** Data-only faces. Browsing and previewing never change a Home or wallpaper selection. */
class ClockFacesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this)[ClockFacesModel::class.java]
        setContent { NanoTheme { ClockFacesScreen(model) { finish() } } }
    }
}

@Composable
private fun ClockFacesScreen(model: ClockFacesModel, finish: () -> Unit) {
    val context = LocalContext.current
    val appVersion = remember(context) { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "installed app" }
    var backConfirmation by rememberSaveable { mutableStateOf(false) }
    var homeDialog by rememberSaveable { mutableStateOf(false) }
    var lockDialog by rememberSaveable { mutableStateOf(false) }
    var removalId by rememberSaveable { mutableStateOf<String?>(null) }
    var licenseId by rememberSaveable { mutableStateOf<String?>(null) }
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { resumeTick++; model.refreshPreviewTimeZone() } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importDocument(uri)
    }
    val chooseFile = {
        runCatching { import.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
            .onFailure { model.message("No document picker is available. Enable a file provider and try again.") }
        Unit
    }
    val state = model.snapshot
    val selected = model.selectedId
    val pack = state.packs.firstOrNull { it.id == selected }
    val builtin = selected?.takeIf { it == ClockFaces.STEEL_ID || it == ClockFaces.CARBON_ID }?.let(ClockFaces::find)
    val busy = model.operation != null || model.updateReview != null
    val usage = remember(selected, state.generation, model.operation, resumeTick) { selected?.let { PackClockApply.removalUsage(context, it) } }
    val lockSelected = remember(selected, model.operation, resumeTick) { selected != null && LockClock.face(context) == selected }
    val back = {
        if (model.operation != null) backConfirmation = true
        else if (selected != null) model.selectedId = null
        else finish()
    }
    BackHandler { back() }
    ClockNoticeExpiry(model.notice, model.noticeRevision, model::expireNotice)
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, topBar = {
        TopAppBar(title = { Text(pack?.spec?.name ?: builtin?.label ?: "Clock Faces") },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
        val detailSideLimit = clockDetailPreviewMaxSide(maxHeight)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            model.operation?.let { operation -> item(key = "operation") { OperationCard(operation, model::cancelOperation) } }
            model.notice?.let { notice -> item(key = "notice") {
                ClockNoticeCard(notice, model::dismissNotice)
            } }
            if (selected == null) {
                item { Text("A face collection for your Home clocks and optional Lock Clock wallpaper. Installing a face does not apply it.", style = MaterialTheme.typography.bodyLarge) }
                item { Button(onClick = chooseFile, enabled = !busy && state.ready && state.error == null, modifier = Modifier.fillMaxWidth()) { Text("Import face ZIP…") } }
                item { Text("Data-only analog faces, up to 4 MiB per ZIP. Files are copied privately; no lasting document access is kept.", style = MaterialTheme.typography.bodySmall) }
                item { SectionTitle("Built-in") }
                items(listOf(ClockFaces.STEEL_ID, ClockFaces.CARBON_ID), key = { it }) { id ->
                    FaceCard(model, id, ClockFaces.find(id).label, "Built-in · Always available offline", state.generation) { model.selectedId = id }
                }
                item { SectionTitle("Installed") }
                if (!state.ready) item { Text("Checking installed faces…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.error?.let { error -> item { Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Store errors are fixed app-owned messages, never raw provider exception text.
                    Text(error)
                    if (state.unavailable.isEmpty()) OutlinedButton(onClick = model::retryCollection, enabled = !busy) { Text("Retry collection") }
                    else Text("Use Retry check on each unavailable face below.", style = MaterialTheme.typography.bodySmall)
                } } } }
                if (state.ready && state.error == null && state.packs.isEmpty() && state.unavailable.isEmpty()) item { Text("No extra faces installed. Import a compatible ZIP to get started.") }
                items(state.packs, key = { it.id }) { installed ->
                    FaceCard(model, installed.id, installed.spec.name, "${sourceLabel(installed.source)} · Version ${installed.spec.packVersion}", state.generation) { model.selectedId = installed.id }
                }
                items(state.unavailable, key = { "unavailable-${it.id}" }) { unavailable ->
                    Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(unavailable.name, style = MaterialTheme.typography.titleMedium)
                        Text("Unavailable · ${sourceLabel(unavailable.source)}")
                        Text("This face could not be loaded. Existing selections safely show Steel Dial. Retry the local check; if memory is low, close other apps first. A damaged face can be removed and reimported from a trusted copy.")
                        OutlinedButton(onClick = { model.retry(unavailable.id) }, enabled = !busy) { Text("Retry check") }
                        TextButton(onClick = { removalId = unavailable.id }, enabled = !busy) { Text("Remove…") }
                    } }
                }
                item { SectionTitle("Available") }
                if (FacePackCatalog.entries.isEmpty()) item { Text("No curated downloads have been published yet. Built-in and installed faces work offline; Import ZIP is available now.") }
                items(FacePackCatalog.entries, key = { "catalog-${it.id}" }) { entry ->
                    val installed = state.packs.firstOrNull { it.source == FacePackSource.CURATED && it.spec.packId == entry.packId }
                    Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(entry.name, style = MaterialTheme.typography.titleMedium)
                        Text("Curated catalog · Version ${entry.version} · ${byteCount(entry.bytes)} ZIP")
                        Text("Verified against this app's pinned digest before installation. Preview and license details are available after validation.")
                        when {
                            installed != null && installed.spec.packVersion >= entry.version -> OutlinedButton(onClick = { model.selectedId = installed.id }) { Text("View installed face") }
                            else -> Button(onClick = { model.download(entry) }, enabled = !busy && state.ready && state.error == null) { Text(if (installed == null) "Download" else "Review update…") }
                        }
                    } }
                }
            } else if (pack != null || builtin != null) {
                val name = pack?.spec?.name ?: requireNotNull(builtin).label
                item { DetailFacePreview(model, selected, name, state.generation, detailSideLimit) }
                item { Text(if (pack != null) "Installed · ${sourceLabel(pack.source)}" else "Built-in · Included with Nano", style = MaterialTheme.typography.titleMedium) }
                if (pack != null) {
                    item { Text("Version ${pack.spec.packVersion} · ${byteCount(pack.payloadBytes)} installed payload") }
                    item { Text("Publisher: ${pack.spec.author}${if (pack.source == FacePackSource.IMPORTED) " (self-declared, not verified)" else " (pack metadata)"}") }
                    pack.spec.description?.let { item { Text(it) } }
                    item { Text("License: ${pack.spec.license}"); TextButton(onClick = { licenseId = pack.id }) { Text("Read license terms") } }
                    item { Text("Compatible with this Nano version · analog-v1${if (pack.spec.dateWindow != null) " with day-of-month" else ""}. No downloadable code, GMT or new complications.", style = MaterialTheme.typography.bodySmall) }
                    if (pack.source == FacePackSource.CURATED) item { Text("Downloaded content matched the catalog digest. This verifies the reviewed file, not independent publisher ownership.", style = MaterialTheme.typography.bodySmall) }
                } else {
                    builtin?.credit?.let { item { Text(it) } }
                    item { Text("Version: included with Nano $appVersion. Built-in artwork and license notices ship with the app; this face cannot be removed.") }
                }
                // resumeTick re-reads choices after the Android wallpaper picker or another settings screen.
                item { Text("Used by ${usage?.homeCount ?: 0} Home ${if (usage?.homeCount == 1) "clock" else "clocks"}${if (lockSelected) " · Selected for Lock Clock" else " · Not selected for Lock Clock"}.") }
                item { Button(onClick = { homeDialog = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Use on Home…") } }
                if (LockClock.available(context)) {
                    item { OutlinedButton(onClick = { lockDialog = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Use this face for Lock Clock…") } }
                    item { OutlinedButton(onClick = {
                        runCatching { LockClock.openPicker(context) }.onSuccess { model.message("Android's wallpaper picker opened. Only Android's confirmation applies wallpaper; returning here does not confirm it was applied.") }
                            .onFailure { model.message("Android's wallpaper picker is unavailable on this device.") }
                    }, enabled = !busy && lockSelected, modifier = Modifier.fillMaxWidth()) { Text("Set as wallpaper…") }
                        Text(if (lockSelected) "Android chooses Home, Lock or both where supported. Some devices also show a separate system clock."
                            else "Select this face for Lock Clock first. Wallpaper confirmation is a separate Android step.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (pack != null) {
                    if (pack.source == FacePackSource.IMPORTED) item { OutlinedButton(onClick = chooseFile, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Import newer version…") } }
                    item { TextButton(onClick = { removalId = pack.id }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Remove face…") } }
                }
            } else {
                item { Text("This face is no longer available. Existing selections safely fall back to Steel Dial.") }
                item { Button(onClick = { model.selectedId = null }) { Text("Back to collection") } }
            }
        }
        }
    }
    if (backConfirmation && model.operation != null) AlertDialog(onDismissRequest = { backConfirmation = false }, title = { Text("Operation in progress") },
        text = { Text(if (model.operation?.progress?.stage == FacePackStage.INSTALLING) "Finishing the change safely. Please stay until the result is known." else "Stay here, or cancel the operation and wait for cleanup. An existing installed version is kept until a successful update commits.") },
        confirmButton = { TextButton(onClick = { backConfirmation = false }) { Text("Stay") } },
        dismissButton = { TextButton(onClick = { backConfirmation = false; model.cancelOperation() }, enabled = model.operation?.let { it.cancellable && it.progress.stage != FacePackStage.INSTALLING && !it.cancelling } == true) { Text("Cancel operation") } })
    LaunchedEffect(model.operation) { if (model.operation == null) backConfirmation = false }
    model.updateReview?.let { review -> AlertDialog(onDismissRequest = model::dismissUpdate, title = { Text("Replace installed version?") },
        text = { Text("Install ${review.name}, version ${review.version}? The current version stays available if validation or installation fails. The file will be checked again and must match the content just reviewed. Existing Home and Lock Clock placements keep their choices and display the updated artwork immediately.") },
        confirmButton = { TextButton(onClick = model::confirmUpdate) { Text("Update face") } }, dismissButton = { TextButton(onClick = model::dismissUpdate) { Text("Keep current") } }) }
    if (homeDialog && selected != null) HomeTargetDialog(selected, onDismiss = { homeDialog = false }) { token, page -> homeDialog = false; model.applyHome(selected, token, page) }
    if (lockDialog && selected != null) {
        val active = remember(lockDialog, resumeTick) { runCatching { WallpaperManager.getInstance(context).wallpaperInfo?.component == LockClock.component(context) }.getOrDefault(false) }
        AlertDialog(onDismissRequest = { lockDialog = false }, title = { Text("Use for Lock Clock?") },
            text = { Text((if (active) "Lock Clock is your active wallpaper. Changing this choice changes its face immediately. " else "This saves Nano's Lock Clock face choice; it does not apply wallpaper. If Lock Clock is already active, its face changes immediately. ") + "Home clocks, background photo and position stay unchanged.") },
            confirmButton = { TextButton(onClick = { lockDialog = false; model.applyLock(selected) }) { Text("Use this face") } }, dismissButton = { TextButton(onClick = { lockDialog = false }) { Text("Cancel") } })
    }
    removalId?.let { id ->
        val usage = remember(id, state.generation) { PackClockApply.removalUsage(context, id) }
        AlertDialog(onDismissRequest = { removalId = null }, title = { Text("Remove face?") },
            text = { Text("This removes only the downloaded or imported face, not its original document. ${usage.homeCount} Home ${if (usage.homeCount == 1) "clock will" else "clocks will"} use Steel Dial instead.${if (usage.lockSelected) " Lock Clock will also use Steel Dial." else " The Lock Clock choice is unchanged."} Widget positions and sizes are kept.") },
            confirmButton = { TextButton(onClick = { removalId = null; model.remove(id) }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { removalId = null }) { Text("Keep face") } })
    }
    licenseId?.let { id -> state.packs.firstOrNull { it.id == id }?.let { licensed ->
        AlertDialog(onDismissRequest = { licenseId = null }, title = { Text("${licensed.spec.name} license") },
            text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) { Text("${licensed.spec.license} · Supplied by the pack publisher\n\n${licensed.licenseText}") } },
            confirmButton = { TextButton(onClick = { licenseId = null }) { Text("Close") } })
    } }
}

@Composable
private fun SectionTitle(title: String) { Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp).semantics { heading() }) }

@Composable
private fun FaceCard(model: ClockFacesModel, id: String, name: String, source: String, generation: Long, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FacePreview(model, id, name, generation, detail = false)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(name, style = MaterialTheme.typography.titleMedium); Text(source, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** The only animated preview is the visible detail. No View is retained after pause or leaving it. */
@Composable
private fun DetailFacePreview(model: ClockFacesModel, id: String, name: String, generation: Long, maxPreviewSide: Dp) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var motionAllowed by remember { mutableStateOf(false) }
    var live by rememberSaveable(id) { mutableStateOf(false) }
    DisposableEffect(context, lifecycle) {
        lateinit var policy: ClockMotionPolicy
        policy = ClockMotionPolicy(context) { motionAllowed = policy.smooth }
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        policy.attach()
        onDispose { policy.detach(); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumed, motionAllowed) { if (!resumed || !motionAllowed) live = false }
    val showingLive = live && resumed && motionAllowed
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showingLive) ClockFacePreviewFrame(maxPreviewSide) {
            AndroidView(
                factory = { DetailClockPreview(it, id, name) },
                modifier = Modifier.matchParentSize(),
                onReset = null,
                onRelease = { it.visibility = View.GONE },
                update = { it.faceId = id },
            )
        } else FacePreview(model, id, name, generation, detail = true, maxPreviewSide = maxPreviewSide)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Live local-time preview", Modifier.weight(1f))
            Switch(checked = showingLive, onCheckedChange = { live = it }, enabled = resumed && motionAllowed, modifier = Modifier.semantics { contentDescription = "Live local-time preview" })
        }
        Text(if (showingLive) "Live preview only; your Home and Lock Clock choices are unchanged."
            else "Static preview at 10:10, using the same renderer as Home and Lock Clock.", style = MaterialTheme.typography.bodySmall)
        if (!motionAllowed || !resumed) Text("Live preview stays off when motion is reduced, battery saver is on, system animations are disabled, or this screen is paused.", style = MaterialTheme.typography.bodySmall)
    }
}

/** Leave space for controls in short windows; the remaining detail always scrolls normally. */
internal fun clockDetailPreviewMaxSide(viewportHeight: Dp): Dp = minOf(360.dp, viewportHeight * .65f)

/**
 * The outer row consumes the available width; only the inner artwork is square. Applying
 * fillMaxWidth + capped height + aspectRatio to the SAME node gives impossible constraints in
 * landscape, so Compose can draw an oversized square while reporting a shorter layout height.
 * Here the artwork receives one exact, compatible width/height and cannot paint into metadata.
 */
@Composable
internal fun ClockFacePreviewFrame(maxSide: Dp, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxSide).coerceAtLeast(0.dp)
        Box(Modifier.size(side).clipToBounds(), contentAlignment = Alignment.Center, content = content)
    }
}

/** No clock-app click behavior or per-second accessibility announcements in a preview. */
internal class DetailClockPreview(context: Context, faceId: String, private val faceName: String) : ClockFaceView(context, faceId) {
    init { setOnClickListener(null); isClickable = false; isFocusable = false; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE }
    override fun accessibilityDescription(epochMillis: Long) = "$faceName live preview"
}

private data class FacePreviewResult(val complete: Boolean = false, val bitmap: Bitmap? = null)

@Composable
private fun FacePreview(model: ClockFacesModel, id: String, name: String, generation: Long, detail: Boolean, maxPreviewSide: Dp = 360.dp) {
    val zone = model.previewTimeZoneKey
    var retry by remember(id, generation, zone) { mutableIntStateOf(0) }
    val result by produceState(FacePreviewResult(), id, generation, detail, zone, retry) {
        value = FacePreviewResult()
        value = FacePreviewResult(complete = true, bitmap = model.preview(id, generation, if (detail) 720 else 240))
    }
    val artwork: @Composable BoxScope.() -> Unit = {
        val ready = result.bitmap
        if (ready != null) Image(ready.asImageBitmap(), "$name preview at 10:10", Modifier.matchParentSize())
        else Text(if (result.complete) "Preview unavailable" else "Loading preview…", style = MaterialTheme.typography.labelSmall)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (detail) ClockFacePreviewFrame(maxPreviewSide, content = artwork)
        else Box(Modifier.size(80.dp).clipToBounds(), contentAlignment = Alignment.Center, content = artwork)
        if (detail && result.complete && result.bitmap == null) TextButton(onClick = { retry++ }) { Text("Retry preview") }
    }
}

@Composable
private fun OperationCard(operation: FaceOperation, cancel: () -> Unit) {
    Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(operation.title, style = MaterialTheme.typography.titleMedium)
        val progress = operation.progress
        Text(if (operation.cancelling) "Cancelling and cleaning up…" else when (progress.stage) {
            FacePackStage.COPYING -> "Copying document…"
            FacePackStage.DOWNLOADING -> "Downloading…"
            FacePackStage.CHECKING -> "Checking format, compatibility and artwork…"
            FacePackStage.INSTALLING -> "Finishing…"
        })
        val total = progress.totalBytes
        if (total != null && total > 0 && progress.stage in listOf(FacePackStage.COPYING, FacePackStage.DOWNLOADING)) LinearProgressIndicator(progress = { (progress.bytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        if (progress.stage == FacePackStage.COPYING || progress.stage == FacePackStage.DOWNLOADING) Text(if (total == null) "${byteCount(progress.bytes)} received · total size unknown" else "${byteCount(progress.bytes)} of ${byteCount(total)}")
        if (operation.cancellable) TextButton(onClick = cancel, enabled = !operation.cancelling && progress.stage != FacePackStage.INSTALLING) { Text("Cancel operation") }
    } }
}

@Composable
private fun HomeTargetDialog(id: String, onDismiss: () -> Unit, confirm: (String?, String) -> Unit) {
    val context = LocalContext.current
    val placements = remember(id) { PackClockApply.homePlacements(context) }
    val pages = remember(id) { PackClockApply.pages(context) }
    var choice by rememberSaveable(id) { mutableStateOf<String?>(null) }
    val validChoice = placements.any { choice == "existing:${it.token}" } || pages.any { choice == "new:${it.key}" }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose one Home destination") },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Replace one existing clock without moving or resizing it, or add a new clock to a specific page.")
            placements.forEach { placement -> RadioChoice("existing:${placement.token}", "Replace ${placement.label}", choice) { choice = it } }
            pages.forEach { page -> RadioChoice("new:${page.key}", "Add a new clock to ${page.label}", choice) { choice = it } }
        } },
        confirmButton = { TextButton(onClick = {
            placements.firstOrNull { choice == "existing:${it.token}" }?.let { confirm(it.token, it.pageKey); return@TextButton }
            pages.firstOrNull { choice == "new:${it.key}" }?.let { confirm(null, it.key) }
        }, enabled = validChoice) { Text(if (choice?.startsWith("new:") == true) "Add one clock" else "Replace one clock") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun RadioChoice(key: String, label: String, selected: String?, choose: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected == key, onClick = { choose(key) }, role = Role.RadioButton), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected == key, onClick = null)
        Text(label, Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp))
    }
}

private fun sourceLabel(source: FacePackSource) = when (source) {
    FacePackSource.IMPORTED -> "Imported · Publisher unverified"
    FacePackSource.CURATED -> "Curated · Catalog digest verified"
}
private fun byteCount(bytes: Long) = "${NumberFormat.getIntegerInstance().format(bytes)} bytes"
