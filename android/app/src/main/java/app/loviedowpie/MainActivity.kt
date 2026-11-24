package app.loviedowpie

import androidx.compose.runtime.collectAsState
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.loviedowpie.ui.HomeScreen
import app.loviedowpie.ui.LoviedowpieTheme
import app.loviedowpie.ui.OnboardingScreen
import app.loviedowpie.ui.PairScreen
import app.loviedowpie.ui.PrivacyScreen
import app.loviedowpie.ui.SettingsScreen
import app.loviedowpie.ui.TrackScreen
import app.loviedowpie.ui.UiFlags
import app.loviedowpie.ui.WidgetRefresh
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The only activity. Onboarding, pairing, home, the map, and settings are
 * screens in this one window. A widget opens it with extra "open" = "track"
 * or "settings".
 *
 * Pulls the inbox when the screen comes back, and every 5 seconds while the
 * map is open or both phones are in live sharing.
 */
class MainActivity : ComponentActivity() {
    val routeEvent = mutableIntStateOf(0)
    var route: String? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars()
        if (Store.isPaired(this)) UiFlags.setOnboardingDone(this)
        WidgetRefresh.ensure(this)
        val open = if (savedInstanceState == null) intent?.getStringExtra(EXTRA_OPEN) else null
        if (savedInstanceState == null) {
            route = open
        }
        setContent {
            LoviedowpieTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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

    private fun applySystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        window.setBackgroundDrawable(ColorDrawable(if (night) Color.parseColor("#1A1113") else Color.parseColor("#FFF8F7")))
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
    Home,
    Track,
    Settings,
    Privacy,
}

@Composable
private fun Root(startOpen: String?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as MainActivity
    var screenName by rememberSaveable { mutableStateOf(startScreen(context, startOpen).name) }
    var privacyFrom by rememberSaveable { mutableStateOf(Screen.Onboarding.name) }
    val screen = screenName.toScreen()

    val event = activity.routeEvent.intValue
    LaunchedEffect(event) {
        if (!Store.isPaired(context)) return@LaunchedEffect
        when (activity.route) {
            "track" -> screenName = Screen.Track.name
            "settings" -> screenName = Screen.Settings.name
        }
    }

    val revision by Store.changes.collectAsState()
    LaunchedEffect(revision, screenName) {
        val current = screenName.toScreen()
        val allowed = current == Screen.Onboarding || current == Screen.Pair || current == Screen.Privacy
        if (!Store.isPaired(context) && !allowed) {
            screenName = if (UiFlags.onboardingDone(context)) Screen.Pair.name else Screen.Onboarding.name
        }
    }

    InboxPump(screen)

    BackHandler(enabled = screen == Screen.Track || screen == Screen.Settings || screen == Screen.Privacy) {
        screenName = if (screen == Screen.Privacy) privacyFrom else Screen.Home.name
    }

    when (screen) {
        Screen.Onboarding -> OnboardingScreen(
            onDone = { screenName = Screen.Pair.name },
            onPrivacy = {
                privacyFrom = Screen.Onboarding.name
                screenName = Screen.Privacy.name
            },
        )
        Screen.Pair -> PairScreen(onPaired = { screenName = Screen.Home.name })
        Screen.Home -> HomeScreen(
            onTrack = { screenName = Screen.Track.name },
            onSettings = { screenName = Screen.Settings.name },
        )
        Screen.Track -> TrackScreen(onBack = { screenName = Screen.Home.name })
        Screen.Settings -> SettingsScreen(
            onBack = { screenName = Screen.Home.name },
            onPrivacy = {
                privacyFrom = Screen.Settings.name
                screenName = Screen.Privacy.name
            },
            onUnpaired = { screenName = Screen.Pair.name },
        )
        Screen.Privacy -> PrivacyScreen(onBack = { screenName = privacyFrom })
    }
}

/**
 * Pulls the inbox on resume, when the map opens, and every 5 seconds while
 * the map is open or this phone is live and the partner is live.
 * Live updates are not pushed, so an open screen has to ask for them.
 * The 5-second loop runs only while this activity is resumed.
 */
@Composable
private fun InboxPump(screen: Screen) {
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
    val partnerLive = Store.partnerLoc(context)?.live == true
    val liveOn = Store.liveOn(context)
    val paired = Store.isPaired(context)
    val shouldPoll = resumed && paired && revision >= 0 &&
        (screen == Screen.Track || (liveOn && partnerLive))

    LaunchedEffect(resumeTick) {
        if (resumeTick == 0 || !Store.isPaired(context)) return@LaunchedEffect
        pullInbox(context)
    }
    LaunchedEffect(screen) {
        if (screen == Screen.Track && Store.isPaired(context)) pullInbox(context)
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

private fun startScreen(context: android.content.Context, open: String?): Screen {
    val paired = Store.isPaired(context)
    if (!paired) {
        return if (UiFlags.onboardingDone(context)) Screen.Pair else Screen.Onboarding
    }
    return when (open) {
        "track" -> Screen.Track
        "settings" -> Screen.Settings
        else -> Screen.Home
    }
}

private fun String.toScreen(): Screen = try {
    Screen.valueOf(this)
} catch (_: IllegalArgumentException) {
    Screen.Home
}
