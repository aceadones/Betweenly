package app.loviedowpie

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.loviedowpie.ui.LdBottomBar
import app.loviedowpie.ui.LdTab
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.loviedowpie.ui.ActivityScreen
import app.loviedowpie.ui.CameraScreen
import app.loviedowpie.ui.ChatController
import app.loviedowpie.ui.ChatScreen
import app.loviedowpie.ui.HomeScreen
import app.loviedowpie.ui.LoviedowpieTheme
import app.loviedowpie.ui.OnboardingScreen
import app.loviedowpie.ui.PairScreen
import app.loviedowpie.ui.PrivacyScreen
import app.loviedowpie.ui.ProfileScreen
import app.loviedowpie.ui.TrackScreen
import app.loviedowpie.ui.UiFlags
import app.loviedowpie.ui.WidgetRefresh
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The only activity. Onboarding and pairing fill the window. After pairing,
 * a bottom bar switches Activity, Chat, Location, and Profile. The map and
 * the camera sit on top of that bar.
 *
 * Widget and notification extras: `open=track`, `open=chat`, `open=activity`.
 * The V1 widget still sends `open=settings`; that opens Profile.
 *
 * Pulls the inbox on resume and every 5 seconds while this window is open,
 * so chat and drawings arrive without a wake ping. Live location uses the
 * same pull.
 */
class MainActivity : ComponentActivity() {
    val routeEvent = mutableIntStateOf(0)
    var route: String? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars()
        if (Store.isPaired(this)) UiFlags.setOnboardingDone(this)
        ChatStore.loadUnread(this)
        WidgetRefresh.ensure(this)
        val open = if (savedInstanceState == null) intent?.getStringExtra(EXTRA_OPEN) else null
        if (savedInstanceState == null) {
            route = open
        }
        setContent {
            LoviedowpieTheme {
                Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
                    Root(open)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route = intent.getStringExtra(EXTRA_OPEN)
        routeEvent.intValue = routeEvent.intValue + 1
    }

    override fun onResume() {
        super.onResume()
        AppState.foreground = true
    }

    override fun onPause() {
        AppState.foreground = false
        super.onPause()
    }

    private fun applySystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        window.setBackgroundDrawable(ColorDrawable(getColor(R.color.window_background)))
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !night
        controller.isAppearanceLightNavigationBars = !night
    }

    companion object {
        const val EXTRA_OPEN = "open"
    }
}

private enum class Gate { Onboarding, Pair, Main, Privacy }

private enum class Tab { Activity, Chat, Location, Profile }

private enum class Overlay { None, Track, Camera }

@Composable
private fun Root(startOpen: String?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as MainActivity
    val initial = remember { startRoute(context, startOpen) }
    var gateName by rememberSaveable { mutableStateOf(initial.gate.name) }
    var tabName by rememberSaveable { mutableStateOf(initial.tab.name) }
    var overlayName by rememberSaveable { mutableStateOf(initial.overlay.name) }
    var privacyFrom by rememberSaveable { mutableStateOf(Gate.Onboarding.name) }
    val gate = gateName.toGate()
    val tab = tabName.toTab()
    val overlay = overlayName.toOverlay()

    val event = activity.routeEvent.intValue
    LaunchedEffect(event) {
        if (!Store.isPaired(context)) return@LaunchedEffect
        when (activity.route) {
            "track" -> {
                gateName = Gate.Main.name
                tabName = Tab.Location.name
                overlayName = Overlay.Track.name
            }
            "chat" -> {
                gateName = Gate.Main.name
                tabName = Tab.Chat.name
                overlayName = Overlay.None.name
            }
            "activity" -> {
                gateName = Gate.Main.name
                tabName = Tab.Activity.name
                overlayName = Overlay.None.name
            }
            // V1 widget. Profile replaced the settings screen.
            "settings" -> {
                gateName = Gate.Main.name
                tabName = Tab.Profile.name
                overlayName = Overlay.None.name
            }
        }
    }

    val revision by Store.changes.collectAsState()
    LaunchedEffect(revision, gateName) {
        if (Store.isPaired(context)) return@LaunchedEffect
        val current = gateName.toGate()
        if (current == Gate.Onboarding || current == Gate.Pair || current == Gate.Privacy) return@LaunchedEffect
        overlayName = Overlay.None.name
        gateName = if (UiFlags.onboardingDone(context)) Gate.Pair.name else Gate.Onboarding.name
    }

    InboxPump(active = gate == Gate.Main)

    val leaveOverlay = overlay != Overlay.None || gate == Gate.Privacy
    BackHandler(enabled = leaveOverlay) {
        when {
            gate == Gate.Privacy -> gateName = privacyFrom
            overlay == Overlay.Camera -> overlayName = Overlay.None.name
            overlay == Overlay.Track -> overlayName = Overlay.None.name
        }
    }

    when {
        gate == Gate.Onboarding -> OnboardingScreen(
            onDone = { gateName = Gate.Pair.name },
            onPrivacy = {
                privacyFrom = Gate.Onboarding.name
                gateName = Gate.Privacy.name
            },
        )
        gate == Gate.Pair -> PairScreen(onPaired = { gateName = Gate.Main.name })
        gate == Gate.Privacy -> PrivacyScreen(onBack = { gateName = privacyFrom })
        overlay == Overlay.Track -> TrackScreen(onBack = { overlayName = Overlay.None.name })
        overlay == Overlay.Camera -> CameraRoute(onClose = { overlayName = Overlay.None.name })
        else -> MainShell(
            tab = tab,
            onTab = { tabName = it.name },
            onTrack = { overlayName = Overlay.Track.name },
            onCamera = { overlayName = Overlay.Camera.name },
            onPrivacy = {
                privacyFrom = Gate.Main.name
                gateName = Gate.Privacy.name
            },
            onUnpaired = {
                overlayName = Overlay.None.name
                gateName = Gate.Pair.name
            },
        )
    }
}

