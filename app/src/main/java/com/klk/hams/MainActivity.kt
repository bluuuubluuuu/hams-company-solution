package com.klk.hams

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.klk.hams.data.location.LocationStream
import com.klk.hams.provisioning.ProvisioningStore
import com.klk.hams.service.HamsForegroundService
import com.klk.hams.ui.count.CountScreen
import com.klk.hams.ui.count.CountViewModel
import com.klk.hams.ui.onboarding.BatteryOnboardingScreen
import com.klk.hams.ui.onboarding.PairingScreen
import com.klk.hams.ui.theme.HAMSTaskRecorderTheme

class MainActivity : ComponentActivity() {

    private var gatePassed = false

    // ComponentActivity (not FragmentActivity) — lint can't introspect the transitive
    // androidx.fragment version pulled by AndroidX and fires a false positive. We never
    // use Fragments here, so the InvalidFragmentVersionForActivityResult check doesn't apply.
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op; user choice persists in OS */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HAMSTaskRecorderTheme {
                var provisioned by remember {
                    mutableStateOf(
                        ProvisioningStore.fromContext(this@MainActivity).isProvisioned()
                    )
                }
                if (!provisioned) {
                    PairingScreen(onPaired = { provisioned = true })
                } else {
                    var showOnboarding by remember {
                        mutableStateOf(!batteryOnboardingShown() && !isBatteryExempt())
                    }
                    if (showOnboarding) {
                        BatteryOnboardingScreen(
                            onAllow = {
                                markBatteryOnboardingShown()
                                requestBatteryExemption()
                                showOnboarding = false
                            },
                            onSkip = {
                                markBatteryOnboardingShown()
                                showOnboarding = false
                            }
                        )
                    } else {
                        val vm: CountViewModel = viewModel(
                            factory = CountViewModel.Factory(application)
                        )
                        val activity = this@MainActivity
                        val notificationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestPermission()
                        ) { /* result reflected via onResume recheck */ }

                        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                        androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                            val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
                                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                    val denied =
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                            ContextCompat.checkSelfPermission(
                                                activity, Manifest.permission.POST_NOTIFICATIONS
                                            ) != PackageManager.PERMISSION_GRANTED
                                    vm.setNotificationPermissionDenied(denied)
                                }
                            }
                            lifecycleOwner.lifecycle.addObserver(obs)
                            onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
                        }

                        CountScreen(
                            vm = vm,
                            onExitRequired = { finishAffinity() },
                            onGatePassed = {
                                gatePassed = true
                                startLocationStream()
                                startHamsService()
                                requestNotificationPermissionIfNeeded()
                            },
                            onNotificationGrantRequested = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    val showRationale =
                                        androidx.core.app.ActivityCompat
                                            .shouldShowRequestPermissionRationale(
                                                activity, Manifest.permission.POST_NOTIFICATIONS
                                            )
                                    val granted = ContextCompat.checkSelfPermission(
                                        activity, Manifest.permission.POST_NOTIFICATIONS
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (!granted && !showRationale) {
                                        activity.startActivity(
                                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                                        )
                                    } else {
                                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                                vm.onNotificationGrantDispatched()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (gatePassed) {
            // Task 20.4: if the user revoked ACCESS_FINE_LOCATION while the app
            // was paused, fall back to the launch-gate behaviour (CLAUDE.md:
            // "GPS is mandatory for counting"). minSdk is 34 so the version
            // guard is defensive only.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val granted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    finishAffinity()
                    return
                }
            }
            startLocationStream()
        }
    }

    override fun onPause() {
        (application as HamsApp).locationStream.stop(LocationStream.REASON_FOREGROUND)
        super.onPause()
    }

    private fun startLocationStream() {
        (application as HamsApp).locationStream.start(LocationStream.REASON_FOREGROUND)
    }

    private fun startHamsService() {
        val intent = Intent(this, HamsForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun isBatteryExempt(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun batteryOnboardingShown(): Boolean =
        getSharedPreferences("hams_prefs", Context.MODE_PRIVATE)
            .getBoolean("battery_onboarding_shown", false)

    private fun markBatteryOnboardingShown() {
        getSharedPreferences("hams_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("battery_onboarding_shown", true).apply()
    }

    @Suppress("BatteryLife")
    private fun requestBatteryExemption() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
        )
    }
}
