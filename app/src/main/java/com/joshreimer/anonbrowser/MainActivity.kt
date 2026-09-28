package com.joshreimer.anonbrowser

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.joshreimer.anonbrowser.ui.theme.AnonBrowserTheme
import com.joshreimer.anonbrowser.ui.theme.TorPurple
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    // Held here (not read straight off the service) so the Composable below always calls
    // collectAsState on one stable flow — never conditionally on whether the service is
    // bound yet, which would make Compose call different composables across recompositions.
    private val torStateFlow = MutableStateFlow<TorState>(TorState.Stopped)
    private val bridgeWarningFlow = MutableStateFlow<String?>(null)
    private var torService: TorService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as TorService.LocalBinder).service
            torService = service
            lifecycleScope.launch {
                service.torManager.state.collect { torStateFlow.value = it }
            }
            lifecycleScope.launch {
                service.torManager.bridgeWarning.collect { bridgeWarningFlow.value = it }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            torService = null
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Status bar stays a fixed brand purple regardless of light/dark theme; the rest of
        // the UI (set via AnonBrowserTheme below) follows the device's light/dark setting.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(TorPurple.toArgb()))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val serviceIntent = Intent(this, TorService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            AnonBrowserTheme {
                Surface {
                    BrowserScreen(
                        torStateFlow = torStateFlow,
                        bridgeWarningFlow = bridgeWarningFlow,
                        onNewIdentity = { torService?.torManager?.newIdentity() ?: false },
                        onApplyBridgeSettings = { enabled, text ->
                            torService?.applyBridgeSettings(enabled, text)
                        },
                        onFetchCircuit = { forOnionTarget ->
                            torService?.torManager?.getCurrentCircuit(forOnionTarget)
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        unbindService(connection)
        super.onDestroy()
    }
}

/** Per-tab state. [webView] is owned imperatively (created/destroyed in the AndroidView
 * `update` block below) — it's not something Compose should snapshot-track itself, unlike
 * the other fields here which are plain mutableState so the tab strip/address bar react to
 * navigation happening inside that tab. */
private class TabState(val id: Long, initialUrl: String = "") {
    var webView: WebView? = null
    var title by mutableStateOf("")
    var url by mutableStateOf(initialUrl)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var loadProgress by mutableStateOf(0)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserScreen(
    torStateFlow: StateFlow<TorState>,
    bridgeWarningFlow: StateFlow<String?>,
    onNewIdentity: suspend () -> Boolean,
    onApplyBridgeSettings: (enabled: Boolean, text: String) -> Unit,
    onFetchCircuit: suspend (forOnionTarget: Boolean) -> CircuitInfo?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val torState by torStateFlow.collectAsState()
    val bridgeWarning by bridgeWarningFlow.collectAsState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboardManager = LocalClipboardManager.current
    val focusRequester = remember { FocusRequester() }
    var showBridgesSheet by remember { mutableStateOf(false) }
    var showCircuitSheet by remember { mutableStateOf(false) }

    var proxyReady by remember { mutableStateOf(false) }
    var addressBarText by remember { mutableStateOf("") }
    var isEditingAddress by remember { mutableStateOf(false) }
    var previousAddress by remember { mutableStateOf("") }

    var nextTabId by remember { mutableStateOf(1L) }
    val tabs = remember {
        mutableStateListOf(TabState(id = 0L, initialUrl = "https://check.torproject.org/"))
    }
    var activeTabId by remember { mutableStateOf(0L) }
    val activeTab = tabs.find { it.id == activeTabId } ?: tabs.first()

    fun addNewTab() {
        val tab = TabState(id = nextTabId)
        nextTabId += 1
        tabs.add(tab)
        activeTabId = tab.id
    }

    fun closeTab(id: Long) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index == -1) return
        val closed = tabs.removeAt(index)
        closed.webView?.destroy()
        if (tabs.isEmpty()) {
            val fresh = TabState(id = nextTabId)
            nextTabId += 1
            tabs.add(fresh)
            activeTabId = fresh.id
        } else if (activeTabId == id) {
            activeTabId = tabs[index.coerceAtMost(tabs.size - 1)].id
        }
    }

    // Wire the WebView proxy (global — one override affects every WebView in the process)
    // to Tor's SOCKS5 port only once Tor is actually bootstrapped, so no tab's requests can
    // leave before the proxy override is in place.
    LaunchedEffect(torState) {
        val running = torState as? TorState.Running ?: return@LaunchedEffect
        if (proxyReady) return@LaunchedEffect
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return@LaunchedEffect

        val config = ProxyConfig.Builder()
            .addProxyRule("socks5://127.0.0.1:${running.socksPort}")
            .build()
        ProxyController.getInstance().setProxyOverride(config, { it.run() }) {
            proxyReady = true
        }
    }

    // Keep the address bar in sync with whichever tab is active / however it navigates,
    // but never while the user is actively editing it.
    LaunchedEffect(activeTabId, activeTab.url, isEditingAddress) {
        if (!isEditingAddress) {
            addressBarText = activeTab.url
        }
    }

    BackHandler(enabled = activeTab.canGoBack || tabs.size > 1) {
        if (activeTab.canGoBack) {
            activeTab.webView?.goBack()
        } else if (tabs.size > 1) {
            closeTab(activeTab.id)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Paints purple behind the status bar inset itself: enableEdgeToEdge's scrim color
        // (set in MainActivity.onCreate) only reliably paints on API < 29 — on 29+ the bar is
        // transparent, so this is what actually makes the status bar area read as purple.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(TorPurple)
                .align(Alignment.TopStart)
        )

        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            TabStrip(
                tabs = tabs,
                activeTabId = activeTabId,
                onSelect = { activeTabId = it },
                onClose = ::closeTab,
                onNewTab = ::addNewTab
            )

            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                TextField(
                    value = addressBarText,
                    onValueChange = { addressBarText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused && !isEditingAddress) {
                                isEditingAddress = true
                                previousAddress = addressBarText
                                addressBarText = ""
                            } else if (!focusState.isFocused && isEditingAddress) {
                                isEditingAddress = false
                                if (addressBarText.isBlank()) {
                                    addressBarText = previousAddress
                                }
                            }
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            if (proxyReady && addressBarText.isNotBlank()) {
                                activeTab.webView?.loadUrl(normalizeUrl(addressBarText))
                            }
                            isEditingAddress = false
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                    )
                )

                if (isEditingAddress && previousAddress.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = previousAddress,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            addressBarText = previousAddress
                            focusRequester.requestFocus()
                        }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit previous address")
                        }
                        IconButton(onClick = {
                            clipboardManager.setText(AnnotatedString(previousAddress))
                        }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy previous address")
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { activeTab.webView?.goBack() }, enabled = activeTab.canGoBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                IconButton(onClick = { activeTab.webView?.goForward() }, enabled = activeTab.canGoForward) {
                    Icon(Icons.Filled.ArrowForward, contentDescription = "Forward")
                }
                IconButton(onClick = { activeTab.webView?.reload() }, enabled = proxyReady) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                }

                Spacer(Modifier.weight(1f))

                IconButton(
                    onClick = {
                        scope.launch {
                            if (onNewIdentity()) activeTab.webView?.reload()
                        }
                    },
                    enabled = torState is TorState.Running
                ) {
                    Icon(Icons.Filled.Shuffle, contentDescription = "New identity")
                }

                IconButton(
                    onClick = { showCircuitSheet = true },
                    enabled = torState is TorState.Running
                ) {
                    Icon(Icons.Filled.Layers, contentDescription = "View Tor circuit")
                }

                IconButton(onClick = { showBridgesSheet = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Bridge settings")
                }
            }

            TorStatusBar(torState, proxyReady)

            if (bridgeWarning != null) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text(
                        text = bridgeWarning ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (proxyReady && activeTab.loadProgress in 1..99) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Box(modifier = Modifier.weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context -> FrameLayout(context) },
                    update = { container ->
                        if (proxyReady) {
                            for (tab in tabs) {
                                if (tab.webView == null) {
                                    val webView = createTabWebView(container.context, tab)
                                    webView.tag = tab.id
                                    tab.webView = webView
                                    container.addView(
                                        webView,
                                        ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    )
                                    if (tab.url.isNotBlank()) {
                                        webView.loadUrl(tab.url)
                                    }
                                }
                            }
                        }

                        val liveIds = tabs.map { it.id }.toSet()
                        for (i in container.childCount - 1 downTo 0) {
                            val child = container.getChildAt(i)
                            val childId = child.tag as? Long
                            if (childId != null && childId !in liveIds) {
                                container.removeViewAt(i)
                                (child as? WebView)?.destroy()
                            }
                        }

                        for (tab in tabs) {
                            tab.webView?.visibility = if (tab.id == activeTabId) View.VISIBLE else View.GONE
                        }
                    }
                )

                if (!proxyReady) {
                    TorConnectingOverlay(
                        state = torState,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }

        if (showBridgesSheet) {
            BridgesSheet(
                initialEnabled = remember { BridgePrefs.isEnabled(context) },
                initialText = remember { BridgePrefs.getText(context) },
                onDismiss = { showBridgesSheet = false },
                onSave = { enabled, text -> onApplyBridgeSettings(enabled, text) }
            )
        }

        if (showCircuitSheet) {
            CircuitSheet(
                onFetch = { onFetchCircuit(isOnionAddress(activeTab.url)) },
                onDismiss = { showCircuitSheet = false }
            )
        }
    }
}

