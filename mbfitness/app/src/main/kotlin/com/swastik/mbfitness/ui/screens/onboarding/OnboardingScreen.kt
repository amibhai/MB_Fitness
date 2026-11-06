package com.swastik.mbfitness.ui.screens.onboarding

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swastik.mbfitness.App
import com.swastik.mbfitness.data.SafBackupFolder
import com.swastik.mbfitness.domain.OemGuide
import com.swastik.mbfitness.domain.OnboardingRules
import com.swastik.mbfitness.domain.OnboardingStep
import com.swastik.mbfitness.ui.appViewModel
import com.swastik.mbfitness.ui.components.BannerTone
import com.swastik.mbfitness.ui.components.ChoiceCard
import com.swastik.mbfitness.ui.components.DarkStatusBarStrip
import com.swastik.mbfitness.ui.components.GymLogo
import com.swastik.mbfitness.ui.components.LinkButton
import com.swastik.mbfitness.ui.components.ListGap
import com.swastik.mbfitness.ui.components.MbBanner
import com.swastik.mbfitness.ui.components.MbCard
import com.swastik.mbfitness.ui.components.MbTextField
import com.swastik.mbfitness.ui.components.PrimaryButton
import com.swastik.mbfitness.ui.components.ScreenPadding
import com.swastik.mbfitness.ui.components.SecondaryButton
import com.swastik.mbfitness.ui.components.launchOr
import com.swastik.mbfitness.ui.screens.lock.SetPinScreen
import com.swastik.mbfitness.ui.screens.lock.SetPinStage
import com.swastik.mbfitness.ui.screens.lock.SetPinViewModel
import com.swastik.mbfitness.ui.screens.settings.BackupScreen
import com.swastik.mbfitness.ui.screens.settings.PlansScreen
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType
import com.swastik.mbfitness.work.DeviceIntents
import com.swastik.mbfitness.work.SimChoice

private enum class Overlay { RESTORE, PLANS, PIN }

/** SPEC 11.1. Shown once, before the app itself. */
@Composable
fun OnboardingScreen(onImportMembers: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val viewModel = appViewModel { container -> OnboardingViewModel(container, container.deviceChecks.simSlots(), Build.VERSION.SDK_INT) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var overlay by remember { mutableStateOf<Overlay?>(null) }

    BackHandler(state.index > 0 && overlay == null, onBack = viewModel::back)

    when (overlay) {
        Overlay.RESTORE -> {
            BackHandler { overlay = null }
            // A successful restore marks onboarding as done, which moves the app on by itself.
            BackupScreen(onBack = { overlay = null })
            return
        }
        Overlay.PLANS -> {
            BackHandler { overlay = null }
            PlansScreen(onBack = { overlay = null })
            return
        }
        Overlay.PIN -> {
            val pin = appViewModel { container -> SetPinViewModel(container.appLock, container.settings) }
            val pinState by pin.uiState.collectAsStateWithLifecycle()
            BackHandler { overlay = null }
            if (pinState.stage == SetPinStage.DONE) {
                LaunchedEffect(Unit) {
                    overlay = null
                    viewModel.next()
                }
            } else {
                SetPinScreen(pinState, pin, onBack = { overlay = null })
            }
            return
        }
        null -> Unit
    }

    if (!state.loaded) return
    val colors = MbTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        DarkStatusBarStrip()
        Column(
            Modifier
                .weight(1f)
                .navigationBarsPadding()
                .padding(ScreenPadding),
        ) {
            ProgressDots(state.index, state.steps.size)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state.step) {
                    OnboardingStep.WELCOME -> WelcomeStep(state, viewModel) { overlay = Overlay.RESTORE }
                    OnboardingStep.GYM -> GymStep(state, viewModel) { overlay = Overlay.PLANS }
                    OnboardingStep.SMS -> SmsStep(state, viewModel)
                    OnboardingStep.SIM -> SimStep(state, viewModel, app.deviceChecks.canListSims())
                    OnboardingStep.PLAN_CHECK -> PlanCheckStep(state, viewModel)
                    OnboardingStep.NOTIFICATIONS -> NotificationsStep(viewModel)
                    OnboardingStep.ALARMS -> AlarmsStep(viewModel)
                    OnboardingStep.BATTERY -> BatteryStep(viewModel)
                    OnboardingStep.HIBERNATION -> HibernationStep(viewModel)
                    OnboardingStep.BACKUP_FOLDER -> BackupFolderStep(viewModel)
                    OnboardingStep.APP_LOCK -> AppLockStep(state, viewModel) { overlay = Overlay.PIN }
                    OnboardingStep.LOGO -> LogoStep(state, viewModel)
                    OnboardingStep.TEST -> TestStep(state, viewModel)
                    OnboardingStep.DONE -> DoneStep(viewModel, onImportMembers)
                }
            }
            if (state.step != OnboardingStep.WELCOME && state.step != OnboardingStep.DONE) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    LinkButton("Back", viewModel::back)
                    LinkButton("Skip for now", viewModel::next)
                }
            }
        }
    }
}

