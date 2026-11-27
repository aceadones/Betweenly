package com.nextlayer.betweenly

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import com.nextlayer.betweenly.ui.UpdateAvailableDialog
import kotlin.coroutines.cancellation.CancellationException
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nextlayer.betweenly.ui.ActivityScreen
import com.nextlayer.betweenly.ui.CameraScreen
import com.nextlayer.betweenly.ui.ChatController
import com.nextlayer.betweenly.ui.ChatScreen
import com.nextlayer.betweenly.ui.DrawingStudioScreen
import com.nextlayer.betweenly.ui.HomeScreen
import com.nextlayer.betweenly.ui.LdBottomBar
import com.nextlayer.betweenly.ui.LdTab
import com.nextlayer.betweenly.ui.BetweenlyTheme
import com.nextlayer.betweenly.ui.Motion
import com.nextlayer.betweenly.ui.OnboardingScreen
import com.nextlayer.betweenly.ui.PairScreen
import com.nextlayer.betweenly.ui.PrivacyScreen
import com.nextlayer.betweenly.ui.ProfileScreen
import com.nextlayer.betweenly.ui.TrackScreen
import com.nextlayer.betweenly.ui.UiFlags
import com.nextlayer.betweenly.ui.WidgetRefresh
import com.nextlayer.betweenly.ui.icons.Lu
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The only activity. Onboarding and pairing fill the window. After pairing,
 * a bottom dock switches Activity, Chat, Location, and Profile. The map,
 * camera, and drawing studio sit above that dock. Duet is a separate app.
 *
 * Widget and notification extras: `open=track`, `open=chat`, `open=activity`,
 * `open=draw` (drawing studio). The V1 widget still sends `open=settings`; that opens Profile.
 *
 * Pulls the inbox on resume and every 5 seconds while this window is open
 * (every 2 seconds while [AppState.fastPoll] is set). Chat, drawings, and
 * live location use the same pull.
 */
