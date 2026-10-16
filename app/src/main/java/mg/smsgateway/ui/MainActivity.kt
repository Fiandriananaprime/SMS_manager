package mg.smsgateway.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import mg.smsgateway.service.GatewayService
import mg.smsgateway.sms.ScheduledCampaignScheduler
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val requestedJob = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedJob.value = intent.getStringExtra(ScheduledCampaignScheduler.EXTRA_JOB_ID)
        // L'app est au premier plan ici : démarrage autorisé du service de premier plan.
        // (`adb shell am start -n mg.smsgateway/.ui.MainActivity` démarre donc aussi la passerelle.)
        ContextCompat.startForegroundService(this, Intent(this, GatewayService::class.java))
        setContent {
            val jobId = requestedJob.collectAsState().value
            SmsGatewayApp(vm, jobId) { requestedJob.value = null }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedJob.value = intent.getStringExtra(ScheduledCampaignScheduler.EXTRA_JOB_ID)
    }
}

sealed interface Screen {
    data object Main : Screen
    data class Campaign(val editJobId: String? = null) : Screen
    data object History : Screen
    data class Job(val id: String) : Screen
    data object Settings : Screen
    data object Logs : Screen
}

@Composable
fun SmsGatewayApp(vm: MainViewModel, requestedJob: String?, onJobOpened: () -> Unit) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(requestedJob?.let { Screen.Job(it) } ?: Screen.Main) }
    LaunchedEffect(requestedJob) {
        requestedJob?.let {
            screen = Screen.Job(it)
            onJobOpened()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refresh()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    BackHandler(enabled = screen != Screen.Main) {
        screen = when (screen) {
            is Screen.Job -> Screen.History
            Screen.Logs -> Screen.Settings
            is Screen.Campaign -> Screen.Main
            else -> Screen.Main
        }
    }

    val requestPermissions: () -> Unit = {
        val list = buildList {
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_PHONE_NUMBERS)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(list.toTypedArray())
    }
    val openAppInfo: () -> Unit = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        )
    }

    val darkTheme = isSystemInDarkTheme()
    val fallbackLight = lightColorScheme(
        primary = Color(0xFF176B57),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD5F2E7),
        onPrimaryContainer = Color(0xFF062D23),
        secondary = Color(0xFF426B85),
        onSecondary = Color.White,
        tertiary = Color(0xFF9A5A1A),
        background = Color(0xFFF5F8F6),
        surface = Color(0xFFFCFDFC),
        surfaceVariant = Color(0xFFEAF1ED),
        onSurface = Color(0xFF17231E),
        onSurfaceVariant = Color(0xFF52615A),
        outline = Color(0xFF78877F),
    )
    val fallbackDark = darkColorScheme(
        primary = Color(0xFF8DD8BD),
        onPrimary = Color(0xFF00382C),
        primaryContainer = Color(0xFF14513F),
        onPrimaryContainer = Color(0xFFD5F2E7),
        secondary = Color(0xFFA8CBE2),
        tertiary = Color(0xFFFFBD75),
        background = Color(0xFF101915),
        surface = Color(0xFF18221D),
        surfaceVariant = Color(0xFF26332C),
        onSurface = Color(0xFFE5EEE8),
        onSurfaceVariant = Color(0xFFBCCAC1),
        outline = Color(0xFF75847B),
    )
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> fallbackDark
        else -> fallbackLight
    }
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )
    MaterialTheme(colorScheme = colorScheme, shapes = shapes) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                when (val s = screen) {
                    Screen.Main -> MainScreen(
                        vm = vm,
                        onCampaign = { screen = Screen.Campaign() },
                        onHistory = { screen = Screen.History },
                        onSettings = { screen = Screen.Settings },
                        onAskPermissions = requestPermissions,
                        onOpenAppInfo = openAppInfo,
                    )
                    is Screen.Campaign -> CampaignScreen(
                        vm = vm,
                        editJobId = s.editJobId,
                        onBack = { screen = Screen.Main },
                        onOpenJob = { screen = Screen.Job(it) },
                        onAskPermissions = requestPermissions,
                    )
                    Screen.History -> HistoryScreen(vm, onBack = { screen = Screen.Main }, onOpenJob = { screen = Screen.Job(it) })
                    is Screen.Job -> JobScreen(
                        vm,
                        s.id,
                        onBack = { screen = Screen.History },
                        onEditCampaign = { screen = Screen.Campaign(s.id) },
                    )
                    Screen.Settings -> SettingsScreen(vm, onBack = { screen = Screen.Main }, onLogs = { screen = Screen.Logs })
                    Screen.Logs -> LogsScreen(vm, onBack = { screen = Screen.Settings })
                }
            }
        }
    }
}
