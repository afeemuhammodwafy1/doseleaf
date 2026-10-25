package com.wafyro.doseleaf.ui
import kotlinx.coroutines.delay
import androidx.compose.animation.scaleIn
import androidx.compose.animation.AnimatedVisibility
import androidx.core.content.ContextCompat
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.core.content.FileProvider
import android.content.ClipData
import java.io.File
import com.wafyro.doseleaf.backup.LocalExport
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult

import android.app.Application
import android.app.TimePickerDialog
import android.media.RingtoneManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wafyro.doseleaf.data.local.DoseleafDatabase
import com.wafyro.doseleaf.data.local.entity.FamilyProfileEntity
import com.wafyro.doseleaf.data.local.entity.MedicineEntity
import com.wafyro.doseleaf.data.local.entity.DoseLogEntity
import com.wafyro.doseleaf.data.local.entity.InventoryEntity
import com.wafyro.doseleaf.reminders.ReminderScheduler
import com.wafyro.doseleaf.reminders.ExpiryReminderScheduler
import com.wafyro.doseleaf.ui.theme.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.time.LocalDate
import java.time.ZoneId

private fun tr(context: Context, english: String): String = english


private enum class Page { HOME, MEDICINES, PROFILES, PROFILE_DETAIL, ADD_MEDICINE, EDIT_MEDICINE, HISTORY, SETTINGS, ABOUT, GUIDE, BIN }

class DoseleafViewModel(private val db: DoseleafDatabase, private val app: Application) : ViewModel() {
    private val doseActionMutex = Mutex()
    val medicines = db.medicineDao().observeCurrent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val bin = db.medicineDao().observeRecycleBin().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val profiles = db.familyProfileDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val logs = db.doseLogDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    init { /* First-run name setup creates the default profile. */ }
    fun addProfile(name: String, relationship: String) = viewModelScope.launch {
        if (name.isNotBlank()) db.familyProfileDao().insert(FamilyProfileEntity(name = name.trim(), relationship = relationship.trim()))
    }
    fun updateProfile(profile: FamilyProfileEntity, name: String, relationship: String) = viewModelScope.launch {
        if (name.isNotBlank()) db.familyProfileDao().update(profile.copy(name = name.trim(), relationship = relationship.trim()))
    }
    fun deleteProfile(profile: FamilyProfileEntity) = viewModelScope.launch {
        if (profile.isDefault) return@launch
        // Keep profile-linked medicine/history records safe: move its active medicines to Recycle Bin first.
        val defaultProfile = db.familyProfileDao().observeAll().first().firstOrNull { it.isDefault && it.id != profile.id }
        db.medicineDao().observeForProfile(profile.id).first().forEach { med ->
            ReminderScheduler.cancel(app, med.id)
            ExpiryReminderScheduler.cancel(app, med.id)
            if (defaultProfile != null) db.medicineDao().update(med.copy(profileId = defaultProfile.id))
            db.medicineDao().softDelete(med.id)
        }
        if (!profile.isDefault) db.familyProfileDao().deleteById(profile.id)
    }
    fun renameDefaultProfile(name: String) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        val current = db.familyProfileDao().observeAll().first().firstOrNull { it.isDefault }
        if (current != null) db.familyProfileDao().update(current.copy(name = name.trim(), relationship = "Self"))
        else db.familyProfileDao().insert(FamilyProfileEntity(name = name.trim(), relationship = "Self", isDefault = true))
    }
    fun updateMedicine(medicine: MedicineEntity, profileId: Long, name: String, dosage: String, times: List<Pair<Int, Int>>, instructions: String, expiryDate: Long?, foodTiming: String, foodOffsetMinutes: Int, prescriptionPhotoUri: String?) = viewModelScope.launch {
        if (name.isBlank() || times.isEmpty()) return@launch
        ReminderScheduler.cancel(app, medicine.id)
        ExpiryReminderScheduler.cancel(app, medicine.id)
        val first = times.first()
        val updated = medicine.copy(profileId = profileId, name = name.trim(), dosage = dosage.trim(),
            reminderHour = first.first, reminderMinute = first.second, instructions = instructions.trim(), expiryDate = expiryDate, foodTiming = foodTiming, foodOffsetMinutes = foodOffsetMinutes, prescriptionPhotoUri = prescriptionPhotoUri)
        db.medicineDao().update(updated)
        db.medicineScheduleDao().deleteForMedicine(medicine.id)
        db.medicineScheduleDao().insertAll(times.distinct().map { (hour, minute) ->
            com.wafyro.doseleaf.data.local.entity.MedicineScheduleEntity(medicineId = medicine.id, hour = hour, minute = minute)
        })
        ReminderScheduler.schedule(app, updated)
        ExpiryReminderScheduler.schedule(app, updated.id, updated.name, updated.expiryDate)
    }
    fun addMedicine(profileId: Long, name: String, dosage: String, times: List<Pair<Int, Int>>, instructions: String, expiryDate: Long?, foodTiming: String, foodOffsetMinutes: Int, prescriptionPhotoUri: String?) = viewModelScope.launch {
        if (name.isBlank() || times.isEmpty()) return@launch
        val firstTime = times.first()
        val med = MedicineEntity(profileId = profileId, name = name.trim(), dosage = dosage.trim(),
            reminderHour = firstTime.first.coerceIn(0,23), reminderMinute = firstTime.second.coerceIn(0,59), instructions = instructions.trim(), expiryDate = expiryDate, foodTiming = foodTiming, foodOffsetMinutes = foodOffsetMinutes, prescriptionPhotoUri = prescriptionPhotoUri)
        val id = db.medicineDao().insert(med)
        db.medicineScheduleDao().insertAll(times.distinct().map { (hour, minute) ->
            com.wafyro.doseleaf.data.local.entity.MedicineScheduleEntity(medicineId = id, hour = hour, minute = minute)
        })
        ReminderScheduler.schedule(app, med.copy(id = id))
        ExpiryReminderScheduler.schedule(app, id, med.name, med.expiryDate)
    }
    fun deleteMedicine(medicine: MedicineEntity) = viewModelScope.launch {
        ReminderScheduler.cancel(app, medicine.id)
        ExpiryReminderScheduler.cancel(app, medicine.id)
        db.medicineDao().softDelete(medicine.id)
    }
    fun restoreMedicine(medicine: MedicineEntity) = viewModelScope.launch {
        db.medicineDao().restore(medicine.id)
        db.medicineDao().getById(medicine.id)?.let { ReminderScheduler.schedule(app, it); ExpiryReminderScheduler.schedule(app, it.id, it.name, it.expiryDate) }
    }
    private suspend fun permanentlyDeleteMedicineData(medicine: MedicineEntity) {
        ReminderScheduler.cancel(app, medicine.id)
        ExpiryReminderScheduler.cancel(app, medicine.id)
        db.medicineScheduleDao().deleteForMedicine(medicine.id)
        db.inventoryDao().deleteForMedicine(medicine.id)
        // Keep dose history as an audit trail; remove only medicine-owned schedule/stock data.
        db.medicineDao().permanentlyDelete(medicine.id)
    }
    fun permanentlyDelete(medicine: MedicineEntity) = viewModelScope.launch { permanentlyDeleteMedicineData(medicine) }
    fun addInventoryChange(medicineId: Long, amount: Int, reason: String, note: String = "") = viewModelScope.launch {
        db.inventoryDao().insert(InventoryEntity(medicineId = medicineId, changeAmount = amount, reason = reason, note = note))
    }
    fun inventoryFor(medicineId: Long) = db.inventoryDao().observeForMedicine(medicineId)
    fun logDose(medicine: MedicineEntity, action: String, scheduledAt: Long = System.currentTimeMillis()) = viewModelScope.launch {
        // Serialize taps and make each medicine + scheduled occurrence idempotent.
        doseActionMutex.withLock {
            val existing = db.doseLogDao().observeAll().first().any { it.medicineId == medicine.id && it.scheduledAt == scheduledAt }
            if (!existing) db.doseLogDao().insert(DoseLogEntity(medicineId = medicine.id, profileId = medicine.profileId,
                medicineNameSnapshot = medicine.name, scheduledAt = scheduledAt, action = action))
        }
    }
    fun permanentlyDeleteAll() = viewModelScope.launch {
        db.medicineDao().observeRecycleBin().first().forEach { permanentlyDeleteMedicineData(it) }
    }
    fun correctDoseLog(log: DoseLogEntity, action: String, note: String) = viewModelScope.launch {
        val auditNote = buildString {
            if (log.note.isNotBlank()) append(log.note).append("\n")
            append("Corrected from ").append(log.action).append(" to ").append(action)
                .append(" on ").append(DateFormat.getDateTimeInstance().format(Date(System.currentTimeMillis())))
            if (note.isNotBlank()) append(" · ").append(note.trim())
        }
        db.doseLogDao().update(log.copy(action = action, note = auditNote, recordedAt = System.currentTimeMillis()))
    }
    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer {
                val app = context.applicationContext as Application
                DoseleafViewModel(DoseleafDatabase.getInstance(app), app)
            }
        }
    }
}

