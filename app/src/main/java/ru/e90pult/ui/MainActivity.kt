package ru.e90pult.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.e90pult.App
import ru.e90pult.Graph

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN = "open"
    }

    private val openRequest = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Интерфейс всегда тёмный: светлые значки строки состояния и навигации.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        openRequest.value = intent?.getStringExtra(EXTRA_OPEN)
        val graph = App.graph(this)
        setContent {
            E90Theme { AppRoot(graph, this, openRequest) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let { openRequest.value = it }
    }

    override fun onStart() {
        super.onStart()
        val g = App.graph(this)
        g.safety.start()
        g.scope.launch { g.engine.scanInbox() }
    }

    override fun onStop() {
        super.onStop()
        val g = App.graph(this)
        g.safety.stop()
        g.pin.lock()
    }
}

@Composable
fun AppRoot(graph: Graph, activity: ComponentActivity, openRequest: MutableState<String?>) {
    val uiScope = rememberCoroutineScope()
    val state = remember { AppState(graph, activity, uiScope) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        graph.safety.restart()
        state.onPermissionResult(res)
    }
    state.permissionLauncher = { perms -> launcher.launch(perms) }
    val live = rememberLive(state)

    val req = openRequest.value
    LaunchedEffect(req) {
        if (req != null) {
            state.handleOpen(req)
            openRequest.value = null
        }
    }
    // Bluetooth машин из всех профилей: телефон в любой машине — значит, возможно, едет.
    val profilesFlow = remember { graph.db.profiles().all() }
    val allProfiles by profilesFlow.collectAsState(initial = emptyList())
    val carDevices = remember(allProfiles) {
        allProfiles.filter { it.btAddress.isNotBlank() }.map { it.btAddress to it.btName }
    }
    LaunchedEffect(carDevices) { graph.safety.setCarDevices(carDevices) }
    LaunchedEffect(live.settings.activeProfileId) {
        if (live.settings.activeProfileId == 0L) {
            graph.db.profiles().allNow().firstOrNull()?.let { p -> graph.prefs.update { it.copy(activeProfileId = p.id) } }
        }
    }
    BackHandler(enabled = state.canGoBack) { state.back() }

    if (!live.settings.onboardingDone) {
        Box(Modifier.fillMaxSize().background(Pal.Bg).systemBarsPadding()) { OnboardingScreen(state, live) }
    } else {
        Scaffold(
            containerColor = Pal.Bg,
            snackbarHost = { SnackbarHost(state.snackbar) },
            bottomBar = { BottomBar(state) }
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) { ScreenSwitch(state, live) }
        }
    }
    AppDialogs(state, live)
}

@Composable
fun BottomBar(state: AppState) {
    NavigationBar(containerColor = Pal.Surface) {
        Tab.entries.forEach { t ->
            NavigationBarItem(
                selected = state.tab == t,
                onClick = { state.selectTab(t) },
                icon = { Icon(painter = painterResource(t.icon), contentDescription = t.title) },
                label = { Text(t.title, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Pal.Blue,
                    selectedTextColor = Pal.Blue,
                    indicatorColor = Pal.BlueSoft,
                    unselectedIconColor = Pal.TextDim,
                    unselectedTextColor = Pal.TextDim
                )
            )
        }
    }
}

@Composable
fun ScreenSwitch(state: AppState, live: Live) {
    when (val s = state.current) {
        Screen.Home -> HomeScreen(state, live)
        Screen.Journal -> JournalScreen(state, live)
        Screen.Charts -> ChartsScreen(state, live)
        Screen.Map -> MapScreen(state, live)
        Screen.More -> MoreScreen(state, live)
        is Screen.Run -> RunScreen(state, live, s.runId)
        Screen.State -> StateScreen(state, live)
        Screen.Service -> ServiceScreen(state, live)
        Screen.Critical -> CriticalScreen(state, live)
        Screen.Balance -> BalanceScreen(state, live)
        Screen.Firmware -> FirmwareScreen(state, live)
        Screen.Commands -> CommandsScreen(state, live)
        Screen.CommandsEditor -> CommandsEditorScreen(state, live)
        Screen.Profiles -> ProfilesScreen(state, live)
        is Screen.ProfileEdit -> ProfileEditScreen(state, live, s.id)
        Screen.Counter -> CounterScreen(state, live)
        Screen.Settings -> SettingsScreen(state, live)
        Screen.Debug -> DebugScreen(state, live)
        Screen.About -> AboutScreen(state)
    }
}