@Composable
private fun TabStrip(
    tabs: List<TabState>,
    activeTabId: Long,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNewTab: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (tab in tabs) {
                val selected = tab.id == activeTabId
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 2.dp, vertical = 4.dp)
                        .widthIn(max = 140.dp)
                        .clickable { onSelect(tab.id) },
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp)
                    ) {
                        Text(
                            text = tab.title.ifBlank { tab.url.ifBlank { "New Tab" } },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Close tab",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
        IconButton(onClick = onNewTab) {
            Icon(Icons.Filled.Add, contentDescription = "New tab")
        }
    }
}

@Composable
private fun TorStatusBar(state: TorState, proxyReady: Boolean) {
    val text = when {
        state is TorState.Stopped -> "Tor: stopped"
        state is TorState.Starting -> "Tor: bootstrapping ${state.bootstrapPercent}%"
        state is TorState.Running && !proxyReady -> "Tor: connected, wiring browser proxy…"
        state is TorState.Running -> "Tor: connected"
        state is TorState.Failed -> "Tor: failed — ${state.message}"
        else -> ""
    }
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TorConnectingOverlay(state: TorState, modifier: Modifier = Modifier) {
    val targetProgress = when (state) {
        is TorState.Starting -> state.bootstrapPercent / 100f
        is TorState.Running -> 1f
        else -> 0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "torBootstrapProgress"
    )

    // A slow "breathing" glow behind the ring — makes it obvious something's actively
    // happening even during the stretches where the bootstrap percentage itself barely moves.
    val infiniteTransition = rememberInfiniteTransition(label = "torPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                        alpha = pulseAlpha
                    }
                    .background(TorPurple, CircleShape)
            )
            CircularProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.size(112.dp),
                strokeWidth = 6.dp,
                color = TorPurple,
                trackColor = TorPurple.copy(alpha = 0.15f)
            )
            Text(
                text = "${(animatedProgress * 100).roundToInt()}%",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = when (state) {
                is TorState.Starting -> "Establishing your Tor circuit…"
                is TorState.Failed -> "Couldn't connect to Tor"
                else -> "Connecting to the Tor network…"
            },
            style = MaterialTheme.typography.titleMedium
        )
        if (state is TorState.Failed) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BridgesSheet(
    initialEnabled: Boolean,
    initialText: String,
    onDismiss: () -> Unit,
    onSave: (enabled: Boolean, text: String) -> Unit
) {
    var enabled by remember { mutableStateOf(initialEnabled) }
    var text by remember { mutableStateOf(initialText) }
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Tor Bridges", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "If Tor is blocked on your network, get bridge lines from bridges.torproject.org " +
                    "and paste them below, one per line. obfs4 and webtunnel bridges are supported; " +
                    "snowflake isn't yet.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = enabled, onCheckedChange = { enabled = it })
                Spacer(Modifier.width(8.dp))
                Text("Use bridges")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().height(160.dp),
                enabled = enabled,
                placeholder = { Text("obfs4 192.0.2.1:443 FINGERPRINT cert=... iat-mode=0") }
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    onSave(enabled, text)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save and reconnect")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CircuitSheet(
    onFetch: suspend () -> CircuitInfo?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var circuit by remember { mutableStateOf<CircuitInfo?>(null) }
    var loading by remember { mutableStateOf(true) }

    fun refresh() {
        loading = true
        scope.launch {
            circuit = onFetch()
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Tor Circuit", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { refresh() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh circuit")
                }
            }
            val snapshot = circuit
            Text(
                if (snapshot?.isOnionCircuit == true) {
                    "Hidden services are reached entirely inside the Tor network via a " +
                        "rendezvous point — there's no exit relay, because the destination " +
                        "never touches the public internet."
                } else {
                    "Your traffic is routed through three relays, each only knowing the hop " +
                        "before and after it — like layers of an onion."
                },
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(16.dp))

            when {
                loading -> {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = TorPurple)
                    }
                }
                snapshot == null -> {
                    Text(
                        "No active circuit yet. Load a page, then check back.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                else -> {
                    Column {
                        CircuitHopRow(label = "This device", subtitle = null, ringCount = 1, isFirst = true, isLast = false)
                        snapshot.hops.forEachIndexed { index, hop ->
                            CircuitHopRow(
                                label = "${hop.role} · ${hop.nickname}",
                                subtitle = listOfNotNull(
                                    countryLabel(hop.countryCode),
                                    hop.ipAddress,
                                    shortenFingerprint(hop.fingerprint)
                                ).joinToString("  ·  "),
                                ringCount = index + 2,
                                isFirst = false,
                                isLast = false
                            )
                        }
                        if (snapshot.isOnionCircuit) {
                            CircuitHopRow(
                                label = "Hidden service",
                                subtitle = "No exit relay — reached inside Tor via the rendezvous point above",
                                ringCount = snapshot.hops.size + 2,
                                isFirst = false,
                                isLast = true
                            )
                        } else {
                            CircuitHopRow(
                                label = "Destination site",
                                subtitle = "Only the exit relay knows this",
                                ringCount = snapshot.hops.size + 2,
                                isFirst = false,
                                isLast = true
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CircuitHopRow(
    label: String,
    subtitle: String?,
    ringCount: Int,
    isFirst: Boolean,
    isLast: Boolean
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(44.dp)) {
            Box(
                modifier = Modifier.width(2.dp).height(10.dp)
                    .background(if (isFirst) androidx.compose.ui.graphics.Color.Transparent else TorPurple.copy(alpha = 0.35f))
            )
            Box(contentAlignment = Alignment.Center) {
                for (ring in 0 until ringCount.coerceIn(1, 4)) {
                    Box(
                        modifier = Modifier
                            .size((34 - ring * 6).dp)
                            .border(1.5.dp, TorPurple.copy(alpha = 0.2f + ring * 0.15f), CircleShape)
                    )
                }
                Box(modifier = Modifier.size(8.dp).background(TorPurple, CircleShape))
            }
            Box(
                modifier = Modifier.width(2.dp).height(10.dp)
                    .background(if (isLast) androidx.compose.ui.graphics.Color.Transparent else TorPurple.copy(alpha = 0.35f))
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun shortenFingerprint(fp: String): String =
    if (fp.length > 12) "${fp.take(6)}…${fp.takeLast(4)}" else fp

private fun isOnionAddress(url: String): Boolean =
    android.net.Uri.parse(url).host?.endsWith(".onion") == true

/** Regional-indicator flag emoji + display name for an ISO 3166-1 alpha-2 country code, e.g.
 * "DE" -> "🇩🇪 Germany". Each regional-indicator symbol sits [FLAG_EMOJI_BASE] above its
 * letter's position in the alphabet, so "DE" becomes the pair of symbols at D and E. */
private fun countryLabel(countryCode: String?): String? {
    if (countryCode == null || countryCode.length != 2) return null
    val upper = countryCode.uppercase()
    if (upper.any { it !in 'A'..'Z' }) return null
    val flag = upper.map { letter -> String(Character.toChars(FLAG_EMOJI_BASE + (letter - 'A'))) }
        .joinToString("")
    val name = java.util.Locale("", upper).displayCountry.takeIf { it.isNotBlank() } ?: upper
    return "$flag $name"
}

private const val FLAG_EMOJI_BASE = 0x1F1E6 // regional indicator symbol letter 'A'

private fun createTabWebView(context: Context, tab: TabState): WebView {
    val webView = WebView(context)
    webView.settings.javaScriptEnabled = true
    webView.settings.domStorageEnabled = true
    webView.settings.mediaPlaybackRequiresUserGesture = true
    webView.settings.setGeolocationEnabled(false)
    webView.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE

    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    cookieManager.setAcceptThirdPartyCookies(webView, false)

    webView.webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            url?.let { tab.url = it }
            tab.canGoBack = view.canGoBack()
            tab.canGoForward = view.canGoForward()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            tab.canGoBack = view.canGoBack()
            tab.canGoForward = view.canGoForward()
        }
    }

    webView.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            tab.loadProgress = newProgress
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            tab.title = title?.takeIf { it.isNotBlank() } ?: tab.title
        }

        // Deny camera/mic (WebRTC) by default: over Tor, ICE/STUN can leak local-network
        // info and the requests won't work reliably anyway.
        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback
        ) {
            callback.invoke(origin, false, false)
        }
    }

    return webView
}

private fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    return when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.contains(" ") || !trimmed.contains(".") ->
            "https://duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
        else -> "https://$trimmed"
    }
}