@Composable
fun DoseleafApp() {
    val context = LocalContext.current
    val vm: DoseleafViewModel = viewModel(factory = DoseleafViewModel.factory(context))
    val profiles by vm.profiles.collectAsState()
    val onboardingScope = rememberCoroutineScope()
    var page by remember { mutableStateOf(Page.HOME) }
    val pageHistory = remember { mutableStateListOf<Page>() }
    fun navigateTo(next: Page) { if (next != page) { pageHistory.add(page); page = next } }
    fun navigateBack() { page = if (pageHistory.isNotEmpty()) pageHistory.removeAt(pageHistory.lastIndex) else Page.HOME }
    var selectedMedicine by remember { mutableStateOf<MedicineEntity?>(null) }
    var selectedProfile by remember { mutableStateOf<FamilyProfileEntity?>(null) }
    var splashVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(1400); splashVisible = false }
    val appPrefs = remember { context.getSharedPreferences("doseleaf", Context.MODE_PRIVATE) }
    val nameSetupDone = appPrefs.getBoolean("name_setup_done", false)
    var firstName by remember { mutableStateOf("") }
    var guideVisible by remember { mutableStateOf(!appPrefs.getBoolean("guide_seen", false) || !nameSetupDone) }
    val firstRunNotificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:${context.packageName}"))) }
            }
        }
    }
    if (splashVisible) { SplashScreen(); return }
    BackHandler(enabled = page != Page.HOME || guideVisible) {
        if (!guideVisible) navigateBack()
    }
    if (guideVisible) {
        GuideScreen(
            initialName = firstName,
            requireName = !nameSetupDone,
            onNameChange = { firstName = it },
            onSkip = { },
            onStart = {
                onboardingScope.launch {
                    if (!nameSetupDone) {
                        vm.renameDefaultProfile(firstName.trim()).join()
                        appPrefs.edit().putBoolean("name_setup_done", true).apply()
                    }
                    appPrefs.edit().putBoolean("guide_seen", true).apply()
                    guideVisible = false
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) firstRunNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                        if (!alarmManager.canScheduleExactAlarms()) runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:${context.packageName}"))) }
                    }
                }
            }
        )
        return
    }
    Scaffold(
        containerColor = PureWhite,
        bottomBar = {
            if (page in listOf(Page.HOME, Page.MEDICINES, Page.PROFILES, Page.HISTORY, Page.SETTINGS)) {
                NavigationBar(containerColor = PureWhite, tonalElevation = 0.dp) {
                    listOf(Page.HOME, Page.MEDICINES, Page.PROFILES, Page.HISTORY, Page.SETTINGS).forEach { item ->
                        NavigationBarItem(
                            selected = page == item, onClick = { navigateTo(item) },
                            icon = { Icon(when(item) {
                                Page.HOME -> Icons.Outlined.Home
                                Page.MEDICINES -> Icons.Outlined.Medication
                                Page.PROFILES -> Icons.Outlined.People
                                Page.HISTORY -> Icons.Outlined.History
                                else -> Icons.Outlined.Settings
                            }, contentDescription = item.name) },
                            label = { Text(when(item) { Page.HOME -> tr(context, "Home"); Page.MEDICINES -> tr(context, "Medicines"); Page.PROFILES -> tr(context, "Family"); Page.HISTORY -> tr(context, "History"); else -> tr(context, "Settings") }) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = ForestGreen, selectedTextColor = ForestGreen, indicatorColor = SoftGreen)
                        )
                    }
                }
            }
        }
    ) { padding ->
        AnimatedContent(targetState = page, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "page", modifier = Modifier.padding(padding)) { target ->
            when (target) {
                Page.HOME -> HomeScreen(vm, { navigateTo(it) }, onEditMedicine = { selectedMedicine = it; navigateTo(Page.EDIT_MEDICINE) })
                Page.MEDICINES -> MedicinesScreen(vm, onAdd = { navigateTo(Page.ADD_MEDICINE) }, onEdit = { selectedMedicine = it; navigateTo(Page.EDIT_MEDICINE) }, onManageProfiles = { navigateTo(Page.PROFILES) })
                Page.ADD_MEDICINE -> AddMedicineDialog(profiles, onCreateProfile = { name, relationship -> vm.addProfile(name, relationship) }, onDismiss = { navigateBack() }, onSave = { profile, name, dosage, times, note, expiry, foodTiming, foodOffset, photoUri ->
                    vm.addMedicine(profile, name, dosage, times, note, expiry, foodTiming, foodOffset, photoUri); navigateBack()
                })
                Page.EDIT_MEDICINE -> selectedMedicine?.let { med -> EditMedicineDialog(med, profiles, onCreateProfile = { name, relationship -> vm.addProfile(name, relationship) }, onDismiss = { navigateBack() }, onSave = { profile, name, dosage, times, note, expiry, foodTiming, foodOffset, photoUri ->
                    vm.updateMedicine(med, profile, name, dosage, times, note, expiry, foodTiming, foodOffset, photoUri); navigateBack()
                }) } ?: EmptyState(Icons.Outlined.Medication, "No medicine selected", "Return to Medicines and choose a medicine to edit.")
                Page.PROFILES -> ProfilesScreen(vm, onOpenProfile = { selectedProfile = it; navigateTo(Page.PROFILE_DETAIL) })
                Page.PROFILE_DETAIL -> selectedProfile?.let { ProfileDetailScreen(it, vm, onBack = { navigateBack() }) } ?: EmptyState(Icons.Outlined.People, "No profile selected", "Return to Family Profiles and choose a profile.")
                Page.HISTORY -> HistoryScreen(vm)
                Page.SETTINGS -> SettingsScreen(vm, { navigateTo(it) })
                Page.ABOUT -> AboutScreen()
                Page.GUIDE -> GuideScreen(initialName = "", requireName = false, onNameChange = { }, onSkip = { }, onStart = { page = Page.HOME })
                Page.BIN -> RecycleBinScreen(vm)
            }
        }
    }
}

private data class UpcomingDose(val medicine: MedicineEntity, val scheduledAt: Long, val hour: Int, val minute: Int)

