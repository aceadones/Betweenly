package app.loviedowpie.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.loviedowpie.LocationSharing
import app.loviedowpie.R
import app.loviedowpie.Store
import app.loviedowpie.ui.icons.Lu
import kotlinx.coroutines.launch

/**
 * First-run story. One idea per page. Nothing here is sent.
 *
 * DATA-FLOW: none. The name and photo are saved on this phone.
 * Pairing sends the profile later, encrypted.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit, onPrivacy: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(Store.myName(context)) }
    var tick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        tick++
    }
    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
    }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        tick++
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        tick++
    }

    val notificationsOk = remember(tick) {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
    val locationOk = remember(tick) { LocationSharing.hasLocationPermission(context) }
    val backgroundOk = remember(tick) { LocationSharing.hasBackgroundLocation(context) }
    val batteryOk = remember(tick) { LocationSharing.batteryOptimizationsIgnored(context) }
    val pages = remember {
        buildList {
            add(StoryPage.Welcome)
            add(StoryPage.Privacy)
            add(StoryPage.You)
            add(StoryPage.Notify)
            add(StoryPage.Location)
            if (Build.VERSION.SDK_INT >= 29) add(StoryPage.Background)
            add(StoryPage.Battery)
            add(StoryPage.Ready)
        }
    }
    val youIndex = pages.indexOf(StoryPage.You)
    val pager = rememberPagerState(pageCount = { pages.size })
    val canContinue = name.trim().isNotEmpty()
    val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp

    fun openSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
        try {
            settings.launch(intent)
        } catch (_: Exception) {
            // This device has no app-details screen. The status line stays as it is.
        }
    }

    fun go(index: Int) {
        val dest = index.coerceIn(0, pages.lastIndex)
        scope.launch {
            pager.animateScrollToPage(
                dest,
                animationSpec = Motion.tween(Motion.SLOW, Motion.easeOut),
            )
        }
    }

    BackHandler(enabled = pager.currentPage > 0) {
        go(pager.currentPage - 1)
    }

    LaunchedEffect(pager, canContinue) {
        snapshotFlow { pager.targetPage }.collect { target ->
            if (!canContinue && target > youIndex) pager.scrollToPage(youIndex)
        }
    }
    LaunchedEffect(pager.currentPage, name) {
        if (pager.currentPage > youIndex && name.trim().isNotEmpty()) {
            Store.setMyName(context, name.trim())
        }
    }

    val page = pages[pager.currentPage.coerceIn(0, pages.lastIndex)]
    val skippable = page != StoryPage.Welcome && page != StoryPage.You && page != StoryPage.Ready

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = true,
        ) { index ->
            when (pages[index]) {
                StoryPage.Welcome -> WelcomePage(pager, index, topPad) { go(index + 1) }
                StoryPage.Privacy -> PrivacyPage(
                    topPad = topPad,
                    onOpen = onPrivacy,
                    onNext = { go(index + 1) },
                )
                StoryPage.You -> YouPage(
                    topPad = topPad,
                    name = name,
                    onName = { value ->
                        if (value.length <= 80) name = value.replace("\n", " ")
                    },
                    onNext = {
                        val clean = name.trim()
                        if (clean.isEmpty()) return@YouPage
                        Store.setMyName(context, clean)
                        go(index + 1)
                    },
                )
                StoryPage.Notify -> {
                    AdvanceAfterGrant(
                        granted = notificationsOk,
                        active = pager.currentPage == index,
                        onGranted = { go(index + 1) },
                    )
                    PermissionPage(
                        topPad = topPad,
                        title = stringResource(R.string.perm_notifications_title),
                        line = stringResource(R.string.onb_perm_notify_line),
                        icon = Lu.Bell,
                        granted = notificationsOk,
                        onAllow = if (Build.VERSION.SDK_INT >= 33 && !notificationsOk) {
                            {
                                try {
                                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } catch (_: Exception) {
                                    openSettings()
                                }
                            }
                        } else {
                            null
                        },
                        onContinue = { go(index + 1) },
                        onLater = { go(index + 1) },
                        onSettings = ::openSettings,
                        showFootnote = true,
                    )
                }
                StoryPage.Location -> {
                    AdvanceAfterGrant(
                        granted = locationOk,
                        active = pager.currentPage == index,
                        onGranted = { go(index + 1) },
                    )
                    PermissionPage(
                        topPad = topPad,
                        title = stringResource(R.string.perm_location_title),
                        line = stringResource(R.string.onb_perm_loc_line),
                        icon = Lu.MapPin,
                        granted = locationOk,
                        onAllow = {
                            try {
                                foreground.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                    ),
                                )
                            } catch (_: Exception) {
                                openSettings()
                            }
                        },
                        onContinue = { go(index + 1) },
                        onLater = { go(index + 1) },
                        onSettings = ::openSettings,
                        showFootnote = false,
                    )
                }
                StoryPage.Background -> {
                    AdvanceAfterGrant(
                        granted = backgroundOk,
                        active = pager.currentPage == index,
                        onGranted = { go(index + 1) },
                    )
                    PermissionPage(
                        topPad = topPad,
                        title = stringResource(R.string.perm_background_title),
                        line = stringResource(R.string.onb_perm_bg_line),
                        icon = Lu.LocateFixed,
                        granted = backgroundOk,
                        onAllow = {
                            if (!locationOk) {
                                try {
                                    foreground.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION,
                                        ),
                                    )
                                } catch (_: Exception) {
                                    openSettings()
                                }
                            } else {
                                try {
                                    background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                } catch (_: Exception) {
                                    openSettings()
                                }
                            }
                        },
                        onContinue = { go(index + 1) },
                        onLater = { go(index + 1) },
                        onSettings = ::openSettings,
                        showFootnote = false,
                    )
                }
                StoryPage.Battery -> {
                    AdvanceAfterGrant(
                        granted = batteryOk,
                        active = pager.currentPage == index,
                        onGranted = { go(index + 1) },
                    )
                    PermissionPage(
                        topPad = topPad,
                        title = stringResource(R.string.perm_battery_title),
                        line = stringResource(R.string.onb_perm_bat_line),
                        icon = Lu.Battery,
                        granted = batteryOk,
                        onAllow = { LocationSharing.requestUnrestrictedBattery(context) },
                        onContinue = { go(index + 1) },
                        onLater = { go(index + 1) },
                        onSettings = ::openSettings,
                        showFootnote = false,
                    )
                }
                StoryPage.Ready -> ReadyPage(topPad) {
                    val clean = name.trim()
                    if (clean.isNotEmpty()) Store.setMyName(context, clean)
                    UiFlags.setOnboardingDone(context)
                    onDone()
                }
            }
        }
        StoryChrome(
            page = pager.currentPage.coerceIn(0, pages.lastIndex),
            count = pages.size,
            canBack = pager.currentPage > 0,
            canSkip = skippable,
            onBack = { go(pager.currentPage - 1) },
            onSkip = { go(pager.currentPage + 1) },
        )
    }
}