@Composable
private fun ProgressDots(index: Int, total: Int) {
    val colors = MbTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = "Step ${index + 1} of $total" },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        repeat(total) { dot ->
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (dot <= index) colors.accent else colors.inactive.background),
            )
        }
    }
}

@Composable
private fun StepTitle(title: String, body: String? = null) {
    val colors = MbTheme.colors
    Text(title, style = MbType.Title, color = colors.textPrimary)
    body?.let { Text(it, style = MbType.Body, color = colors.textSecondary) }
}

@Composable
private fun ColumnScope.WelcomeStep(state: OnboardingUiState, viewModel: OnboardingViewModel, onRestore: () -> Unit) {
    val colors = MbTheme.colors
    GymLogo(state.settings.logoFile, state.settings.gymName, Modifier.align(Alignment.CenterHorizontally), size = 96.dp)
    Text(state.settings.greeting, Modifier.align(Alignment.CenterHorizontally), style = MbType.SectionLabel, color = colors.accent)
    StepTitle("Welcome", "A few steps to get reminders working. You can skip any of them and come back later from Settings.")
    if (state.settings.settingsReset) {
        MbBanner("The app's settings were damaged, so setup runs again. Your members and payments are safe.", BannerTone.WARNING)
    }
    PrimaryButton("Start", viewModel::next, Modifier.fillMaxWidth())
    SecondaryButton("Restore from a backup", onRestore, Modifier.fillMaxWidth())
}

@Composable
private fun GymStep(state: OnboardingUiState, viewModel: OnboardingViewModel, onPlans: () -> Unit) {
    val colors = MbTheme.colors
    val gym = state.gym
    StepTitle("Your gym")
    MbTextField(gym.ownerName, { text -> viewModel.onGymChange { it.copy(ownerName = text) } }, "Owner name", error = gym.ownerProblem.takeIf { gym.tried }, capitalization = KeyboardCapitalization.Words)
    MbTextField(gym.gymName, { text -> viewModel.onGymChange { it.copy(gymName = text) } }, "Gym name", error = gym.gymProblem.takeIf { gym.tried }, capitalization = KeyboardCapitalization.Words)
    MbTextField(
        gym.trainerPhone,
        { text -> viewModel.onGymChange { it.copy(trainerPhone = text) } },
        "Your phone number",
        error = gym.phoneProblem.takeIf { gym.tried },
        help = "Members see this in some reminders. Test SMS come here.",
        keyboardType = KeyboardType.Phone,
    )
    MbTextField(gym.upiId, { text -> viewModel.onGymChange { it.copy(upiId = text) } }, "UPI ID", error = gym.upiProblem.takeIf { gym.tried }, keyboardType = KeyboardType.Email)
    MbTextField(gym.payeeName, { text -> viewModel.onGymChange { it.copy(payeeName = text) } }, "UPI payee name", capitalization = KeyboardCapitalization.Words)
    MbCard(Modifier.fillMaxWidth()) {
        Text("Plans are set to 1 month ₹1,200 and 3 months ₹3,000.", style = MbType.Body, color = colors.textPrimary)
        LinkButton("Change plans", onPlans)
    }
    PrimaryButton("Next", viewModel::saveGym, Modifier.fillMaxWidth())
}