@Composable private fun HomeScreen(vm: DoseleafViewModel, navigate: (Page) -> Unit, onEditMedicine: (MedicineEntity) -> Unit) {
    val medicines by vm.medicines.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val logs by vm.logs.collectAsState()
    val context = LocalContext.current
    val database = remember { DoseleafDatabase.getInstance(context) }
    val schedules by database.medicineScheduleDao().observeAll().collectAsState(initial = emptyList())
    var now by remember { mutableStateOf(java.time.LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = java.time.LocalDateTime.now(); delay(15_000) } }
    val todayStart = remember(now.toLocalDate()) { now.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val todayEnd = remember(now.toLocalDate()) { now.toLocalDate().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val todaysLogs = logs.filter { it.recordedAt in todayStart until todayEnd }
    val upcomingDoses = remember(medicines, schedules, logs, now) {
        // Round to the minute so a dose scheduled for the current minute is not skipped.
        val windowStart = now.withSecond(0).withNano(0)
        val start = windowStart.atZone(ZoneId.systemDefault())
        val end = windowStart.plusHours(1).atZone(ZoneId.systemDefault())
        val candidateDates = listOf(windowStart.toLocalDate(), windowStart.toLocalDate().plusDays(1))
        medicines.filter { it.isActive }.flatMap { med ->
            val configured = schedules.filter { it.medicineId == med.id && it.enabled }
            val times = if (configured.isNotEmpty()) configured.map { it.hour to it.minute } else listOf(med.reminderHour to med.reminderMinute)
            times.distinct().flatMap { (hour, minute) ->
                candidateDates.mapNotNull { date ->
                    val instant = date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val occurrence = java.time.Instant.ofEpochMilli(instant).atZone(ZoneId.systemDefault())
                    val inWindow = !occurrence.isBefore(start) && !occurrence.isAfter(end)
                    val alreadyHandled = logs.any { it.medicineId == med.id && it.scheduledAt == instant && it.action in listOf("Taken", "Skipped", "Uncertain", "Missed") }
                    if (inWindow && !alreadyHandled) UpcomingDose(med, instant, hour, minute) else null
                }
            }
        }.distinctBy { it.medicine.id to it.scheduledAt }.sortedBy { it.scheduledAt }
    }
    LazyColumn(Modifier.fillMaxSize().background(PureWhite), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Doseleaf", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = DeepGreen) }
                Image(painterResource(com.wafyro.doseleaf.R.drawable.doseleaf_logo), contentDescription = "Doseleaf", modifier = Modifier.size(52.dp))
            }
        }
        item { Text("Your day, organized.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceSoft), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("TODAY", color = SecondaryText, style = MaterialTheme.typography.labelMedium)
                    Text("${medicines.count { it.isActive }} active medicines", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("${todaysLogs.count { it.action == "Taken" }} taken · ${todaysLogs.count { it.action == "Skipped" }} skipped · ${profiles.size} profile(s)", color = SecondaryText)
                    Button(onClick = { navigate(Page.MEDICINES) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = ForestGreen)) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Add or manage medicines") }
                }
            }
        }
        item { Text("Upcoming in the next hour", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        if (upcomingDoses.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = androidx.compose.ui.graphics.Color(0xFFF2FAF5),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Outlined.AccessTime, contentDescription = null, tint = ForestGreen, modifier = Modifier.size(30.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("No upcoming doses", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = DeepGreen)
                            Text("You're all set for now!", style = MaterialTheme.typography.bodyMedium, color = SecondaryText)
                        }
                        Icon(Icons.Outlined.EventAvailable, contentDescription = null, tint = ForestGreen, modifier = Modifier.size(42.dp))
                    }
                }
            }
        }
        items(upcomingDoses, key = { "home-${it.medicine.id}-${it.scheduledAt}" }) { dose ->
            Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, BorderColor), color = PureWhite) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(dose.medicine.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Scheduled time: ${timeToAmPm(String.format("%02d:%02d", dose.hour, dose.minute))}", color = ForestGreen, style = MaterialTheme.typography.bodySmall)
                    if (dose.medicine.dosage.isNotBlank()) Text(dose.medicine.dosage, color = SecondaryText)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.logDose(dose.medicine, "Taken", dose.scheduledAt) }) { Text("Taken") }
                        OutlinedButton(onClick = { vm.logDose(dose.medicine, "Skipped", dose.scheduledAt) }) { Text("Skip") }
                        TextButton(onClick = { onEditMedicine(dose.medicine) }) { Text("Edit") }
                    }
                }
            }
        }
    }
}
@Composable private fun QuickCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = PureWhite, border = BorderStroke(1.dp, BorderColor)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = ForestGreen); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, color = SecondaryText, style = MaterialTheme.typography.bodySmall) }
            Icon(Icons.Outlined.ChevronRight, null, tint = SecondaryText)
        }
    }
}
@Composable private fun MedicinesScreen(vm: DoseleafViewModel, onAdd: () -> Unit, onEdit: (MedicineEntity) -> Unit, onManageProfiles: () -> Unit) {
    val context = LocalContext.current
    val medicines by vm.medicines.collectAsState()
    var pendingDelete by remember { mutableStateOf<MedicineEntity?>(null) }
    Column(Modifier.fillMaxSize().background(PureWhite).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(LocalContext.current, "Medicines"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = onAdd) { Icon(Icons.Outlined.Add, null); Text(" Add") }
        }
        if (medicines.isEmpty()) EmptyState(Icons.Outlined.Medication, "No medicines yet", "Add a medicine and set a daily reminder.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(medicines, key = { it.id }) { med ->
                val db = remember { DoseleafDatabase.getInstance(context) }
                val configuredTimes by remember(med.id) { db.medicineScheduleDao().observeForMedicine(med.id) }.collectAsState(initial = emptyList())
                Card(shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, BorderColor), colors = CardDefaults.cardColors(containerColor = PureWhite)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(med.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                val timeLabel = if (configuredTimes.isNotEmpty()) configuredTimes.filter { it.enabled }.joinToString(", ") { schedule -> String.format(java.util.Locale.getDefault(), "%02d:%02d %s", if (schedule.hour % 12 == 0) 12 else schedule.hour % 12, schedule.minute, if (schedule.hour < 12) "AM" else "PM") } else String.format(java.util.Locale.getDefault(), "%02d:%02d %s", if (med.reminderHour % 12 == 0) 12 else med.reminderHour % 12, med.reminderMinute, if (med.reminderHour < 12) "AM" else "PM")
                                Text(listOf(med.dosage, timeLabel).filter { it.isNotBlank() }.joinToString(" · "), color = SecondaryText)
                                med.expiryDate?.let { expiry ->
                                    val daysLeft = (expiry - System.currentTimeMillis()) / (24L * 60L * 60L * 1000L)
                                    Text(
                                        if (daysLeft < 0) "Expired — check before use" else if (daysLeft <= 30) "Expiry approaching: ${DateFormat.getDateInstance().format(Date(expiry))}" else "Expires: ${DateFormat.getDateInstance().format(Date(expiry))}",
                                        color = if (daysLeft <= 30) ErrorRed else SecondaryText,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            IconButton(onClick = { onEdit(med) }) { Icon(Icons.Outlined.Edit, "Edit", tint = ForestGreen) }
                            IconButton(onClick = { pendingDelete = med }) { Icon(Icons.Outlined.DeleteOutline, "Delete", tint = ErrorRed) }
                        }
                        if (med.foodTiming != "None") Text(tr(LocalContext.current, med.foodTiming) + if (med.foodOffsetMinutes > 0) " · ${med.foodOffsetMinutes} min" else "", color = ForestGreen, style = MaterialTheme.typography.bodySmall)
                        if (med.instructions.isNotBlank()) Text(med.instructions, color = SecondaryText, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Taken", "Skipped", "Uncertain").forEach { status ->
                                OutlinedButton(onClick = { vm.logDose(med, status) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) { Text(status) }
                            }
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { med ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(tr(LocalContext.current, "Move to Recycle Bin?")) },
            text = { Text(tr(LocalContext.current, "${med.name} will be removed from active medicines. You can restore it later.")) },
            confirmButton = { TextButton(onClick = { vm.deleteMedicine(med); pendingDelete = null }) { Text(tr(LocalContext.current, "Move to Bin"), color = ErrorRed) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(tr(LocalContext.current, "Cancel")) } }
        )
    }
}
@Composable private fun InventoryEditor(medicine: MedicineEntity, vm: DoseleafViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val db = remember { DoseleafDatabase.getInstance(context) }
    val transactions by db.inventoryDao().observeForMedicine(medicine.id).collectAsState(initial = emptyList())
    var quantity by remember { mutableStateOf("0") }
    var reason by remember { mutableStateOf("Refill") }
    var note by remember { mutableStateOf("") }
    val stock = transactions.sumOf { it.changeAmount }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Current estimated stock: $stock", fontWeight = FontWeight.SemiBold, color = if (stock <= medicine.lowStockThreshold) ErrorRed else ForestGreen)
        Text("Enter a positive amount to add stock or a negative amount to record use/adjustment.", color = SecondaryText, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(quantity, { quantity = it.filter { c -> c == '-' || c.isDigit() }.take(7) }, label = { Text(tr(LocalContext.current, "Quantity change")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        OutlinedTextField(reason, { reason = it }, label = { Text(tr(LocalContext.current, "Reason (e.g. Refill, Used, Correction)")) }, singleLine = true)
        OutlinedTextField(note, { note = it }, label = { Text(tr(LocalContext.current, "Note (optional)")) }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val amount = quantity.toIntOrNull()
                if (amount != null && amount != 0 && reason.isNotBlank() && stock + amount >= 0) {
                    vm.addInventoryChange(medicine.id, amount, reason.trim(), note.trim())
                    quantity = "0"; note = ""
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = ForestGreen)) { Text(tr(LocalContext.current, "Save stock change")) }
            TextButton(onClick = onClose) { Text(tr(LocalContext.current, "Close")) }
        }
        if (transactions.isNotEmpty()) {
            Text(tr(LocalContext.current, "Recent changes"), style = MaterialTheme.typography.labelMedium)
            transactions.take(3).forEach { tx ->
                Text("${if (tx.changeAmount > 0) "+" else ""}${tx.changeAmount} · ${tx.reason} · ${DateFormat.getDateInstance().format(Date(tx.createdAt))}", color = SecondaryText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun EditMedicineDialog(
    medicine: MedicineEntity, profiles: List<FamilyProfileEntity>, onCreateProfile: (String, String) -> Unit, onDismiss: () -> Unit,
    onSave: (Long, String, String, List<Pair<Int, Int>>, String, Long?, String, Int, String?) -> Unit
) {
    val context = LocalContext.current
    var foodTiming by remember(medicine.id) { mutableStateOf(medicine.foodTiming) }
    var foodOffset by remember(medicine.id) { mutableIntStateOf(medicine.foodOffsetMinutes) }
    var photoUri by remember(medicine.id) { mutableStateOf(medicine.prescriptionPhotoUri) }
    var customFoodOffset by remember(medicine.id) { mutableStateOf(medicine.foodOffsetMinutes.toString()) }
    var name by remember(medicine.id) { mutableStateOf(medicine.name) }
    var dosage by remember(medicine.id) { mutableStateOf(medicine.dosage) }
    var selectedTimes by remember(medicine.id) { mutableStateOf(listOf(String.format("%02d:%02d", medicine.reminderHour, medicine.reminderMinute))) }
    val scheduleDb = remember { DoseleafDatabase.getInstance(context) }
    val existingSchedules by remember(medicine.id) { scheduleDb.medicineScheduleDao().observeForMedicine(medicine.id) }.collectAsState(initial = emptyList())
    LaunchedEffect(existingSchedules) {
        if (existingSchedules.isNotEmpty()) selectedTimes = existingSchedules.filter { it.enabled }.map { String.format("%02d:%02d", it.hour, it.minute) }.ifEmpty { listOf(String.format("%02d:%02d", medicine.reminderHour, medicine.reminderMinute)) }
    }
    var note by remember(medicine.id) { mutableStateOf(medicine.instructions) }
    var profileId by remember(medicine.id) { mutableLongStateOf(medicine.profileId) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val editScrollState = rememberScrollState()
    val editScope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize(), color = PureWhite) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Back") }
                Text("Edit medicine", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp).verticalScroll(editScrollState), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (error != null) Surface(color = androidx.compose.ui.graphics.Color(0xFFFEE4E2), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.ErrorOutline, null, tint = ErrorRed); Spacer(Modifier.width(8.dp)); Text(error!!, color = ErrorRed, fontWeight = FontWeight.Medium) }
                }
                OutlinedTextField(value = name, onValueChange = { name = it; error = null }, label = { Text("Medicine name *") }, isError = error == "Enter a medicine name.", singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = dosage, onValueChange = { dosage = it }, label = { Text("Dosage (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Reminder times *", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = {
                    val parts = selectedTimes.lastOrNull()?.split(":") ?: listOf("09", "00")
                    TimePickerDialog(context, { _, h, m -> selectedTimes = (selectedTimes + String.format("%02d:%02d", h, m)).distinct(); error = null }, parts.getOrNull(0)?.toIntOrNull() ?: 9, parts.getOrNull(1)?.toIntOrNull() ?: 0, false).show()
                }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Schedule, null); Spacer(Modifier.width(6.dp)); Text("Add time · AM/PM") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    selectedTimes.forEach { raw ->
                        FilterChip(selected = true, onClick = { if (selectedTimes.size > 1) selectedTimes = selectedTimes - raw; error = null }, label = { Text(timeToAmPm(raw)) }, trailingIcon = { Icon(Icons.Outlined.Close, null) })
                    }
                }
                if (error == "Choose a valid reminder time.") Text(error!!, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Instructions (optional)") }, modifier = Modifier.fillMaxWidth())
                Text("Food timing (optional)", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { listOf("None", "Before food", "With food", "After food").forEach { tag -> FilterChip(selected = foodTiming == tag, onClick = { foodTiming = tag }, label = { Text(tag) }) } }
                if (foodTiming == "Before food" || foodTiming == "After food") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf(0, 15, 30, 60).forEach { mins -> FilterChip(selected = foodOffset == mins, onClick = { foodOffset = mins; customFoodOffset = mins.toString() }, label = { Text(if (mins == 0) "No offset" else "$mins min") }) } }
                    OutlinedTextField(value = customFoodOffset, onValueChange = { raw -> customFoodOffset = raw.filter(Char::isDigit).take(4); customFoodOffset.toIntOrNull()?.takeIf { it in 0..1440 }?.let { foodOffset = it } }, label = { Text("Custom interval in minutes") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
                Text("Select profile *", style = MaterialTheme.typography.labelMedium)
                if (error == "Please select a profile.") Text(error!!, color = ErrorRed, fontWeight = FontWeight.SemiBold)
                profiles.forEach { profile -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = profileId == profile.id, onClick = { profileId = profile.id; error = null }); Text(profile.name) } }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = {
                    val parsed = selectedTimes.map { raw -> val parts = raw.split(":"); val h = parts.getOrNull(0)?.toIntOrNull(); val m = parts.getOrNull(1)?.toIntOrNull(); if (parts.size == 2 && h != null && h in 0..23 && m != null && m in 0..59) h to m else null }
                    error = when {
                        name.isBlank() -> "Enter a medicine name."
                        profileId == 0L || profiles.none { it.id == profileId } -> "Please select a profile."
                        parsed.isEmpty() || parsed.any { it == null } -> "Choose a valid reminder time."
                        else -> null
                    }
                    if (error != null) editScope.launch { editScrollState.animateScrollTo(0) }
                    else if (!saving) {
                        saving = true
                        onSave(profileId, name.trim(), dosage, parsed.filterNotNull().distinct().sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second }), note, medicine.expiryDate, foodTiming, foodOffset, photoUri)
                    }
                }, enabled = !saving, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = ForestGreen)) { Text("Save changes") }
            }
        }
    }
}

private fun timeToAmPm(raw: String): String {
    val p = raw.split(":"); val h = p.getOrNull(0)?.toIntOrNull() ?: 9; val m = p.getOrNull(1)?.toIntOrNull() ?: 0
    return String.format(java.util.Locale.getDefault(), "%02d:%02d %s", if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun AddMedicineDialog(
    profiles: List<FamilyProfileEntity>, onCreateProfile: (String, String) -> Unit, onDismiss: () -> Unit,
    onSave: (Long, String, String, List<Pair<Int, Int>>, String, Long?, String, Int, String?) -> Unit
) {
    val context = LocalContext.current
    var foodTiming by remember { mutableStateOf("None") }
    var foodOffset by remember { mutableIntStateOf(0) }
    var photoUri by remember { mutableStateOf<String?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; photoUri = uri.toString() }
    }
    var customFoodOffset by remember { mutableStateOf("0") }
    var name by remember { mutableStateOf("") }

    var selectedTimes by remember { mutableStateOf(listOf("")) }

    var note by remember { mutableStateOf("") }
    var profileId by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreateProfile by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }
    var newProfileRelationship by remember { mutableStateOf("") }
    val formScrollState = rememberScrollState()
    val formScope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize().background(PureWhite), color = PureWhite) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Back") }
                    Text("Add medicine", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp).verticalScroll(formScrollState), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (error != null) {
                    Surface(color = androidx.compose.ui.graphics.Color(0xFFFEE4E2), shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.ErrorOutline, null, tint = ErrorRed); Spacer(Modifier.width(8.dp)); Text(error!!, color = ErrorRed, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                OutlinedTextField(name, { name = it; error = null }, label = { Text(tr(LocalContext.current, "Medicine name *")) }, isError = error == "Enter a medicine name.", singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Times per day", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..4).forEach { count -> FilterChip(selected = selectedTimes.size == count, onClick = {
                        selectedTimes = when { count < selectedTimes.size -> selectedTimes.take(count); count > selectedTimes.size -> selectedTimes + List(count - selectedTimes.size) { "" }; else -> selectedTimes }
                    }, label = { Text(if (count == 1) "1 time" else "$count times") }) }
                }
                Text(tr(LocalContext.current, "Reminder times"), style = MaterialTheme.typography.labelMedium)
                if (error == "Choose a time for every reminder." || error == "Add at least one reminder time.") Text(error!!, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    selectedTimes.forEachIndexed { index, time ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${index + 1}.")
                            OutlinedButton(onClick = {
                                val parts = time.split(":"); val h = parts.getOrNull(0)?.toIntOrNull() ?: 9; val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
                                TimePickerDialog(context, { _, hour, minute -> selectedTimes = selectedTimes.toMutableList().also { it[index] = String.format("%02d:%02d", hour, minute) }; error = null }, h, m, false).show()
                            }, modifier = Modifier.weight(1f)) { Text(if (time.isBlank()) "Choose your time" else timeToAmPm(time)) }
                        }
                    }
                }
                OutlinedTextField(note, { note = it }, label = { Text(tr(LocalContext.current, "Instructions (optional)")) })
                Text(tr(LocalContext.current, "Food timing (optional)"), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("None", "Before food", "With food", "After food").forEach { tag -> FilterChip(selected = foodTiming == tag, onClick = { foodTiming = tag }, label = { Text(tag) }) } }
                if (foodTiming == "Before food" || foodTiming == "After food") {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf(0, 15, 30, 60).forEach { mins -> FilterChip(selected = foodOffset == mins, onClick = { foodOffset = mins; customFoodOffset = mins.toString() }, label = { Text(if (mins == 0) "No offset" else "$mins min") }) } }
                OutlinedTextField(customFoodOffset, { raw -> customFoodOffset = raw.filter(Char::isDigit).take(4); customFoodOffset.toIntOrNull()?.takeIf { it in 0..1440 }?.let { foodOffset = it } }, label = { Text(tr(LocalContext.current, "Custom interval in minutes")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
                if (profiles.isNotEmpty()) {
                    Text(tr(LocalContext.current, "Select profile *"), style = MaterialTheme.typography.labelMedium)
                    if (error == "Please select a profile.") Text(error!!, color = ErrorRed, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    profiles.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = profileId == p.id, onClick = { profileId = p.id; error = null })
                            Text(p.name)
                        }
                    }
                    TextButton(onClick = { showCreateProfile = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Create New Profile")
                    }
                } else {
                    Text("No profiles yet. Create one to continue.", color = SecondaryText)
                    TextButton(onClick = { showCreateProfile = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Create New Profile")
                    }
                }
                }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = {
            val parsed = selectedTimes.map { raw ->
                val parts = raw.split(":")
                val h = parts.getOrNull(0)?.toIntOrNull()
                val m = parts.getOrNull(1)?.toIntOrNull()
                if (parts.size != 2 || h == null || h !in 0..23 || m == null || m !in 0..59) null else h to m
            }
            error = when {
                name.isBlank() -> "Enter a medicine name."
                profiles.isEmpty() -> "Create a profile first."
                profileId == 0L || profiles.none { it.id == profileId } -> "Please select a profile."
                parsed.any { it == null } -> "Choose a time for every reminder."
                parsed.filterNotNull().distinct().isEmpty() -> "Add at least one reminder time."
                else -> null
            }
            if (error != null) formScope.launch { formScrollState.animateScrollTo(0) }
            else onSave(profileId, name, "", parsed.filterNotNull().distinct().sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second }), note, null, foodTiming, foodOffset, photoUri)
        }, modifier = Modifier.weight(1f)) { Text("Save medicine") }
            }
        }
    }
    if (showCreateProfile) {
        AlertDialog(
            onDismissRequest = { showCreateProfile = false },
            title = { Text("Create New Profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newProfileName, onValueChange = { newProfileName = it }, label = { Text("Name *") }, singleLine = true)
                    OutlinedTextField(value = newProfileRelationship, onValueChange = { newProfileRelationship = it }, label = { Text("Relationship (optional)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = newProfileName.isNotBlank(), onClick = {
                    onCreateProfile(newProfileName.trim(), newProfileRelationship.trim())
                    showCreateProfile = false
                    newProfileName = ""
                    newProfileRelationship = ""
                }) { Text("Create profile") }
            },
            dismissButton = { TextButton(onClick = { showCreateProfile = false }) { Text("Cancel") } }
        )
    }
}
@Composable private fun ProfilesScreen(vm: DoseleafViewModel, onOpenProfile: (FamilyProfileEntity) -> Unit) {
    val profiles by vm.profiles.collectAsState()
    val medicines by vm.medicines.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FamilyProfileEntity?>(null) }
    var deleting by remember { mutableStateOf<FamilyProfileEntity?>(null) }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr(LocalContext.current, "Family Profiles"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = { showAdd = true }) { Icon(Icons.Outlined.Add, null); Text(" Add") }
        }
        Spacer(Modifier.height(12.dp))
        profiles.forEach { p ->
            Surface(onClick = { onOpenProfile(p) }, color = PureWhite, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, BorderColor), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Person, null, tint = ForestGreen); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.SemiBold)
                        Text(p.relationship.ifBlank { "Family member" }, color = SecondaryText)
                        Text("${medicines.count { it.profileId == p.id }} active medicine(s)", color = SecondaryText, style = MaterialTheme.typography.bodySmall)
                    }
                    if (p.isDefault) Text(tr(LocalContext.current, "Default"), color = ForestGreen, style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = { editing = p }) { Icon(Icons.Outlined.Edit, "Edit profile", tint = ForestGreen) }
                    if (!p.isDefault) IconButton(onClick = { deleting = p }) { Icon(Icons.Outlined.DeleteOutline, "Delete profile", tint = ErrorRed) }
                }
            }
        }
    }
    if (showAdd) {
        var name by remember { mutableStateOf("") }; var relationship by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showAdd = false }, title = { Text(tr(LocalContext.current, "Add profile")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(tr(LocalContext.current, "Name")) }, singleLine = true)
                OutlinedTextField(relationship, { relationship = it }, label = { Text(tr(LocalContext.current, "Relationship (optional)")) }, singleLine = true)
            } },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { vm.addProfile(name, relationship); showAdd = false } }) { Text(tr(LocalContext.current, "Save")) } },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text(tr(LocalContext.current, "Cancel")) } })
    }
    editing?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }; var relationship by remember(p.id) { mutableStateOf(p.relationship) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(tr(LocalContext.current, "Edit profile")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(tr(LocalContext.current, "Name")) }, singleLine = true)
                OutlinedTextField(relationship, { relationship = it }, label = { Text(tr(LocalContext.current, "Relationship")) }, singleLine = true)
            } },
            confirmButton = { TextButton(onClick = { vm.updateProfile(p, name, relationship); editing = null }) { Text(tr(LocalContext.current, "Save changes")) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(tr(LocalContext.current, "Cancel")) } })
    }
    deleting?.let { p ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(tr(LocalContext.current, "Delete ${p.name}?")) },
            text = { Text(if (p.isDefault) "The default Self profile cannot be deleted. You can edit its name instead." else "Its active medicines will move to Recycle Bin and their reminders will stop. Existing dose history will be retained. The profile will then be removed.") },
            confirmButton = { TextButton(enabled = !p.isDefault, onClick = { vm.deleteProfile(p); deleting = null }) { Text(tr(LocalContext.current, "Delete"), color = ErrorRed) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr(LocalContext.current, "Cancel")) } })
    }
}

