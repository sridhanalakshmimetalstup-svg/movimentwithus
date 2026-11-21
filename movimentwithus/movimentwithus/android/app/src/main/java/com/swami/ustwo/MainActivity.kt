package com.swami.ustwo

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Pink = Color(0xFFFF4F7A)

class MainActivity : ComponentActivity() {

    private lateinit var store: Store
    private var configured by mutableStateOf(false)
    private var unlocked by mutableStateOf(false)
    private var openLatest by mutableStateOf(false)
    private var sharedUri by mutableStateOf<Uri?>(null)

    /** Set right before launching the camera/photo picker so leaving the app doesn't re-lock it. */
    var keepUnlockedOnce = false

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        configured = store.isConfigured
        handleIntent(intent)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Pink, background = Color.Black, surface = Color.Black)) {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    when {
                        !configured -> SetupScreen(store) {
                            configured = true
                            unlocked = true
                            afterSetup()
                        }
                        !unlocked -> LockScreen(store) { unlocked = true }
                        else -> HomeScreen(
                            store = store,
                            openLatest = openLatest,
                            onOpenedLatest = { openLatest = false },
                            sharedUri = sharedUri,
                            onSharedConsumed = { sharedUri = null },
                            beforeExternalScreen = { keepUnlockedOnce = true },
                            onSignOut = {
                                RefreshWorker.cancelAll(this)
                                store.clear()
                                Cache.clearAll(this)
                                UsWidget.updateAll(this)
                                configured = false
                                unlocked = false
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        if (keepUnlockedOnce) keepUnlockedOnce = false else unlocked = false
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_OPEN_LATEST, false)) openLatest = true
        if (intent.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (uri != null) sharedUri = uri
        }
    }

    private fun afterSetup() {
        RefreshWorker.schedule(this)
        RefreshWorker.runNow(this)
        if (Build.VERSION.SDK_INT >= 33) {
            keepUnlockedOnce = true
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_OPEN_LATEST = "open_latest"
    }
}

// ---------------------------------------------------------------- setup

@Composable
private fun SetupScreen(store: Store, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var repo by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text("💌 UsTwo", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Connect the private GitHub repo you both share.", color = Color.Gray)
        OutlinedTextField(
            value = repo, onValueChange = { repo = it.trim() },
            label = { Text("Repo (owner/name or GitHub link)") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = token, onValueChange = { token = it.trim() },
            label = { Text("GitHub access token") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Your name (shown on your partner's widget)") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = pin, onValueChange = { if (it.all(Char::isDigit) && it.length <= 12) pin = it },
            label = { Text("New PIN (4+ digits)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = pin2, onValueChange = { if (it.all(Char::isDigit) && it.length <= 12) pin2 = it },
            label = { Text("Repeat PIN") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            onClick = {
                val parsed = parseRepo(repo)
                error = when {
                    parsed == null -> "Repo should look like owner/name"
                    token.isBlank() || name.isBlank() -> "Fill in every field"
                    pin.length < 4 -> "PIN must be at least 4 digits"
                    pin != pin2 -> "PINs don't match"
                    else -> null
                }
                if (error != null || parsed == null) return@Button
                busy = true
                scope.launch {
                    val cfg = Config(parsed.first, parsed.second, token, name.trim())
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            GitHub(cfg).checkAccess()
                            store.saveConfig(cfg)
                            store.setPin(pin)
                        }
                    }
                    busy = false
                    result.onSuccess { onDone() }.onFailure { error = friendlyError(it) }
                }
            }
        ) { Text(if (busy) "Checking…" else "Connect") }
    }
}

private fun parseRepo(input: String): Pair<String, String>? {
    val cleaned = input.trim()
        .removePrefix("https://").removePrefix("http://").removePrefix("github.com/")
        .removeSuffix("/").removeSuffix(".git")
    val parts = cleaned.split("/").filter { it.isNotBlank() }
    return if (parts.size == 2) parts[0] to parts[1] else null
}

// ---------------------------------------------------------------- lock

@Composable
private fun LockScreen(store: Store, onUnlock: () -> Unit) {
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun attempt() {
        if (checking || pin.isEmpty()) return
        val wait = store.lockoutRemainingMs()
        if (wait > 0) {
            error = "Too many wrong PINs. Try again in ${wait / 1000 + 1}s."
            return
        }
        checking = true
        val entered = pin
        scope.launch {
            val ok = withContext(Dispatchers.Default) { store.checkPin(entered) }
            checking = false
            if (ok) {
                store.resetFailures()
                onUnlock()
            } else {
                store.recordFailure()
                pin = ""
                error = "Wrong PIN"
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🔒", fontSize = 56.sp)
        Spacer(Modifier.height(12.dp))
        Text("UsTwo is locked", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.all(Char::isDigit) && it.length <= 12) pin = it },
            label = { Text("PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { attempt() }),
            modifier = Modifier.width(220.dp).focusRequester(focus)
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { attempt() }, enabled = !checking, modifier = Modifier.width(220.dp)) {
            Text("Unlock")
        }
    }
}

// ---------------------------------------------------------------- home

@Composable
private fun HomeScreen(
    store: Store,
    openLatest: Boolean,
    onOpenedLatest: () -> Unit,
    sharedUri: Uri?,
    onSharedConsumed: () -> Unit,
    beforeExternalScreen: () -> Unit,
    onSignOut: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var feed by remember { mutableStateOf(store.feed) }
    var refreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Prepared?>(null) }
    var caption by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Post?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Sync.refresh(ctx) } }
            refreshing = false
            feed = store.feed
            refreshTick++
            r.onFailure { message = friendlyError(it) }
        }
    }

    fun prepare(uri: Uri) {
        message = null
        scope.launch {
            val p = withContext(Dispatchers.IO) { runCatching { Images.prepareUpload(ctx, uri) }.getOrNull() }
            if (p == null) message = "Couldn't read that image" else pending = p
        }
    }

    fun send() {
        val p = pending ?: return
        sending = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val cfg = store.config ?: error("Not signed in")
                    val post = GitHub(cfg).post(p.jpeg, caption.trim())
                    Cache.put(ctx, post.path, p.jpeg)
                    Sync.refresh(ctx)
                }
            }
            sending = false
            r.onSuccess {
                pending = null
                caption = ""
                feed = store.feed
                refreshTick++
                message = "Sent 💌"
            }.onFailure { message = friendlyError(it) }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) prepare(uri)
    }
    val cameraUri = remember {
        val f = File(ctx.cacheDir, "camera/shot.jpg").apply { parentFile?.mkdirs() }
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) prepare(cameraUri)
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(sharedUri) {
        sharedUri?.let {
            prepare(it)
            onSharedConsumed()
        }
    }
    LaunchedEffect(openLatest, feed.firstOrNull()?.path) {
        if (openLatest && feed.isNotEmpty()) {
            viewing = feed.first()
            onOpenedLatest()
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("💌 UsTwo", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else TextButton(onClick = { refresh() }) { Text("Refresh") }
                TextButton(onClick = { confirmSignOut = true }) { Text("Sign out", color = Color.Gray) }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            val p = pending
            if (p != null) {
                Composer(
                    preview = p.preview, caption = caption, onCaption = { caption = it },
                    sending = sending, onSend = { send() }, onCancel = { pending = null; caption = "" }
                )
            } else {
                LatestCard(feed.firstOrNull(), refreshTick) { viewing = it }
            }
        }

        if (pending == null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { beforeExternalScreen(); camera.launch(cameraUri) },
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) { Text("📷  Camera") }
                    OutlinedButton(
                        onClick = {
                            beforeExternalScreen()
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) { Text("🖼  Gallery") }
                }
            }
        }

        message?.let { msg ->
            item(span = { GridItemSpan(maxLineSpan) }) { Text(msg, color = Color.LightGray) }
        }

        if (feed.size > 1) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Memories", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            }
            items(feed.drop(1), key = { it.path }) { post ->
                RemoteImage(
                    path = post.path, maxSide = 360,
                    modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).clickable { viewing = post },
                    contentScale = ContentScale.Crop
                )
            }
        }
    }

    viewing?.let { Viewer(it) { viewing = null } }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("This removes the token and PIN from this phone. Photos stay in GitHub.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun LatestCard(post: Post?, tick: Int, onOpen: (Post) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF1C1C1E))
    ) {
        if (post == null) {
            Text("No photos yet — send the first one 💌", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
        } else {
            RemoteImage(
                path = post.path, maxSide = 1200, tick = tick,
                modifier = Modifier.fillMaxSize().clickable { onOpen(post) },
                contentScale = ContentScale.Crop
            )
            Caption(post, Modifier.align(Alignment.BottomStart))
        }
    }
}