/** SPEC 11.1 step 3, including the restricted-settings guide for a sideloaded app. */
@Composable
private fun SmsStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val askedAt = remember { longArrayOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val elapsed = SystemClock.uptimeMillis() - askedAt[0]
        val activity = context as? android.app.Activity
        val rationale = activity?.shouldShowRequestPermissionRationale(Manifest.permission.SEND_SMS) == true
        viewModel.onSmsResult(granted, OnboardingRules.looksRestricted(granted, rationale, elapsed, app.deviceChecks.installedOutsideStore()))
    }
    LifecycleResumeEffect(Unit) {
        viewModel.refreshSms()
        onPauseOrDispose { }
    }
    StepTitle("SMS permission", "The app sends fee reminders from this phone's SIM. Nothing goes without this.")
    if (state.smsAllowed) {
        MbBanner("SMS permission is on.", BannerTone.INFO)
        PrimaryButton("Next", viewModel::next, Modifier.fillMaxWidth())
    } else {
        if (state.smsRestricted) RestrictedGuide()
        PrimaryButton(
            if (state.smsRestricted) "Try again" else "Allow SMS",
            {
                askedAt[0] = SystemClock.uptimeMillis()
                launcher.launch(Manifest.permission.SEND_SMS)
            },
            Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RestrictedGuide() {
    val context = LocalContext.current
    val colors = MbTheme.colors
    MbCard(Modifier.fillMaxWidth()) {
        Text("Android blocks this for apps installed outside a store. To allow it:", style = MbType.BodyLarge, color = colors.textPrimary)
        listOf(
            "Open App info below.",
            "Tap the three dots at the top right, then Allow restricted settings.",
            "Go to Permissions, then SMS, then Allow.",
        ).forEachIndexed { index, line ->
            Text("${index + 1}. $line", Modifier.padding(top = 4.dp), style = MbType.Body, color = colors.textSecondary)
        }
        LinkButton("Open App info", { DeviceIntents.open(context, DeviceIntents.appInfo(context)) })
    }
}

@Composable
private fun SimStep(state: OnboardingUiState, viewModel: OnboardingViewModel, canList: Boolean) {
    val app = LocalContext.current.applicationContext as App
    var sims by remember { mutableStateOf(if (canList) app.deviceChecks.activeSims() else emptyList<SimChoice>()) }
    var asked by remember { mutableStateOf(canList) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true
        sims = app.deviceChecks.activeSims()
    }
    LaunchedEffect(asked, sims.size) {
        if (asked && sims.size < 2) viewModel.dropSimStep()
    }
    StepTitle("Which SIM?", "Reminders go from the SIM you choose. Android only asks for this permission to list them.")
    if (!asked) {
        PrimaryButton("Show my SIMs", { launcher.launch(Manifest.permission.READ_PHONE_STATE) }, Modifier.fillMaxWidth())
    } else {
        sims.forEach { sim ->
            ChoiceCard(sim.label, null, state.settings.simSubscriptionId == sim.id, { viewModel.chooseSim(sim.id) })
        }
    }
}

@Composable
private fun PlanCheckStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepTitle("Your SIM plan", "Does your plan include free SMS, usually about 100 a day?")
    if (state.capTip) {
        MbBanner("The limit is set to ${OnboardingRules.CAP_IF_UNSURE} a day. Check with your operator, then change it in Settings if you can send more.", BannerTone.INFO)
        PrimaryButton("Next", viewModel::next, Modifier.fillMaxWidth())
    } else {
        PrimaryButton("Yes", { viewModel.onPlanAnswer(hasFreeSms = true) }, Modifier.fillMaxWidth())
        SecondaryButton("Not sure", { viewModel.onPlanAnswer(hasFreeSms = false) }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun NotificationsStep(viewModel: OnboardingViewModel) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.next() }
    StepTitle("Notifications", "A short summary each morning, and a warning if anything stops working.")
    PrimaryButton(
        "Allow notifications",
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else viewModel.next()
        },
        Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AlarmsStep(viewModel: OnboardingViewModel) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    var allowed by remember { mutableStateOf(app.deviceChecks.exactAlarmsAllowed() == true) }
    LifecycleResumeEffect(Unit) {
        allowed = app.deviceChecks.exactAlarmsAllowed() == true
        onPauseOrDispose { }
    }
    StepTitle("Alarms and reminders", "This lets reminders go right on time. Without it they can be up to 30 minutes late, which is fine too.")
    if (allowed) {
        MbBanner("Allowed.", BannerTone.INFO)
        PrimaryButton("Next", viewModel::next, Modifier.fillMaxWidth())
    } else {
        PrimaryButton("Allow", { DeviceIntents.exactAlarms(context)?.let { DeviceIntents.open(context, it) } }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun BatteryStep(viewModel: OnboardingViewModel) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val colors = MbTheme.colors
    var exempt by remember { mutableStateOf(app.deviceChecks.batteryExempt()) }
    var opened by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        exempt = app.deviceChecks.batteryExempt()
        onPauseOrDispose { }
    }
    StepTitle("Battery", "Phones stop apps to save battery. If this one is stopped, reminders don't go.")
    if (!exempt) {
        PrimaryButton("Turn off battery saving for this app", { DeviceIntents.open(context, DeviceIntents.battery(context)) }, Modifier.fillMaxWidth())
    } else {
        MbBanner("Battery saving is off for this app.", BannerTone.INFO)
    }
    OemGuide.cardFor(Build.MANUFACTURER)?.let { guide ->
        MbCard(Modifier.fillMaxWidth()) {
            Text("On ${guide.brand} phones, also:", style = MbType.BodyLarge, color = colors.textPrimary)
            guide.steps.forEachIndexed { index, step ->
                Text("${index + 1}. $step", Modifier.padding(top = 4.dp), style = MbType.Body, color = colors.textSecondary)
            }
            LinkButton("Open settings", { opened = DeviceIntents.brandBattery(Build.MANUFACTURER).any { DeviceIntents.open(context, it) } })
            if (!opened) Text("Open the phone's Settings and follow the steps above.", style = MbType.Caption, color = colors.textSecondary)
        }
    }
    if (exempt) PrimaryButton("Next", viewModel::next, Modifier.fillMaxWidth())
}

@Composable
private fun HibernationStep(viewModel: OnboardingViewModel) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.next() }
    StepTitle("Unused apps", "Android can pause apps that aren't opened for a while. Turn off \"Pause app activity if unused\" for this one.")
    PrimaryButton(
        "Open the setting",
        {
            // Not every phone has this screen. Nothing to do on those, so it just moves on.
            val intent = DeviceIntents.hibernation(context)
            if (intent != null) launcher.launchOr(intent, viewModel::next) else viewModel.next()
        },
        Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BackupFolderStep(viewModel: OnboardingViewModel) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            SafBackupFolder.keepAccess(context, uri)
            viewModel.onFolderChosen(uri.toString())
        }
    }
    StepTitle("Backups", "Pick a folder for the weekly backup. A folder that syncs to Google Drive is best, or make one called MB Fitness Backups.")
    PrimaryButton("Choose folder", { launcher.launchOr(null) { viewModel.next() } }, Modifier.fillMaxWidth())
}