@Composable private fun ProfileDetailScreen(profile: FamilyProfileEntity, vm: DoseleafViewModel, onBack: () -> Unit) {
    val medicines by vm.medicines.collectAsState()
    val logs by vm.logs.collectAsState()
    val profileMedicines = medicines.filter { it.profileId == profile.id && it.isActive }
    val profileLogs = logs.filter { it.profileId == profile.id }.take(20)
    Column(Modifier.fillMaxSize().background(PureWhite).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Back") }
            Text(profile.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        Text(profile.relationship.ifBlank { "Family member" }, color = SecondaryText)
        if (profile.isDefault) Text("Default profile", color = ForestGreen, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        Text("Active medicines (${profileMedicines.size})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (profileMedicines.isEmpty()) Text("No active medicines for this profile.", color = SecondaryText, modifier = Modifier.padding(vertical = 12.dp))
        else LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(profileMedicines, key = { it.id }) { med ->
                Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, BorderColor), color = PureWhite) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(med.name, fontWeight = FontWeight.SemiBold)
                        if (med.dosage.isNotBlank()) Text(med.dosage, color = SecondaryText)
                        if (med.instructions.isNotBlank()) Text(med.instructions, color = SecondaryText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Recent dose history (${profileLogs.size})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (profileLogs.isEmpty()) Text("No dose history recorded yet.", color = SecondaryText, modifier = Modifier.padding(vertical = 12.dp))
        else LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(profileLogs, key = { it.id }) { log ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(log.medicineNameSnapshot, fontWeight = FontWeight.Medium); Text(DateFormat.getDateTimeInstance().format(Date(log.scheduledAt)), color = SecondaryText, style = MaterialTheme.typography.bodySmall) }
                    Text(log.action, color = if (log.action == "Taken") ForestGreen else SecondaryText)
                }
            }
        }
    }
}