@Composable
private fun Composer(
    preview: Bitmap, caption: String, onCaption: (String) -> Unit,
    sending: Boolean, onSend: () -> Unit, onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(
            bitmap = preview.asImageBitmap(), contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp))
        )
        OutlinedTextField(
            value = caption, onValueChange = { if (it.length <= 120) onCaption(it) },
            label = { Text("Caption (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel, enabled = !sending, modifier = Modifier.weight(1f).height(52.dp)) {
                Text("Cancel")
            }
            Button(
                onClick = onSend, enabled = !sending,
                colors = ButtonDefaults.buttonColors(containerColor = Pink),
                modifier = Modifier.weight(1f).height(52.dp)
            ) { Text(if (sending) "Sending…" else "Send 💌") }
        }
    }
}

@Composable
private fun Caption(post: Post, modifier: Modifier = Modifier) {
    val ago = DateUtils.getRelativeTimeSpanString(post.ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Text("${post.by} · $ago", color = Color.White, fontWeight = FontWeight.SemiBold)
        if (post.caption.isNotBlank()) Text(post.caption, color = Color.White)
    }
}

@Composable
private fun Viewer(post: Post, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose)) {
            RemoteImage(post.path, maxSide = 2000, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Caption(post, Modifier.align(Alignment.BottomStart))
        }
    }
}

@Composable
private fun RemoteImage(
    path: String,
    maxSide: Int,
    modifier: Modifier,
    contentScale: ContentScale,
    tick: Int = 0,
) {
    val ctx = LocalContext.current
    var bmp by remember(path, tick) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(path, tick) { mutableStateOf(false) }
    LaunchedEffect(path, tick) {
        val b = withContext(Dispatchers.IO) { runCatching { Cache.bitmap(ctx, path, maxSide) }.getOrNull() }
        if (b == null) failed = true else bmp = b
    }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier.background(Color(0xFF1C1C1E)), contentAlignment = Alignment.Center) {
            if (failed) Text("⚠️", color = Color.Gray) else CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}