@Composable
private fun AppLockStep(state: OnboardingUiState, viewModel: OnboardingViewModel, onSetPin: () -> Unit) {
    StepTitle("App lock", "A PIN keeps fees and member details private if someone picks up your phone. You'll also get a recovery code to write down.")
    if (state.settings.pinHash != null) {
        MbBanner("Your PIN is set.", BannerTone.INFO)
        PrimaryButton("Next", viewModel::next, Modifier.fillMaxWidth())
    } else {
        PrimaryButton("Set a PIN", onSetPin, Modifier.fillMaxWidth())
    }
}

@Composable
private fun LogoStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val settingsViewModel = appViewModel { container ->
        com.swastik.mbfitness.ui.screens.settings.SettingsViewModel(container.settings, container.logos, container::rearmReminders)
    }
    var failed by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picture ->
        if (picture != null) settingsViewModel.pickLogo(picture) { failed = true }
    }
    StepTitle("Logo", "It shows on the home screen and the splash screen.")
    GymLogo(state.settings.logoFile, state.settings.gymName, size = 96.dp)
    if (failed) MbBanner("Couldn't read that picture.", BannerTone.DANGER)
    PrimaryButton(
        if (state.settings.logoFile == null) "Pick from gallery" else "Pick another",
        { launcher.launchOr(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) { failed = true } },
        Modifier.fillMaxWidth(),
    )
    if (state.settings.logoFile != null) SecondaryButton("Next", viewModel::next, Modifier.fillMaxWidth())
}

@Composable
private fun TestStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepTitle("Send a test", "One SMS to your own number, to check everything works. Members get nothing.")
    state.testError?.let { MbBanner(it, BannerTone.DANGER) }
    if (!state.testSent) {
        PrimaryButton("Send me a test SMS", viewModel::sendTest, Modifier.fillMaxWidth())
    } else {
        MbBanner("Sent. It can take a minute to arrive.", BannerTone.INFO)
        PrimaryButton("It arrived", viewModel::next, Modifier.fillMaxWidth())
        SecondaryButton("Send again", viewModel::sendTest, Modifier.fillMaxWidth())
    }
}

@Composable
private fun DoneStep(viewModel: OnboardingViewModel, onImportMembers: () -> Unit) {
    StepTitle("You're set", "Add your members one by one from Home, or bring them all in from a file.")
    PrimaryButton("Start using the app", { viewModel.finish() }, Modifier.fillMaxWidth())
    SecondaryButton("Import members", { viewModel.finish(onImportMembers) }, Modifier.fillMaxWidth())
}