class MainActivity : ComponentActivity() {
    val routeEvent = mutableIntStateOf(0)
    var route: String? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PetReminder.schedule(this)
        applySystemBars()
        if (Store.isPaired(this)) UiFlags.setOnboardingDone(this)
        ChatStore.loadUnread(this)
        WidgetRefresh.ensure(this)
        val open = if (savedInstanceState == null) intent?.getStringExtra(EXTRA_OPEN) else null
        if (savedInstanceState == null) {
            route = open
        }
        setContent {
            BetweenlyTheme {
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

private enum class Screen {
    Onboarding,
    Pair,
    Main,
    Privacy,
    Track,
    Camera,
    Drawing,
    SavedDrawings,
    Pet,
    Frame,
    Countdown,
    Todo,
    Mood,
    Game,
    Letters,
}

private enum class Tab { Activity, Chat, Location, Profile }

@Composable
private fun Root(startOpen: String?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as MainActivity
    val initial = remember { startRoute(context, startOpen) }
    var screenName by rememberSaveable { mutableStateOf(initial.screen.name) }
    var tabName by rememberSaveable { mutableStateOf(initial.tab.name) }
    var privacyFrom by rememberSaveable { mutableStateOf(Screen.Onboarding.name) }
    val screen = screenName.toScreen()
    val tab = tabName.toTab()

    val event = activity.routeEvent.intValue
    LaunchedEffect(event) {
        if (!Store.isPaired(context)) return@LaunchedEffect
        when (activity.route) {
            "track" -> {
                screenName = Screen.Track.name
                tabName = Tab.Location.name
            }
            "chat" -> {
                screenName = Screen.Main.name
                tabName = Tab.Chat.name
            }
            "activity" -> {
                screenName = Screen.Main.name
                tabName = Tab.Activity.name
            }
            "draw" -> screenName = Screen.Drawing.name
            // V1 widget. Profile replaced the settings screen.
            "settings" -> {
                screenName = Screen.Main.name
                tabName = Tab.Profile.name
            }
        }
    }

    val revision by Store.changes.collectAsState()
    LaunchedEffect(revision, screenName) {
        if (Store.isPaired(context)) return@LaunchedEffect
        val current = screenName.toScreen()
        if (current == Screen.Onboarding || current == Screen.Pair || current == Screen.Privacy) return@LaunchedEffect
        screenName = if (UiFlags.onboardingDone(context)) Screen.Pair.name else Screen.Onboarding.name
    }

    InboxPump(
        active = screen != Screen.Onboarding && screen != Screen.Pair && screen != Screen.Privacy,
    )

    val leave = screen == Screen.Privacy || screen == Screen.Camera || screen == Screen.Track ||
        screen == Screen.Drawing || screen == Screen.SavedDrawings
    BackHandler(enabled = leave) {
        screenName = if (screen == Screen.Privacy) privacyFrom else Screen.Main.name
    }

    val offer by UpdatePrompt.info.collectAsState()
    val showUpdate by UpdatePrompt.dialog.collectAsState()
    LaunchedEffect(Unit) {
        val result = try {
            // DATA-FLOW: update metadata from the relay, and from GitHub only when that repo is public. Not encrypted. No pair data.
            Updater.check(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            UpdateCheck.Error
        }
        when (result) {
            is UpdateCheck.Available -> {
                val id = result.info.identity()
                val show = Store.updateDismissedId(context) != id ||
                    Store.updateDismissedAt(context) < Store.lastUpdateCheck(context)
                UpdatePrompt.publish(result.info, dialog = show)
            }
            UpdateCheck.UpToDate -> UpdatePrompt.clear()
            UpdateCheck.Error -> Unit
        }
    }

    val slidePx = with(LocalDensity.current) { 24.dp.roundToPx() }
    Box(Modifier.fillMaxSize()) {
    AnimatedContent(
        targetState = screen,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            if (screenForward(initialState, targetState)) Motion.screenEnter(slidePx)
            else Motion.screenExit(slidePx)
        },
        label = "screen",
    ) { current ->
        when (current) {
            Screen.Onboarding -> OnboardingScreen(
                onDone = { screenName = Screen.Pair.name },
                onPrivacy = {
                    privacyFrom = Screen.Onboarding.name
                    screenName = Screen.Privacy.name
                },
            )
            Screen.Pair -> PairScreen(onPaired = { screenName = Screen.Main.name })
            Screen.Privacy -> PrivacyScreen(onBack = { screenName = privacyFrom })
            Screen.Track -> TrackScreen(onBack = { screenName = Screen.Main.name })
            Screen.Camera -> CameraRoute(onClose = { screenName = Screen.Main.name })
            Screen.Drawing -> DrawingStudioScreen(onClose = { screenName = Screen.Main.name })
            Screen.Pet -> com.nextlayer.betweenly.ui.PetScreen(onBack = { screenName = Screen.Main.name })
            Screen.Frame -> com.nextlayer.betweenly.ui.FrameScreen(onBack = { screenName = Screen.Main.name })
            Screen.Countdown -> com.nextlayer.betweenly.ui.CountdownScreen(onBack = { screenName = Screen.Main.name })
            Screen.Todo -> com.nextlayer.betweenly.ui.TodoScreen(onBack = { screenName = Screen.Main.name })
            Screen.Mood -> com.nextlayer.betweenly.ui.MoodScreen(onBack = { screenName = Screen.Main.name })
            Screen.Game -> com.nextlayer.betweenly.ui.GameScreen(onBack = { screenName = Screen.Main.name })
            Screen.Letters -> com.nextlayer.betweenly.ui.LettersScreen(onBack = { screenName = Screen.Main.name })
            Screen.SavedDrawings -> com.nextlayer.betweenly.ui.SavedDrawingsScreen(
                onBack = { screenName = Screen.Main.name },
            )
            Screen.Main -> MainShell(
                tab = tab,
                onTab = { tabName = it.name },
                onTrack = { screenName = Screen.Track.name },
                onCamera = { screenName = Screen.Camera.name },
                onOpenDrawing = { screenName = Screen.Drawing.name },
                onOpenPet = { screenName = Screen.Pet.name },
                onOpenFrame = { screenName = Screen.Frame.name },
                onOpenFeature = { screenName = it },
                onSavedDrawings = { screenName = Screen.SavedDrawings.name },
                onPrivacy = {
                    privacyFrom = Screen.Main.name
                    screenName = Screen.Privacy.name
                },
                onUnpaired = { screenName = Screen.Pair.name },
            )
        }
    }
    val petRev by com.nextlayer.betweenly.PetStore.changes.collectAsState()
    val showPet = remember(petRev) { com.nextlayer.betweenly.PetStore.overlayShown(context) }
    if (screen == Screen.Main && showPet) {
        com.nextlayer.betweenly.ui.PetOverlay(onOpen = { screenName = Screen.Pet.name })
    }
    val pending = offer
    if (showUpdate && pending != null) {
        UpdateAvailableDialog(
            info = pending,
            onLater = {
                Store.dismissUpdateDialog(context, pending.identity())
                UpdatePrompt.hideDialog()
            },
        )
    }
    }
}

@Composable
private fun MainShell(
    tab: Tab,
    onTab: (Tab) -> Unit,
    onTrack: () -> Unit,
    onCamera: () -> Unit,
    onOpenDrawing: () -> Unit,
    onOpenPet: () -> Unit,
    onOpenFrame: () -> Unit,
    onOpenFeature: (String) -> Unit,
    onSavedDrawings: () -> Unit,
    onPrivacy: () -> Unit,
    onUnpaired: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val unread by ChatStore.unreadCount.collectAsState()
    LaunchedEffect(Unit) { ChatStore.loadUnread(context) }
    val fullChat = tab == Tab.Chat
    val tabSlide = with(LocalDensity.current) { 24.dp.roundToPx() }
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = if (fullChat) {
            WindowInsets.safeDrawing
        } else {
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        },
        bottomBar = {
            if (!fullChat) LdBottomBar(
                tabs = listOf(
                    LdTab(
                        stringResource(R.string.tab_activity),
                        Lu.Layers,
                        tab == Tab.Activity,
                    ) { onTab(Tab.Activity) },
                    LdTab(
                        stringResource(R.string.tab_chat),
                        Lu.MessageCircle,
                        tab == Tab.Chat,
                        badge = if (tab == Tab.Chat) 0 else unread,
                    ) { onTab(Tab.Chat) },
                    LdTab(
                        stringResource(R.string.tab_location),
                        Lu.MapPin,
                        tab == Tab.Location,
                    ) { onTab(Tab.Location) },
                    LdTab(
                        stringResource(R.string.tab_profile),
                        Lu.User,
                        tab == Tab.Profile,
                    ) { onTab(Tab.Profile) },
                ),
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = tab,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize(),
            transitionSpec = {
                val dir = if (targetState.ordinal >= initialState.ordinal) tabSlide else -tabSlide
                Motion.tabTransition(dir)
            },
            label = "tab",
        ) { current ->
            Box(Modifier.fillMaxSize()) {
                when (current) {
                    Tab.Activity -> ActivityScreen(
                        onOpenDrawing = onOpenDrawing,
                        onOpenPet = onOpenPet,
                        onOpenFrame = onOpenFrame,
                        onOpenFeature = onOpenFeature,
                    )
                    Tab.Chat -> ChatScreen(
                        onBack = { onTab(Tab.Activity) },
                        onOpenCamera = onCamera,
                    )
                    Tab.Location -> HomeScreen(onTrack = onTrack)
                    Tab.Profile -> ProfileScreen(
                        onPrivacy = onPrivacy,
                        onUnpaired = onUnpaired,
                        onSavedDrawings = onSavedDrawings,
                    )
                }
            }
        }
    }
}

/**
 * Camera sits above the dock. Confirming a shot stages it for the chat
 * preview. The chat screen sends it after a caption, if the person still wants to.
 */
@Composable
private fun CameraRoute(onClose: () -> Unit) {
    CameraScreen(
        onResult = { uri: Uri, isVideo: Boolean ->
            ChatController.stageCapture(uri, isVideo)
            onClose()
        },
        onClose = onClose,
    )
}

/**
 * Pulls the inbox on resume and on a timer while the paired window is open.
 * The timer is 5 seconds, or 2 seconds while [AppState.fastPoll] is true.
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
        var elapsed = 0L
        while (isActive) {
            delay(1_000)
            elapsed += 1_000
            if (!Store.isPaired(context)) return@LaunchedEffect
            val period = if (AppState.fastPoll) 2_000L else 5_000L
            if (elapsed >= period) {
                elapsed = 0L
                pullInbox(context)
            }
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

private data class Start(val screen: Screen, val tab: Tab)

private fun startRoute(context: android.content.Context, open: String?): Start {
    if (!Store.isPaired(context)) {
        val screen = if (UiFlags.onboardingDone(context)) Screen.Pair else Screen.Onboarding
        return Start(screen, Tab.Location)
    }
    val tab = when (open) {
        "chat" -> Tab.Chat
        "activity", "draw" -> Tab.Activity
        "settings" -> Tab.Profile
        else -> Tab.Location
    }
    val screen = when (open) {
        "track" -> Screen.Track
        "draw" -> Screen.Drawing
        else -> Screen.Main
    }
    return Start(screen, tab)
}

private fun screenForward(from: Screen, to: Screen): Boolean {
    fun depth(screen: Screen) = when (screen) {
        Screen.Onboarding -> 0
        Screen.Pair -> 1
        Screen.Main -> 2
        Screen.Privacy, Screen.Track, Screen.Camera, Screen.Drawing, Screen.SavedDrawings, Screen.Pet, Screen.Frame, Screen.Countdown, Screen.Todo, Screen.Mood, Screen.Game, Screen.Letters -> 3
    }
    return depth(to) >= depth(from)
}

private fun String.toScreen(): Screen = try {
    Screen.valueOf(this)
} catch (_: IllegalArgumentException) {
    Screen.Main
}

private fun String.toTab(): Tab = try {
    Tab.valueOf(this)
} catch (_: IllegalArgumentException) {
    Tab.Location
}
