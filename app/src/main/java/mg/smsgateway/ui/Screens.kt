package mg.smsgateway.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import mg.smsgateway.Config
import mg.smsgateway.data.JobStatus
import mg.smsgateway.service.GatewayState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fmt(ts: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(ts))
private fun yesNo(b: Boolean) = if (b) "oui" else "non"

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onBack) { Text("‹ Retour") }
        Spacer(Modifier.padding(horizontal = 8.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

// ============================================================ ÉCRAN PRINCIPAL

@Composable
fun MainScreen(
    vm: MainViewModel,
    onCampaign: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onAskPermissions: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val reserved by vm.reserved.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val perms by vm.perms.collectAsStateWithLifecycle()
    val usb by vm.usb.collectAsStateWithLifecycle()
    val running by GatewayState.running.collectAsStateWithLifecycle()
    val serverError by GatewayState.serverError.collectAsStateWithLifecycle()

    val s = settings
    if (s == null) {
        Text("Chargement…", Modifier.padding(16.dp))
        return
    }
    val remaining = maxOf(0, s.smsLimit - s.smsUsed - reserved)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    "SMS",
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Column {
                Text("SMS GATEWAY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text("Votre espace", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("VOTRE TÉLÉPHONE, VOTRE SIM", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Vos messages.\nEn toute simplicité.",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
                Text(
                    "Créez une campagne personnalisée ou pilotez vos envois depuis votre PC.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
                Button(onClick = onCampaign, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Créer une campagne  ›")
                }
            }
        }

        SectionCard("Connexion USB") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(colors = CardDefaults.cardColors(
                    containerColor = if (usb.pcActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                )) {
                    Text(
                        if (usb.pcActive) "PC CONNECTÉ" else "EN ATTENTE",
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = if (usb.pcActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Text("Câble USB branché : ${yesNo(usb.cablePlugged)}  •  Débogage USB : ${yesNo(usb.adbEnabled)}")
            Text("Serveur local : " + if (running) "actif sur 127.0.0.1:${Config.PORT}" else "arrêté")
            serverError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = { if (running) vm.stopGateway() else vm.startGateway() }) {
                Text(if (running) "Arrêter la passerelle" else "Démarrer la passerelle")
            }
        }

        if (!perms.requiredGranted || !perms.notifications) {
            SectionCard("Permissions requises") {
                Text("• SMS (SEND_SMS) : envoyer les messages avec votre SIM.")
                Text("• Téléphone (READ_PHONE_STATE) : détecter les SIM, leur état et le réseau.")
                Text("• Numéros de téléphone (READ_PHONE_NUMBERS) : afficher le numéro de la SIM si elle le fournit (facultatif).")
                Text("• Notifications : afficher la notification obligatoire du service actif (Android 13+).")
                Text(
                    "Si le système n'affiche plus la fenêtre d'autorisation, ouvrez les paramètres de l'application. " +
                        "Sur Android 13+, une APK installée hors Play Store peut exiger : ⋮ → « Autoriser les paramètres restreints ».",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAskPermissions) { Text("Autoriser") }
                    OutlinedButton(onClick = onOpenAppInfo) { Text("Paramètres de l'app") }
                }
            }
        }

        SectionCard("SIM sélectionnée") {
            if (sims.isEmpty()) {
                Text(
                    if (perms.phoneState) "Aucune SIM active détectée." else "Autorisez la permission « Téléphone » pour détecter les SIM.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val locked = reserved > 0
            sims.forEach { sim ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = sim.available && !locked) { vm.selectSim(sim.subscriptionId) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = s.selectedSubscriptionId == sim.subscriptionId,
                        onClick = { vm.selectSim(sim.subscriptionId) },
                        enabled = sim.available && !locked,
                    )
                    Column {
                        Text("${sim.displayName} — ${sim.carrierName}", style = MaterialTheme.typography.titleSmall)
                        Text("Numéro : ${sim.number ?: "non disponible"}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "État : ${if (sim.available) "disponible" else sim.state}" +
                                when (sim.networkInService) { true -> " • réseau OK"; false -> " • hors réseau"; null -> "" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("subscriptionId : ${sim.subscriptionId}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (reserved > 0) Text("Changement de SIM verrouillé : un envoi est en cours.", style = MaterialTheme.typography.bodySmall)
            if (s.selectedSubscriptionId < 0) {
                Text("Aucune SIM choisie : aucun envoi ne sera accepté.", color = MaterialTheme.colorScheme.error)
            }
        }

        SectionCard("Forfait local (compteur de sécurité)") {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$remaining", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text(" segments disponibles", Modifier.padding(start = 6.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("${s.smsUsed} / ${s.smsLimit}", style = MaterialTheme.typography.labelLarge)
            }
            LinearProgressIndicator(
                progress = {
                    if (s.smsLimit <= 0) 0f else (s.smsUsed + reserved).toFloat().div(s.smsLimit).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
            Text("Utilisés : ${s.smsUsed} · Réservés : $reserved · Plafond : ${s.smsLimit}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Ce compteur est local à l'application : elle ne connaît pas le solde réel de l'opérateur.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (s.smsLimit == 0) Text("Définissez votre plafond dans Paramètres.", color = MaterialTheme.colorScheme.error)
        }

        SectionCard("Envoi depuis le PC") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = s.sendingAllowed, onCheckedChange = { vm.setSendingAllowed(it) })
                Spacer(Modifier.padding(horizontal = 6.dp))
                Text(if (s.sendingAllowed) "Envoi autorisé" else "Envoi bloqué")
            }
            Text(
                "Désactivé : l'API USB refuse les nouveaux envois. Les campagnes confirmées dans l'application restent indépendantes.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onHistory, modifier = Modifier.weight(1f).height(50.dp)) { Text("Historique") }
            OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f).height(50.dp)) { Text("Paramètres") }
        }
    }
}

// ============================================================ HISTORIQUE

@Composable
fun HistoryScreen(vm: MainViewModel, onBack: () -> Unit, onOpenJob: (String) -> Unit) {
    val jobs by vm.summaries.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Jobs", onBack)
        if (jobs.isEmpty()) Text("Aucun envoi pour le moment.", Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(jobs, key = { it.id }) { j ->
                Card(Modifier.fillMaxWidth().clickable { onOpenJob(j.id) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(fmt(j.createdAt), style = MaterialTheme.typography.titleSmall)
                        Text("${j.totalRecipients} destinataires")
                        j.scheduledAt?.let { Text("Prévu : ${fmt(it)}") }
                        Text(
                            buildString {
                                append("${j.sent} envoyés")
                                if (j.failed > 0) append(" • ${j.failed} échecs")
                                if (j.cancelled > 0) append(" • ${j.cancelled} annulés")
                            }
                        )
                        Text(j.status, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// ============================================================ JOB

@Composable
fun JobScreen(vm: MainViewModel, jobId: String, onBack: () -> Unit, onEditCampaign: () -> Unit) {
    val flow = remember(jobId) { vm.observeJob(jobId) }
    val recipientsFlow = remember(jobId) { vm.observeRecipients(jobId) }
    val p by flow.collectAsStateWithLifecycle(initialValue = null)
    val recipients by recipientsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val deleteError by vm.deleteJobError.collectAsStateWithLifecycle()
    var showCancelConfirm by remember(jobId) { mutableStateOf(false) }
    var showDeleteConfirm by remember(jobId) { mutableStateOf(false) }
    LaunchedEffect(jobId) { vm.clearDeleteJobError() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Envoi #${jobId.take(8)}", onBack)
        val prog = p
        if (prog == null) {
            Text("Chargement…", Modifier.padding(16.dp))
            return
        }
        val now by produceState(System.currentTimeMillis(), prog.status, prog.scheduledAt) {
            while (prog.status == JobStatus.SCHEDULED) {
                value = System.currentTimeMillis()
                delay(1_000)
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when (prog.status) {
                    JobStatus.SCHEDULED -> "Envoi programmé"
                    JobStatus.RUNNING -> "Envoi en cours"
                    JobStatus.QUEUED -> "En file d'attente"
                    JobStatus.COMPLETED -> "Terminé"
                    JobStatus.COMPLETED_WITH_ERRORS -> "Terminé avec des échecs"
                    JobStatus.CANCELLED -> "Annulé"
                    else -> prog.status
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (prog.message.isNotBlank()) {
                Text("Message : ${prog.message}", style = MaterialTheme.typography.bodyMedium)
            }
            prog.scheduledAt?.let { Text("Envoi prévu le ${fmt(it)}") }
            if (prog.status == JobStatus.SCHEDULED && prog.scheduledAt != null) {
                Text("Temps restant : ${remainingTime(prog.scheduledAt, now)}", style = MaterialTheme.typography.titleSmall)
            }
            if (prog.status == JobStatus.SCHEDULED) {
                OutlinedButton(onClick = onEditCampaign) { Text("Modifier la campagne") }
            }
            Text("Total       : ${prog.total}", fontFamily = FontFamily.Monospace)
            Text("En attente  : ${prog.pending}", fontFamily = FontFamily.Monospace)
            Text("En cours    : ${prog.sending}", fontFamily = FontFamily.Monospace)
            Text("Réussis     : ${prog.sent}", fontFamily = FontFamily.Monospace)
            Text("Échecs      : ${prog.failed}", fontFamily = FontFamily.Monospace)
            Text("Annulés     : ${prog.cancelled}", fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { prog.percent / 100f }, modifier = Modifier.fillMaxWidth())
            Text("${prog.percent} %")
            if (prog.finishedAt == null) {
                Button(onClick = { showCancelConfirm = true }) {
                    Text(if (prog.status == JobStatus.SCHEDULED) "Annuler la campagne" else "Arrêter les SMS restants")
                }
                if (prog.status != JobStatus.SCHEDULED) {
                    Text("Les SMS déjà transmis au système téléphonique ne peuvent pas toujours être annulés.")
                }
            }
            deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (prog.finishedAt != null) {
                androidx.compose.material3.TextButton(onClick = { showDeleteConfirm = true }) {
                    Text("Supprimer cette campagne et ses destinataires")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(recipients, key = { it.id }) { r ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(r.phoneNumber, fontFamily = FontFamily.Monospace)
                        Text(r.status, fontFamily = FontFamily.Monospace)
                    }
                    r.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (r.deliveredSegments > 0) Text("Livré (accusé de réception)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (showCancelConfirm) {
            val scheduled = p?.status == JobStatus.SCHEDULED
            AlertDialog(
                onDismissRequest = { showCancelConfirm = false },
                title = { Text(if (scheduled) "Annuler cette campagne ?" else "Arrêter les SMS restants ?") },
                text = {
                    Text(
                        if (scheduled) "Aucun SMS ne sera envoyé. Les alarmes et rappels seront annulés."
                        else "Les messages déjà transmis au système téléphonique ne peuvent pas être rappelés."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showCancelConfirm = false
                            vm.cancelJob(jobId)
                        },
                    ) { Text(if (scheduled) "Annuler la campagne" else "Arrêter les envois") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showCancelConfirm = false }) { Text("Continuer") }
                },
            )
        }
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Supprimer l'historique ?") },
                text = { Text("La campagne et sa liste de destinataires seront supprimées définitivement de cet appareil.") },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteConfirm = false
                            vm.deleteFinishedJob(jobId, onBack)
                        },
                    ) { Text("Supprimer") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showDeleteConfirm = false }) { Text("Garder") }
                },
            )
        }
    }
}

private fun remainingTime(scheduledAt: Long, now: Long): String {
    val remaining = (scheduledAt - now).coerceAtLeast(0) / 60_000L
    return "${remaining / 60} h ${remaining % 60} min"
}

// ============================================================ PARAMÈTRES

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onLogs: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings ?: return
    val clipboard = LocalClipboardManager.current

    var limitText by remember(s.smsLimit) { mutableStateOf(s.smsLimit.toString()) }
    var usedText by remember(s.smsUsed) { mutableStateOf(s.smsUsed.toString()) }
    var regionText by remember(s.defaultRegion) { mutableStateOf(s.defaultRegion) }
    var rateText by remember(s.maxPerMinute) { mutableStateOf(s.maxPerMinute.toString()) }
    var showToken by remember { mutableStateOf(false) }
    val numeric = KeyboardOptions(keyboardType = KeyboardType.Number)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Paramètres", onBack)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 0.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            SectionCard("Forfait local") {
                OutlinedTextField(
                    value = limitText, onValueChange = { limitText = it.filter(Char::isDigit) },
                    label = { Text("SMS disponibles (plafond)") }, keyboardOptions = numeric, singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { limitText.toIntOrNull()?.let { vm.setLimit(it) } }) { Text("Enregistrer") }
                    OutlinedButton(onClick = { vm.newPeriod(limitText.toIntOrNull()) }) { Text("Nouvelle période (utilisés → 0)") }
                }
                OutlinedTextField(
                    value = usedText, onValueChange = { usedText = it.filter(Char::isDigit) },
                    label = { Text("Corriger le compteur « utilisés »") }, keyboardOptions = numeric, singleLine = true,
                )
                OutlinedButton(onClick = { usedText.toIntOrNull()?.let { vm.setUsed(it) } }) { Text("Corriger") }
                Text("Début de la période : ${fmt(s.periodStartedAt)}", style = MaterialTheme.typography.bodySmall)
            }

            SectionCard("Numéros") {
                OutlinedTextField(
                    value = regionText, onValueChange = { regionText = it.take(2) },
                    label = { Text("Pays par défaut (ex. MG) pour 034… → +26134…") }, singleLine = true,
                )
                OutlinedButton(onClick = { vm.setRegion(regionText) }) { Text("Enregistrer") }
            }

            SectionCard("Cadence d'envoi") {
                OutlinedTextField(
                    value = rateText, onValueChange = { rateText = it.filter(Char::isDigit) },
                    label = { Text("Segments max par minute") }, keyboardOptions = numeric, singleLine = true,
                )
                Text(
                    "Android affiche une confirmation anti-spam au-delà d'environ 30 SMS/minute par application. " +
                        "La valeur par défaut (20) reste en dessous.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { rateText.toIntOrNull()?.let { vm.setMaxPerMinute(it) } }) { Text("Enregistrer") }
            }

            SectionCard("Appairage PC (jeton secret)") {
                Text(
                    if (showToken) s.pairingToken else "••••••••••••••••••••••••••••••••",
                    fontFamily = FontFamily.Monospace,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Masquer" else "Afficher") }
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(s.pairingToken)) }) { Text("Copier") }
                    OutlinedButton(onClick = { vm.regenerateToken() }) { Text("Régénérer") }
                }
                Text(
                    "Le PC doit envoyer ce jeton (Authorization: Bearer …). Régénérer invalide l'ancien jeton.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(onClick = onLogs) { Text("Voir les logs") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ============================================================ LOGS

@Composable
fun LogsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Logs", onBack)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(logs, key = { it.id }) { l ->
                Text(
                    "${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(l.timestamp))} ${l.level} [${l.tag}] ${l.message}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (l.level == "ERROR") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}