@Composable
private fun MainShell(
    tab: Tab,
    onTab: (Tab) -> Unit,
    onTrack: () -> Unit,
    onCamera: () -> Unit,
    onPrivacy: () -> Unit,
    onUnpaired: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val unread by ChatStore.unreadCount.collectAsState()
    LaunchedEffect(Unit) { ChatStore.loadUnread(context) }
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
        bottomBar = {
            LdBottomBar(
                tabs = listOf(
                    LdTab(
                        stringResource(R.string.tab_activity),
                        Icons.Outlined.Edit,
                        Icons.Filled.Edit,
                        tab == Tab.Activity,
                    ) { onTab(Tab.Activity) },
                    LdTab(
                        stringResource(R.string.tab_chat),
                        Icons.Outlined.Email,
                        Icons.Filled.Email,
                        tab == Tab.Chat,
                        badge = if (tab == Tab.Chat) 0 else unread,
                    ) { onTab(Tab.Chat) },
                    LdTab(
                        stringResource(R.string.tab_location),
                        Icons.Outlined.LocationOn,
                        Icons.Filled.LocationOn,
                        tab == Tab.Location,
                    ) { onTab(Tab.Location) },
                    LdTab(
                        stringResource(R.string.tab_profile),
                        Icons.Outlined.Person,
                        Icons.Filled.Person,
                        tab == Tab.Profile,
                    ) { onTab(Tab.Profile) },
                ),
                onCamera = onCamera,
                cameraDescription = stringResource(R.string.tab_camera),
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize(),
        ) {
            when (tab) {
                Tab.Activity -> ActivityScreen()
                Tab.Chat -> ChatScreen(onOpenCamera = onCamera)
                Tab.Location -> HomeScreen(onTrack = onTrack)
                Tab.Profile -> ProfileScreen(onPrivacy = onPrivacy, onUnpaired = onUnpaired)
            }
        }
    }
}

/**
 * Camera sits above the tab bar. A finished photo or video is handed to the
 * chat task. [ChatController.sendMedia] lives in that task's file.
 */
@Composable
private fun CameraRoute(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    CameraScreen(
        onResult = { uri: Uri, isVideo: Boolean ->
            val app = context.applicationContext
            AppScope.scope.launch {
                try {
                    ChatController.sendMedia(app, uri, isVideo)
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The picture stays where the camera put it. Chat reports its own failure.
                }
            }
            onClose()
        },
        onClose = onClose,
    )
}

/**
 * Pulls the inbox on resume and every 5 seconds while the paired window is
 * open. Live updates are not pushed, and neither are drawings or chat.
 * The loop runs only while this activity is resumed.
 */
@Composable
private fun InboxPump(active: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumeTick by remember { mutableIntStateOf(0) }
    var resumed by remember {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    resumeTick++
                }
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            resumed = true
            resumeTick++
        }
        onDispose { lifecycle.removeObserver(observer) }
    }

    val revision by Store.changes.collectAsState()
    val paired = Store.isPaired(context)
    val shouldPoll = resumed && paired && active && revision >= 0

    LaunchedEffect(resumeTick) {
        if (resumeTick == 0 || !Store.isPaired(context)) return@LaunchedEffect
        pullInbox(context)
    }
    LaunchedEffect(shouldPoll) {
        if (!shouldPoll) return@LaunchedEffect
        while (isActive) {
            delay(5_000)
            if (!Store.isPaired(context)) return@LaunchedEffect
            pullInbox(context)
        }
    }
}

private suspend fun pullInbox(context: android.content.Context) {
    try {
        // DATA-FLOW: ciphertext blobs from my relay inbox, encrypted. The relay deletes them on read. See Inbox.process.
        Inbox.process(context)
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (_: Exception) {
        // No network. The next resume or tick tries again.
    }
}

private data class Start(val gate: Gate, val tab: Tab, val overlay: Overlay)

private fun startRoute(context: android.content.Context, open: String?): Start {
    if (!Store.isPaired(context)) {
        val gate = if (UiFlags.onboardingDone(context)) Gate.Pair else Gate.Onboarding
        return Start(gate, Tab.Location, Overlay.None)
    }
    val tab = when (open) {
        "chat" -> Tab.Chat
        "activity" -> Tab.Activity
        "settings" -> Tab.Profile
        else -> Tab.Location
    }
    val overlay = if (open == "track") Overlay.Track else Overlay.None
    return Start(Gate.Main, tab, overlay)
}

private fun String.toGate(): Gate = try {
    Gate.valueOf(this)
} catch (_: IllegalArgumentException) {
    Gate.Main
}

private fun String.toTab(): Tab = try {
    Tab.valueOf(this)
} catch (_: IllegalArgumentException) {
    Tab.Location
}

private fun String.toOverlay(): Overlay = try {
    Overlay.valueOf(this)
} catch (_: IllegalArgumentException) {
    Overlay.None
}