@Composable private fun HistoryScreen(vm: DoseleafViewModel) {
    val logs by vm.logs.collectAsState()
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("All") }
    var editing by remember { mutableStateOf<DoseLogEntity?>(null) }
    val statuses = listOf("All", "Taken", "Skipped", "Uncertain", "Missed")
    val filtered = logs.filter {
        (query.isBlank() || it.medicineNameSnapshot.contains(query, ignoreCase = true)) &&
        (status == "All" || it.action.equals(status, ignoreCase = true))
    }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(tr(LocalContext.current, "Dose History"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text(tr(LocalContext.current, "Search medicine")) }, singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) })
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { statuses.forEach { item -> FilterChip(selected = status == item, onClick = { status = item }, label = { Text(item) }) } }
        Spacer(Modifier.height(8.dp))
        if (filtered.isEmpty()) EmptyState(Icons.Outlined.History, "No matching history", "Try another search or status filter.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filtered, key = { it.id }) { log ->
                Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, BorderColor), color = PureWhite) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(log.medicineNameSnapshot, fontWeight = FontWeight.SemiBold)
                            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(log.recordedAt)), color = SecondaryText, style = MaterialTheme.typography.bodySmall)
                            Text(tr(LocalContext.current, "Status: ${log.action}"), color = ForestGreen, fontWeight = FontWeight.Medium)
                            if (log.note.isNotBlank()) Text(log.note, color = SecondaryText, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                        }
                        IconButton(onClick = { editing = log }) { Icon(Icons.Outlined.Edit, "Correct dose record", tint = ForestGreen) }
                    }
                }
            }
        }
    }
    editing?.let { log ->
        var newStatus by remember(log.id) { mutableStateOf(log.action) }
        var note by remember(log.id) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(tr(LocalContext.current, "Correct dose record")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${log.medicineNameSnapshot} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(log.recordedAt))}")
                listOf("Taken", "Skipped", "Uncertain", "Missed", "Pending").forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = newStatus == option, onClick = { newStatus = option }); Text(option) }
                }
                OutlinedTextField(note, { note = it }, label = { Text(tr(LocalContext.current, "Correction note (optional)")) }, minLines = 2)
                Text(tr(LocalContext.current, "The previous status and correction time will be appended to the record note."), color = SecondaryText, style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = { vm.correctDoseLog(log, newStatus, note); editing = null }) { Text(tr(LocalContext.current, "Save correction")) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(tr(LocalContext.current, "Cancel")) } })
    }
}
@Composable private fun RecycleBinScreen(vm: DoseleafViewModel) {
    val bin by vm.bin.collectAsState()
    var confirm by remember { mutableStateOf<MedicineEntity?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(tr(LocalContext.current, "Recycle Bin"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(tr(LocalContext.current, "Restore deleted medicines or permanently remove them."), color = SecondaryText) }
            TextButton(enabled = bin.isNotEmpty(), onClick = { confirmAll = true }) { Text("Delete all", color = if (bin.isNotEmpty()) ErrorRed else SecondaryText) }
        }
        Spacer(Modifier.height(12.dp))
        if (bin.isEmpty()) EmptyState(Icons.Outlined.DeleteOutline, "Recycle Bin is empty", "Deleted medicines will appear here.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(bin, key = { it.id }) { med ->
                Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, BorderColor), color = PureWhite) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(med.name, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { vm.restoreMedicine(med) }) { Text(tr(LocalContext.current, "Restore")) }
                            TextButton(onClick = { confirm = med }) { Text(tr(LocalContext.current, "Delete permanently"), color = ErrorRed) }
                        }
                    }
                }
            }
        }
    }
    if (confirmAll) AlertDialog(onDismissRequest = { confirmAll = false }, title = { Text("Delete all permanently?") }, text = { Text("This will permanently delete all ${bin.size} medicine(s) in the Recycle Bin. Dose history and profiles are not included. This cannot be undone.") }, confirmButton = { TextButton(onClick = { vm.permanentlyDeleteAll(); confirmAll = false }) { Text("Delete all", color = ErrorRed) } }, dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } })
    confirm?.let { med ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(tr(LocalContext.current, "Delete permanently?")) },
            text = { Text(tr(LocalContext.current, "This will permanently remove ${med.name}. This action cannot be undone.")) },
            confirmButton = { TextButton(onClick = { vm.permanentlyDelete(med); confirm = null }) { Text(tr(LocalContext.current, "Delete"), color = ErrorRed) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr(LocalContext.current, "Cancel")) } })
    }
}
@Composable private fun SettingsScreen(vm: DoseleafViewModel, navigate: (Page) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("doseleaf_settings", Context.MODE_PRIVATE) }
    var vibration by remember { mutableStateOf(prefs.getBoolean("vibration", true)) }
    var reminders by remember { mutableStateOf(prefs.getBoolean("reminders", true)) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = selectedCategory != null) { selectedCategory = null }
    var snoozeDialog by remember { mutableStateOf(false) }
    var snoozeMinutes by remember { mutableIntStateOf(prefs.getInt("snooze_minutes", 10)) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    var dataMessage by remember { mutableStateOf<String?>(null) }
    var reportFrom by remember { mutableStateOf(LocalDate.now().minusDays(30).toString()) }
    var reportTo by remember { mutableStateOf(LocalDate.now().toString()) }
    var reportMedicineQuery by remember { mutableStateOf("") }
    var reportProfileId by remember { mutableStateOf<Long?>(null) }
    var reportStatus by remember { mutableStateOf("All") }
    val reportProfiles by vm.profiles.collectAsState()
    val reportStatuses = listOf("All", "Taken", "Skipped", "Uncertain", "Missed", "Pending")
    val scope = rememberCoroutineScope()
    val database = remember { DoseleafDatabase.getInstance(context) }
    val backupCreator = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching { LocalExport.exportBackupJson(context, uri, database) }
                .onSuccess { dataMessage = "Backup saved. Keep the file somewhere safe." }
                .onFailure { dataMessage = "Backup failed: ${it.message ?: "unknown error"}" }
        }
    }
    val csvCreator = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            runCatching { LocalExport.exportDoseHistoryCsv(context, uri, database, reportProfileId, reportMedicineQuery, reportStatus) }
                .onSuccess { dataMessage = "Dose history CSV exported." }
                .onFailure { dataMessage = "CSV export failed: ${it.message ?: "unknown error"}" }
        }
    }
    val pdfCreator = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            val range = runCatching {
                val from = LocalDate.parse(reportFrom).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val to = LocalDate.parse(reportTo).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
                require(from <= to) { "Start date must not be after end date." }
                from to to
            }
            if (range.isFailure) {
                dataMessage = "Invalid date range. Use YYYY-MM-DD and check start/end dates."
            } else {
                runCatching { LocalExport.exportDoseHistoryPdf(context, uri, database, range.getOrThrow().first, range.getOrThrow().second, reportProfileId, reportMedicineQuery, reportStatus) }
                    .onSuccess { dataMessage = "PDF report exported for $reportFrom to $reportTo." }
                    .onFailure { dataMessage = "PDF export failed: ${it.message ?: "unknown error"}" }
            }
        }
    }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingRestore = uri
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        dataMessage = if (granted) "Notifications enabled. Check Android alarm settings too." else "Notifications are not permitted. Enable them in Android Settings to receive reminders."
    }
    if (selectedCategory == null) {
        LazyColumn(Modifier.fillMaxSize().background(PureWhite), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text(tr(LocalContext.current, "Settings"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
            item { Text(tr(LocalContext.current, "Choose a category. Each opens its own page."), color = SecondaryText) }
            item { SettingRow("Reminders & Notifications", "Sound preferences, vibration and reminder scheduling", Icons.Outlined.NotificationsActive) { selectedCategory = "Reminders" } }
            item { SettingRow("Family & Profiles", "Manage people and their medicine profiles", Icons.Outlined.People) { selectedCategory = "Profiles" } }
            item { SettingRow("Appearance", "Display preferences", Icons.Outlined.Palette) { selectedCategory = "Appearance" } }
            item { SettingRow("Data & Backup", "Local backup, Google Drive, restore and reports", Icons.Outlined.SaveAlt) { selectedCategory = "Data" } }
            item { SettingRow("Privacy & Permissions", "Notification and exact alarm access", Icons.Outlined.Security) { selectedCategory = "Permissions" } }
            item { SettingRow("Recycle Bin", "Restore or permanently delete medicines", Icons.Outlined.DeleteOutline) { navigate(Page.BIN) } }
            item { SettingRow("Help & About", "How to use and app information", Icons.Outlined.Info) { selectedCategory = "About" } }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().background(PureWhite), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { selectedCategory = null }) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Back") }
            Text(tr(LocalContext.current, when(selectedCategory) { "Reminders" -> "Reminders & Notifications"; "Profiles" -> "Family & Profiles"; "Appearance" -> "Appearance"; "Data" -> "Data & Backup"; "Permissions" -> "Privacy & Permissions"; else -> "Help & About" }), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        } }
        item { Text(tr(LocalContext.current, "Configure this category below."), color = SecondaryText) }
        if (selectedCategory == "Profiles") {
        item { SettingRow("Manage family profiles", "Add, edit or delete family members", Icons.Outlined.People) { navigate(Page.PROFILES) } }
        }
        if (selectedCategory == "Reminders") {
        item { SectionLabel("Preferences") }
        item { SettingRow("Test alarm sound", "Play the phone's current alarm tone", Icons.Outlined.VolumeUp) {
            runCatching { RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.play() }
                .onFailure { dataMessage = "Unable to play the alarm sound on this device." }
        } }
        item { SettingToggle("Reminder features", "Enable or disable reminder scheduling", reminders) {
            reminders = it
            prefs.edit().putBoolean("reminders", it).apply()
            scope.launch {
                vm.medicines.value.forEach { medicine ->
                    if (it) ReminderScheduler.schedule(context, medicine)
                    else ReminderScheduler.cancel(context, medicine.id)
                }
            }
        } }
        item { SettingToggle("Haptic preference", "Store your vibration preference", vibration) {
            vibration = it; prefs.edit().putBoolean("vibration", it).apply()
        } }
        item { SettingRow("Snooze duration", "Current: $snoozeMinutes minutes", Icons.Outlined.Snooze) { snoozeDialog = true } }
        }
        if (selectedCategory == "Data") {
        item { SectionLabel("Data & help") }
        item { SettingRow("Create local backup", "Save profiles, medicines, dose history and local records to a JSON file", Icons.Outlined.SaveAlt) {
            backupCreator.launch("doseleaf-backup.json")
        } }
        item {
            Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, BorderColor), color = PureWhite, onClick = {
                scope.launch {
                    runCatching {
                        val backupFile = File(context.cacheDir, "doseleaf-backup.json")
                        if (!backupFile.exists()) backupFile.createNewFile()
                        val backupUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", backupFile)
                        LocalExport.exportBackupJson(context, backupUri, database)
                        val shareUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", backupFile)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_STREAM, shareUri)
                            clipData = ClipData.newUri(context.contentResolver, "Doseleaf backup", shareUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val driveIntent = Intent(send).setPackage("com.google.android.apps.docs")
                        if (driveIntent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(driveIntent)
                        } else {
                            require(send.resolveActivity(context.packageManager) != null) { "Google Drive is not installed and no compatible backup app is available." }
                            context.startActivity(Intent.createChooser(send, "Save Doseleaf backup"))
                        }
                    }.onFailure { dataMessage = "Could not prepare Google Drive backup: ${it.message ?: "unknown error"}" }
                }
            }) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CloudUpload, null, tint = ForestGreen); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text("Google Drive backup", fontWeight = FontWeight.SemiBold); Surface(color = SoftGreen, shape = RoundedCornerShape(6.dp)) { Text("Recommended", color = ForestGreen, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)) } }; Text("Send a JSON backup to Google Drive or another compatible app", color = SecondaryText, style = MaterialTheme.typography.bodySmall) }
                    Icon(Icons.Outlined.ChevronRight, null, tint = SecondaryText)
                }
            }
        }
        item { SettingRow("Restore local backup", "Replace this device's Doseleaf data from a backup file", Icons.Outlined.Restore) {
            backupPicker.launch(arrayOf("application/json", "text/json", "*/*"))
        } }
        item { SettingRow("Export dose history (CSV)", "Create a spreadsheet-friendly history file", Icons.Outlined.FileDownload) {
            csvCreator.launch("doseleaf-dose-history.csv")
        } }
        item {
            Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, BorderColor), color = PureWhite) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr(LocalContext.current, "PDF report date range"), fontWeight = FontWeight.SemiBold)
                    Text(tr(LocalContext.current, "Enter dates as YYYY-MM-DD. The end date is included."), color = SecondaryText, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(reportMedicineQuery, { reportMedicineQuery = it }, label = { Text(tr(LocalContext.current, "Filter medicine name (optional)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(tr(LocalContext.current, "Select profile *"), style = MaterialTheme.typography.labelMedium)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(selected = reportProfileId == null, onClick = { reportProfileId = null }, label = { Text(tr(LocalContext.current, "All profiles")) })
                        reportProfiles.forEach { profile -> FilterChip(selected = reportProfileId == profile.id, onClick = { reportProfileId = profile.id }, label = { Text(profile.name) }) }
                    }
                    Text(tr(LocalContext.current, "Dose status"), style = MaterialTheme.typography.labelMedium)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        reportStatuses.forEach { option -> FilterChip(selected = reportStatus == option, onClick = { reportStatus = option }, label = { Text(option) }) }
                    }
                    OutlinedTextField(reportFrom, { reportFrom = it }, label = { Text(tr(LocalContext.current, "From date")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(reportTo, { reportTo = it }, label = { Text(tr(LocalContext.current, "To date")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { pdfCreator.launch("doseleaf-dose-history.pdf") }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = ForestGreen)) {
                        Icon(Icons.Outlined.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text(tr(LocalContext.current, "Export PDF report"))
                    }
                }
            }
        }
        item { dataMessage?.let { msg ->
            Surface(shape = RoundedCornerShape(12.dp), color = SurfaceSoft) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, modifier = Modifier.weight(1f), color = SecondaryText, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { dataMessage = null }) { Text(tr(LocalContext.current, "Dismiss")) }
                }
            }
        } }
        }
        if (selectedCategory == "About") {
        item { SettingRow("How to use", "View the getting-started guide", Icons.Outlined.MenuBook) { navigate(Page.GUIDE) } }
        item { SettingRow("About Doseleaf", "Wafyro Software · Version 1.1.0", Icons.Outlined.Info) { navigate(Page.ABOUT) } }
        }
        if (selectedCategory == "Permissions") {
        item { SettingRow("Enable notifications", "Request permission to show medicine reminder notifications", Icons.Outlined.NotificationsActive) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        } }
        item { SettingRow("Exact alarm access", "Open Android settings if reminders are not firing on time", Icons.Outlined.Alarm) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:${context.packageName}")))
            } else {
                dataMessage = "Exact alarm access is not required on this Android version."
            }
        } }
        item { SettingRow("Full-screen alarm access", "Allow Android to show the alarm screen over the lock screen when supported", Icons.Outlined.Fullscreen) {
            if (Build.VERSION.SDK_INT >= 34) {
                runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).setData(Uri.parse("package:${context.packageName}"))) }
                    .onFailure { dataMessage = "Open this app's special app access settings and check full-screen notifications." }
            } else dataMessage = "Full-screen alarm behaviour is controlled by Android and notification settings on this version."
        } }
        item { SettingRow("Notification settings", "Open Android app notification settings", Icons.Outlined.Settings) {
            context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
        } }
        }
    }
    if (snoozeDialog) {
        AlertDialog(onDismissRequest = { snoozeDialog = false }, title = { Text(tr(LocalContext.current, "Snooze duration")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { listOf(5, 10, 15, 20, 30).forEach { minutes ->
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = snoozeMinutes == minutes, onClick = { snoozeMinutes = minutes }); Text("$minutes minutes") }
            } } },
            confirmButton = { TextButton(onClick = { prefs.edit().putInt("snooze_minutes", snoozeMinutes).apply(); snoozeDialog = false }) { Text(tr(LocalContext.current, "Save")) } },
            dismissButton = { TextButton(onClick = { snoozeDialog = false }) { Text(tr(LocalContext.current, "Cancel")) } })
    }
    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(tr(LocalContext.current, "Replace local Doseleaf data?")) },
            text = { Text("Restoring replaces the profiles, medicines, dose history and inventory records currently on this device. Export a backup of the current data first if you may need it.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    scope.launch {
                        runCatching { LocalExport.restoreBackupJson(context, uri, database) }
                            .onSuccess {
                                dataMessage = "Backup restored. Reminder alarms may need to be checked in Android settings."
                                // Rebuild active reminders from restored medicine records.
                                database.medicineDao().observeCurrent().first().forEach { medicine ->
                                    ReminderScheduler.schedule(context, medicine)
                                }
                            }
                            .onFailure { dataMessage = "Restore failed: ${it.message ?: "unknown error"}" }
                    }
                }) { Text(tr(LocalContext.current, "Replace data"), color = ErrorRed) }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text(tr(LocalContext.current, "Cancel")) } }
        )
    }
}
@Composable private fun SectionLabel(text: String) { Text(tr(LocalContext.current, text).uppercase(), color = ForestGreen, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp)) }
@Composable private fun SettingRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, click: () -> Unit) {
    val context = LocalContext.current
    Surface(onClick = click, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, BorderColor), color = PureWhite) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = ForestGreen); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(tr(context, title), fontWeight = FontWeight.Medium); Text(tr(context, subtitle), color = SecondaryText, style = MaterialTheme.typography.bodySmall) }
            Icon(Icons.Outlined.ChevronRight, null, tint = SecondaryText)
        }
    }
}
@Composable private fun SettingToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, BorderColor), color = PureWhite) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(tr(context, title), fontWeight = FontWeight.Medium); Text(tr(context, subtitle), color = SecondaryText, style = MaterialTheme.typography.bodySmall) }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
@Composable private fun AboutScreen() {
    Column(Modifier.fillMaxSize().background(PureWhite).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(30.dp))
        Image(painterResource(com.wafyro.doseleaf.R.drawable.doseleaf_logo), contentDescription = "Doseleaf", modifier = Modifier.size(112.dp))
        Text("Doseleaf", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = DeepGreen)
        Text(tr(LocalContext.current, "Smart Medicine Reminder"), color = SecondaryText)
        Spacer(Modifier.height(20.dp))
        Text(tr(LocalContext.current, "About Doseleaf"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Organize medication schedules and record dose activity. Your information is stored locally on this device.", color = SecondaryText)
        val context = LocalContext.current
        Button(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://doseleaf.amwafy.xyz/support/"))
                runCatching { context.startActivity(intent) }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = ForestGreen)
        ) { Text("Send Feedback") }
        Spacer(Modifier.weight(1f))
        Text(tr(LocalContext.current, "FROM"), style = MaterialTheme.typography.labelSmall, color = SecondaryText)
        Text(tr(LocalContext.current, "Wafyro Software"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = DeepGreen)
        Text(tr(LocalContext.current, "Version 1.1.0 (2)"), color = SecondaryText, style = MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(PureWhite), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = true, enter = fadeIn() + scaleIn()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(painterResource(com.wafyro.doseleaf.R.drawable.doseleaf_logo), contentDescription = "Doseleaf", modifier = Modifier.size(112.dp))
                Text("Doseleaf", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = DeepGreen)
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr(LocalContext.current, "FROM"), style = MaterialTheme.typography.labelSmall, color = SecondaryText)
            Text(tr(LocalContext.current, "Wafyro Software"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = DeepGreen)
        }
    }
}

@Composable private fun GuideScreen(
    initialName: String,
    requireName: Boolean,
    onNameChange: (String) -> Unit,
    onSkip: () -> Unit,
    onStart: () -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    val titles = listOf("Create a profile", "Add your medicine", "Set your schedule", "Enable reminders", "Track each dose")
    val descriptions = listOf(
        "Create a profile for yourself or a family member.",
        "Add a medicine name and the details you want to track.",
        "Choose reminder times that fit your prescribed schedule.",
        "Allow notifications and exact alarms so reminders can reach you on time.",
        "Record dose statuses and review your history."
    )
    val icons = listOf(Icons.Outlined.People, Icons.Outlined.Medication, Icons.Outlined.Schedule, Icons.Outlined.NotificationsActive, Icons.Outlined.CheckCircle)
    Column(Modifier.fillMaxSize().background(PureWhite).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Text("Step ${step + 1} of ${titles.size}", color = SecondaryText) }
        Spacer(Modifier.weight(1f))
        Surface(color = SoftGreen, shape = RoundedCornerShape(30.dp)) { Icon(icons[step], null, tint = ForestGreen, modifier = Modifier.padding(30.dp).size(64.dp)) }
        Spacer(Modifier.height(26.dp))
        Text("HOW TO USE DOSELEAF", color = SecondaryText, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(10.dp))
        Text(titles[step], style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(descriptions[step], color = SecondaryText)
        if (step == 0 && requireName) {
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(value = initialName, onValueChange = onNameChange, label = { Text("Your name *") }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = initialName.isBlank())
            if (initialName.isBlank()) Text("Enter your name to create the default profile.", color = ErrorRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.Start))
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(titles.size) { i -> Box(Modifier.size(if (i == step) 22.dp else 7.dp, 7.dp).background(if (i == step) ForestGreen else BorderColor, RoundedCornerShape(20.dp))) }
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = {
            if (step == 0 && requireName && initialName.isBlank()) return@Button
            if (step < titles.lastIndex) step++ else onStart()
        }, modifier = Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = ForestGreen), shape = RoundedCornerShape(16.dp)) {
            Text(if (step < titles.lastIndex) "Continue" else "Get Started")
        }
    }
}
@Composable private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    val context = LocalContext.current
    Box(Modifier.fillMaxWidth().padding(top = 50.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = SoftGreen, shape = RoundedCornerShape(24.dp)) { Icon(icon, null, tint = ForestGreen, modifier = Modifier.padding(22.dp).size(38.dp)) }
            Text(tr(context, title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(tr(context, body), color = SecondaryText)
        }
    }
}
