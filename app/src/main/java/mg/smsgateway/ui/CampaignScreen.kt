package mg.smsgateway.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mg.smsgateway.Config
import mg.smsgateway.domain.PhoneColumnCandidate
import mg.smsgateway.domain.PhoneResult
import mg.smsgateway.domain.MessageTemplate
import mg.smsgateway.domain.SpreadsheetVariable
import mg.smsgateway.domain.SpreadsheetDocument
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class SelectedPhoneColumn(val index: Int, val candidate: PhoneColumnCandidate?)

@Composable
fun CampaignScreen(
    vm: MainViewModel,
    editJobId: String?,
    onBack: () -> Unit,
    onOpenJob: (String) -> Unit,
    onAskPermissions: () -> Unit,
) {
    val context = LocalContext.current
    val draft by vm.campaignDraft.collectAsStateWithLifecycle()
    val draftReady by vm.campaignDraftReady.collectAsStateWithLifecycle()
    val imported by vm.importedDocument.collectAsStateWithLifecycle()
    val candidates by vm.phoneColumns.collectAsStateWithLifecycle()
    val importError by vm.importError.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val draftSaveStatus by vm.draftSaveStatus.collectAsStateWithLifecycle()
    val draftRestoreError by vm.draftRestoreError.collectAsStateWithLifecycle()
    val sendState by vm.campaignSendState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val perms by vm.perms.collectAsStateWithLifecycle()
    val scheduledDraft by vm.scheduledCampaignDraft.collectAsStateWithLifecycle()

    val recipients = remember { mutableStateListOf<CampaignRecipient>() }
    var restored by remember { mutableStateOf(false) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf(TextFieldValue("")) }
    var manualText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var manualColumn by remember { mutableStateOf("") }
    var selectedColumn by remember { mutableStateOf<SelectedPhoneColumn?>(null) }
    var skipHeader by remember { mutableStateOf(true) }
    var headerLine by remember { mutableStateOf("") }
    var scheduledAt by remember { mutableStateOf<Long?>(null) }
    var scheduleError by remember { mutableStateOf<String?>(null) }
    var authorized by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var showClearDraftConfirm by remember { mutableStateOf(false) }
    var loadedEditId by remember { mutableStateOf<String?>(null) }
    var previewRecipientIndex by remember { mutableStateOf(0) }

    LaunchedEffect(editJobId) {
        if (editJobId != null) vm.loadScheduledCampaign(editJobId)
        else vm.resetCampaignSendState()
    }

    LaunchedEffect(draftReady, draft) {
        if (editJobId == null && draftReady && !restored) {
            sourceName = draft?.sourceName
            message = TextFieldValue(draft?.message.orEmpty())
            if (draft != null) recipients.addAll(vm.restoreDraftRecipients(draft?.recipientsJson.orEmpty()))
            restored = true
        }
    }
    LaunchedEffect(scheduledDraft, editJobId) {
        val existing = scheduledDraft
        if (editJobId != null && existing?.id == editJobId && loadedEditId != editJobId) {
            sourceName = "Campagne ${editJobId.take(8)}"
            message = TextFieldValue(existing.message)
            recipients.clear()
            recipients.addAll(existing.recipients)
            scheduledAt = existing.scheduledAt
            loadedEditId = editJobId
        }
    }
    LaunchedEffect(candidates, imported) {
        val confident = candidates.filter { it.confidence >= 70 }
        if (selectedColumn == null && imported != null && confident.size == 1) {
            selectedColumn = SelectedPhoneColumn(confident.first().index, confident.first())
            skipHeader = confident.first().headerRow != null
            headerLine = confident.first().headerRow?.toString().orEmpty()
        }
    }
    LaunchedEffect(sendState) {
        if (sendState is CampaignSendState.Accepted) vm.clearCampaignDraft()
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Fichier importé"
            sourceName = name
            vm.readSpreadsheet(uri, name)
        }
    }

    val effectiveHeaderLine = headerLine.toIntOrNull()
        ?: candidates.firstNotNullOfOrNull { it.headerRow }
    val selectedRecipients = recipients.filter { it.selected }
    val selectedCount = selectedRecipients.size
    val availableVariables = remember(imported, effectiveHeaderLine, recipients.size) {
        val variables = imported?.let { MessageTemplate.variables(it, effectiveHeaderLine) }.orEmpty()
            .associateBy { it.name }
            .toMutableMap()
        recipients.flatMap { it.variables.keys }.forEach { name ->
            variables.putIfAbsent(name.uppercase(Locale.ROOT), SpreadsheetVariable(name.uppercase(Locale.ROOT), -1, name))
        }
        variables.values.sortedWith(compareBy<SpreadsheetVariable> { it.columnIndex }.thenBy { it.name })
    }
    val availableVariableNames = availableVariables.map { it.name }.toSet()
    val messageText = message.text
    val usedVariables = MessageTemplate.tokens(messageText)
    val missingVariables = usedVariables.filter { name -> name !in availableVariableNames }
    val recipientsMissingVariables = selectedRecipients.count { recipient ->
        val names = recipient.variables.keys.map { it.uppercase(Locale.ROOT) }.toSet()
        usedVariables.any { it !in names }
    }
    val templateError = when {
        missingVariables.isNotEmpty() -> "Variable(s) inconnue(s) : ${missingVariables.joinToString { "#$it" }}. Choisissez une colonne proposée."
        recipientsMissingVariables > 0 -> "$recipientsMissingVariables destinataire(s) n'ont pas toutes les colonnes demandées. Importez la colonne ou retirez ces destinataires."
        else -> null
    }
    val segmentEstimate = selectedRecipients.maxOfOrNull { recipient ->
        estimateSegments(MessageTemplate.render(messageText, recipient.variables))
    } ?: estimateSegments(messageText)
    val duplicateCount = selectedRecipients
        .groupingBy { (vm.validatePhone(it.phone) as? PhoneResult.Valid)?.e164 ?: it.phone.trim() }
        .eachCount().values.sumOf { (it - 1).coerceAtLeast(0) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onBack) { Text("Retour") }
            Column {
                Text("SMS GATEWAY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    if (editJobId == null) "Nouvelle campagne" else "Modifier la campagne",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CampaignHero(
                editing = editJobId != null,
                sourceName = sourceName,
                selectedCount = selectedCount,
                segmentEstimate = segmentEstimate,
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CampaignSectionHeading("01", "Votre liste", "Importez un tableur ou ajoutez les numéros.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SummaryPill("$selectedCount sélectionné(s)")
                        if (duplicateCount > 0) SummaryPill("$duplicateCount doublon(s)", warning = true)
                    }
                    if (selectedCount > Config.MAX_RECIPIENTS_PER_JOB) {
                        Text(
                            "Limite de ${Config.MAX_RECIPIENTS_PER_JOB} destinataires par campagne; retirez des lignes avant de continuer.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!perms.requiredGranted || !perms.notifications) {
                        OutlinedButton(onClick = onAskPermissions, modifier = Modifier.fillMaxWidth()) {
                            Text("Gérer les autorisations SMS et notifications")
                        }
                        draftRestoreError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    if (sourceName != null) Text("Fichier : $sourceName", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        selectedColumn = null
                        filePicker.launch(arrayOf(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "text/csv",
                            "application/csv",
                            "*/*",
                        ))
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Importer un fichier .xlsx ou .csv")
                    }
                    Text(
                        "Les fichiers sont lus localement. Aucun SMS n'est envoyé pendant l'importation.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (importing) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text("Analyse du fichier…")
                        }
                    }
                    importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    imported?.let { document ->
                        SpreadsheetPreview(
                            document = document,
                            headerLine = effectiveHeaderLine,
                            selectedPhoneColumn = selectedColumn?.index,
                        )
                    }
                    if (imported != null) ColumnPicker(
                        document = imported!!,
                        candidates = candidates,
                        selected = selectedColumn,
                        skipHeader = skipHeader,
                        headerLine = headerLine,
                        manualColumn = manualColumn,
                        onSelect = {
                            selectedColumn = it
                            skipHeader = it.candidate?.headerRow != null
                            headerLine = it.candidate?.headerRow?.toString().orEmpty()
                        },
                        onSkipHeaderChange = {
                            skipHeader = it
                            if (!it) headerLine = ""
                        },
                        onHeaderLineChange = { headerLine = it },
                        onManualColumnChange = { manualColumn = it },
                        onAdd = { column, headerToSkip ->
                            val doc = imported ?: return@ColumnPicker
                            val columns = MessageTemplate.variables(doc, effectiveHeaderLine)
                            val headerToExclude = headerToSkip ?: effectiveHeaderLine
                            val values = doc.rows.mapNotNull { row ->
                                if (row.line == headerToExclude) return@mapNotNull null
                                val cell = row.cells.getOrNull(column.index) ?: return@mapNotNull null
                                cell.value.trim().takeIf(String::isNotEmpty)?.let { row to cell.copy(value = it) }
                            }
                            addRecipients(recipients, values.map { (row, cell) ->
                                CampaignRecipient(
                                    cell.value,
                                    sourceLine = row.line,
                                    numericExcelValue = cell.numeric,
                                    variables = MessageTemplate.valuesFor(row, columns),
                                )
                            })
                            sourceName = doc.name
                        },
                    )
                    OutlinedTextField(
                        value = manualText,
                        onValueChange = { manualText = it },
                        label = { Text("Ajouter des numéros manuellement") },
                        supportingText = { Text("Un numéro par ligne, ou séparés par des virgules / points-virgules.") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = {
                            val values = manualText.split(Regex("[,;\\r\\n]+"))
                                .map(String::trim).filter(String::isNotEmpty)
                            addRecipients(recipients, values.map { CampaignRecipient(it) })
                            manualText = ""
                        },
                        enabled = manualText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Ajouter la liste") }
                    if (recipients.isNotEmpty()) {
                        Text("${recipients.size} numéro(s) dans la liste", style = MaterialTheme.typography.labelLarge)
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("Rechercher un numéro") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (duplicateCount > 0) {
                            OutlinedButton(
                                onClick = {
                                    val seen = HashSet<String>()
                                    val duplicateIndexes = recipients.indices.filter { index ->
                                        val item = recipients[index]
                                        if (!item.selected) false
                                        else {
                                            val key = (vm.validatePhone(item.phone) as? PhoneResult.Valid)?.e164 ?: item.phone.trim()
                                            !seen.add(key)
                                        }
                                    }
                                    duplicateIndexes.asReversed().forEach(recipients::removeAt)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Supprimer les doublons ($duplicateCount)") }
                        }
                        LazyColumn(
                            Modifier.fillMaxWidth().heightIn(max = 280.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val matching = recipients.withIndex()
                                .filter { it.value.phone.contains(searchQuery, ignoreCase = true) }
                            itemsIndexed(matching, key = { _, item -> "${item.value.sourceLine ?: "m"}-${item.value.phone}-${item.index}" }) { _, indexed ->
                                val index = indexed.index
                                val recipient = indexed.value
                                RecipientEditor(
                                    recipient = recipient,
                                    validation = vm.validatePhone(recipient.phone),
                                    onChange = { recipients[index] = it },
                                    onRemove = { recipients.removeAt(index) },
                                )
                            }
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CampaignSectionHeading("02", "Écrivez votre message", "Chaque personne recevra un SMS adapté à sa ligne.")
                    MessageTemplateEditor(
                        value = message,
                        variables = availableVariables,
                        onValueChange = { message = it },
                    )
                    if (availableVariables.isNotEmpty()) {
                        Text("Tapez # pour insérer une variable de colonne, par exemple #NOM ou #A.")
                    }
                    templateError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text("${messageText.length} caractère(s) · jusqu'à $segmentEstimate segment(s) par destinataire")
                    if (messageText.any { it !in GSM_BASIC && it !in GSM_EXTENDED }) {
                        Text("Un caractère spécial utilise l'encodage Unicode et réduit la capacité par segment.")
                    }
                    Text("Envoi unique : aucun rappel ne déclenche un deuxième SMS.")
                    selectedRecipients.firstOrNull()?.let {
                        MessagePreviewCard(
                            recipients = selectedRecipients,
                            activeIndex = previewRecipientIndex,
                            onSelectRecipient = { previewRecipientIndex = it },
                            message = messageText,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { scheduledAt = System.currentTimeMillis() + 5 * 60_000L; scheduleError = null }) { Text("5 min") }
                        OutlinedButton(onClick = { scheduledAt = System.currentTimeMillis() + 10 * 60_000L; scheduleError = null }) { Text("10 min") }
                        OutlinedButton(onClick = { scheduledAt = System.currentTimeMillis() + 30 * 60_000L; scheduleError = null }) { Text("30 min") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { scheduledAt = System.currentTimeMillis() + 60 * 60_000L; scheduleError = null }) { Text("1 heure") }
                        OutlinedButton(onClick = { scheduledAt = System.currentTimeMillis() + 5 * 60 * 60_000L; scheduleError = null }) { Text("5 heures") }
                        OutlinedButton(
                            onClick = {
                                showDateTimePicker(context, scheduledAt ?: System.currentTimeMillis() + 60 * 60_000L) {
                                    scheduledAt = it
                                    scheduleError = null
                                }
                            },
                        ) { Text("Date et heure") }
                    }
                    scheduledAt?.let {
                        Text("Programmation sélectionnée : ${formatScheduleTime(it)}")
                        if (!perms.notifications) {
                            Text(
                                "Les notifications sont désactivées : aucun rappel ne sera affiché. L'envoi reste programmé localement.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    scheduleError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CampaignSectionHeading("03", "Prêt à partir ?", "Vérifiez la SIM et confirmez votre autorisation.")
                    Text("SIM sélectionnée : ${settings?.selectedSubscriptionId?.takeIf { it >= 0 } ?: "aucune"}")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = authorized, onCheckedChange = { authorized = it })
                        Text("Je suis autorisé(e) à contacter ces destinataires et je respecterai leurs demandes de désinscription.")
                    }
                    OutlinedButton(
                        onClick = {
                            vm.saveCampaignDraft(sourceName, messageText, recipients.toList())
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Enregistrer le brouillon") }
                    if (editJobId == null && draft != null) {
                        androidx.compose.material3.TextButton(onClick = { showClearDraftConfirm = true }) {
                            Text("Supprimer le brouillon local")
                        }
                    }
                    draftSaveStatus?.let { status ->
                        Text(
                            status,
                            color = if (status.startsWith("Échec")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                    when (val result = sendState) {
                        CampaignSendState.Idle, CampaignSendState.Sending -> Unit
                        is CampaignSendState.Rejected -> {
                            Text(result.reason, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = vm::resetCampaignSendState) { Text("Modifier la campagne") }
                        }
                        is CampaignSendState.Accepted -> {
                            Text(
                                if (result.scheduled) "Campagne programmée. L'envoi est unique; le rappel ne le relance pas."
                                else "Campagne acceptée par la file locale. Le statut par destinataire est disponible dans l'historique."
                            )
                            Button(onClick = { onOpenJob(result.jobId) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Suivre l'envoi")
                            }
                        }
                    }
                }
            }
        }
        val canSubmit = selectedCount in 1..Config.MAX_RECIPIENTS_PER_JOB &&
            messageText.isNotBlank() && templateError == null &&
            selectedRecipients.all { vm.validatePhone(it.phone) is PhoneResult.Valid } &&
            sendState !is CampaignSendState.Sending && sendState !is CampaignSendState.Accepted
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("$selectedCount destinataire(s)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("Jusqu'à $segmentEstimate segment(s) par SMS", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (sendState is CampaignSendState.Sending) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (editJobId == null) {
                        Button(
                            onClick = { scheduledAt = null; showConfirm = true },
                            enabled = canSubmit,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        ) { Text("Envoyer") }
                    }
                    Button(
                        onClick = {
                            val time = scheduledAt
                            if (time == null) scheduleError = "Choisissez d'abord une date dans la section message."
                            else if (time <= System.currentTimeMillis()) scheduleError = "La date doit être dans le futur."
                            else showConfirm = true
                        },
                        enabled = canSubmit,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        colors = if (editJobId == null) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        } else ButtonDefaults.buttonColors(),
                    ) {
                        Text(if (editJobId == null) "Programmer" else "Enregistrer")
                    }
                }
                scheduleError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = {
                Text(
                    when {
                        editJobId != null -> "Confirmer les modifications"
                        scheduledAt == null -> "Confirmer l'envoi"
                        else -> "Confirmer la programmation"
                    }
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (editJobId != null) "Modification d'un envoi programmé · $selectedCount destinataire(s)"
                        else if (scheduledAt == null) "Mode : envoi immédiat · $selectedCount destinataire(s)"
                        else "Mode : envoi unique programmé · $selectedCount destinataire(s)"
                    )
                    if (scheduledAt != null) {
                        Text("Prévu : ${formatScheduleTime(scheduledAt!!)}")
                        Text("Temps restant : ${remainingTime(scheduledAt!!)}")
                    }
                    Text("Environ $segmentEstimate segment(s) par SMS")
                    Text("Message exact :")
                    val previewRecipient = selectedRecipients.firstOrNull()
                    if (previewRecipient != null && usedVariables.isNotEmpty()) {
                        Text("Aperçu pour ${previewRecipient.phone} :")
                        Text(MessageTemplate.render(messageText, previewRecipient.variables), style = MaterialTheme.typography.bodyMedium)
                        Text("Les variables seront remplacées séparément pour chaque destinataire.")
                    } else {
                        Text(messageText, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("Aperçu des destinataires :")
                    recipients.filter { it.selected }.take(5).forEach { Text("• ${it.phone}") }
                    if (selectedCount > 5) Text("et ${selectedCount - 5} autre(s)…")
                    if (!authorized) Text(
                        "Confirmez que vous êtes autorisé(e) à contacter cette liste.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirm = false
                        val selected = recipients.filter { it.selected }
                        val schedule = scheduledAt
                        when {
                            editJobId != null && schedule != null ->
                                vm.updateScheduledCampaign(editJobId, messageText, selected, schedule)
                            schedule == null -> vm.sendCampaign(messageText, selected)
                            else -> vm.scheduleCampaign(messageText, selected, schedule)
                        }
                    },
                    enabled = authorized && sendState !is CampaignSendState.Sending &&
                    templateError == null &&
                        (scheduledAt == null || scheduledAt!! > System.currentTimeMillis()) &&
                        (editJobId == null || scheduledAt != null),
                ) {
                    Text(
                        when {
                            editJobId != null -> "Confirmer et enregistrer"
                            scheduledAt == null -> "Confirmer et envoyer"
                            else -> "Confirmer la programmation"
                        }
                    )
                }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Modifier") } },
        )
    }
    if (showClearDraftConfirm) {
        AlertDialog(
            onDismissRequest = { showClearDraftConfirm = false },
            title = { Text("Supprimer le brouillon ?") },
            text = { Text("Le message et les destinataires enregistrés seront effacés de cet appareil.") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDraftConfirm = false
                        recipients.clear()
                        message = TextFieldValue("")
                        sourceName = null
                        scheduledAt = null
                        vm.clearCampaignDraft()
                    },
                ) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDraftConfirm = false }) { Text("Garder") }
            },
        )
    }
}

@Composable
private fun CampaignHero(
    editing: Boolean,
    sourceName: String?,
    selectedCount: Int,
    segmentEstimate: Int,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = scheme.primary,
    ) {
        Box(
            Modifier
                .background(Brush.linearGradient(listOf(scheme.primary, scheme.secondary)))
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (editing) "CAMPAGNE PROGRAMMÉE" else "MESSAGES PERSONNALISÉS",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onPrimary.copy(alpha = 0.78f),
                )
                Text(
                    if (editing) "Ajustez votre envoi." else "Un message. Mille attentions.",
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Importez vos contacts, composez votre SMS et vérifiez chaque aperçu avant l'envoi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onPrimary.copy(alpha = 0.88f),
                )
                HorizontalDivider(color = scheme.onPrimary.copy(alpha = 0.22f))
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    HeroMetric(selectedCount.toString(), "destinataires", Modifier.weight(1f))
                    HeroMetric(
                        if (sourceName == null) "—" else "OK",
                        if (sourceName == null) "fichier à choisir" else sourceName.substringAfterLast('.', "fichier"),
                        Modifier.weight(1.2f),
                    )
                    HeroMetric("~$segmentEstimate", "segment(s) / SMS", Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HeroMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(end = 8.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CampaignSectionHeading(number: String, title: String, subtitle: String) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(number, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SummaryPill(text: String, warning: Boolean = false) {
    Surface(
        color = if (warning) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (warning) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun SpreadsheetPreview(
    document: SpreadsheetDocument,
    headerLine: Int?,
    selectedPhoneColumn: Int?,
) {
    val width = (document.rows.maxOfOrNull { it.cells.size } ?: 0).coerceAtMost(12)
    val rows = document.rows.take(7)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Aperçu du tableur", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("${document.rows.size} lignes · ${document.rows.maxOfOrNull { it.cells.size } ?: 0} colonnes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SummaryPill("EXCEL")
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 224.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("LIGNE", Modifier.width(48.dp).padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (index in 0 until width) {
                    val heading = document.rows.firstOrNull { it.line == headerLine }?.cells?.getOrNull(index)?.value
                        ?.takeIf(String::isNotBlank) ?: "Colonne ${columnName(index)}"
                    val selected = selectedPhoneColumn == index
                    Surface(
                        Modifier.width(116.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Column(Modifier.padding(horizontal = 9.dp, vertical = 7.dp)) {
                            Text(columnName(index), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text(heading, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.line.toString(), Modifier.width(48.dp).padding(vertical = 9.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (index in 0 until width) {
                        val isHeader = row.line == headerLine
                        val isPhone = selectedPhoneColumn == index
                        Surface(
                            Modifier.width(116.dp),
                            color = when {
                                isHeader -> MaterialTheme.colorScheme.surfaceVariant
                                isPhone -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surface
                            },
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                row.cells.getOrNull(index)?.value?.ifBlank { "—" } ?: "—",
                                Modifier.padding(horizontal = 9.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isPhone) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        Text("Les cellules colorées indiquent la colonne de numéros sélectionnée.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MessagePreviewCard(
    recipients: List<CampaignRecipient>,
    activeIndex: Int,
    onSelectRecipient: (Int) -> Unit,
    message: String,
) {
    val previewRecipient = recipients.getOrNull(activeIndex) ?: recipients.firstOrNull() ?: return
    val previewName = listOf("PRENOM", "FIRST_NAME", "NOM", "NAME")
        .firstNotNullOfOrNull { previewRecipient.variables[it]?.takeIf(String::isNotBlank) }
        ?: previewRecipient.phone
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Votre SMS, en vrai", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Changez de destinataire pour vérifier les variables.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${activeIndex.coerceIn(0, recipients.lastIndex) + 1}/${recipients.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            recipients.take(12).forEachIndexed { index, recipient ->
                val name = listOf("PRENOM", "FIRST_NAME", "NOM", "NAME")
                    .firstNotNullOfOrNull { recipient.variables[it]?.takeIf(String::isNotBlank) }
                    ?: recipient.phone
                AssistChip(
                    onClick = { onSelectRecipient(index) },
                    label = { Text(name, maxLines = 1) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (index == activeIndex) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        labelColor = if (index == activeIndex) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
        }
        Text(previewRecipient.phone, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                Modifier.fillMaxWidth(0.94f),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp),
            ) {
                Text(
                    MessageTemplate.render(message, previewRecipient.variables).ifBlank { "Votre message apparaîtra ici…" },
                    Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Text(
            "SMS · environ ${estimateSegments(MessageTemplate.render(message, previewRecipient.variables))} segment(s)",
            Modifier.align(Alignment.End),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageTemplateEditor(
    value: TextFieldValue,
    variables: List<SpreadsheetVariable>,
    onValueChange: (TextFieldValue) -> Unit,
) {
    val cursor = value.selection.end.coerceIn(0, value.text.length)
    val prefix = value.text.substring(0, cursor)
    val hashIndex = prefix.lastIndexOf('#')
    val query = if (hashIndex >= 0) prefix.substring(hashIndex + 1) else ""
    val suggestionsActive = hashIndex >= 0 && query.all { it.isLetterOrDigit() || it == '_' }
    val suggestions = if (suggestionsActive) {
        variables.filter { it.name.startsWith(query.uppercase(Locale.ROOT)) }.take(8)
    } else {
        emptyList()
    }
    val knownNames = remember(variables) { variables.map { it.name }.toSet() }
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val tokenHighlight = remember(knownNames, primary, error) {
        VisualTransformation { text ->
            val annotated = AnnotatedString.Builder(text.text)
            Regex("#([A-Za-z0-9_]+)").findAll(text.text).forEach { match ->
                val color = if (match.groupValues[1].uppercase(Locale.ROOT) in knownNames) primary else error
                annotated.addStyle(
                    SpanStyle(color = color, fontWeight = FontWeight.Bold),
                    match.range.first,
                    match.range.last + 1,
                )
            }
            TransformedText(annotated.toAnnotatedString(), OffsetMapping.Identity)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Message SMS") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            visualTransformation = tokenHighlight,
        )
        if (suggestionsActive) {
            if (suggestions.isEmpty()) {
                Text("Aucune variable de colonne ne correspond à #$query.", color = MaterialTheme.colorScheme.error)
            } else {
                Text("Variables disponibles", style = MaterialTheme.typography.labelMedium)
                suggestions.forEach { variable ->
                    TextButton(
                        onClick = {
                            val cursorPosition = value.selection.end.coerceIn(0, value.text.length)
                            val token = "#${variable.name}"
                            val tokenEnd = value.text.drop(cursorPosition).takeWhile { it.isLetterOrDigit() || it == '_' }
                                .length + cursorPosition
                            val suffix = value.text.substring(tokenEnd)
                            val separator = if (suffix.firstOrNull()?.isWhitespace() == true) "" else " "
                            val replacement = value.text.substring(0, hashIndex) + token + separator + suffix
                            onValueChange(TextFieldValue(replacement, TextRange(hashIndex + token.length + separator.length)))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val column = variable.columnIndex.takeIf { it >= 0 }?.let(::columnName)
                        Text(
                            if (column == null) "#${variable.name} · ${variable.label}"
                            else "#${variable.name} · colonne $column (${variable.label})"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnPicker(
    document: SpreadsheetDocument,
    candidates: List<PhoneColumnCandidate>,
    selected: SelectedPhoneColumn?,
    skipHeader: Boolean,
    headerLine: String,
    manualColumn: String,
    onSelect: (SelectedPhoneColumn) -> Unit,
    onSkipHeaderChange: (Boolean) -> Unit,
    onHeaderLineChange: (String) -> Unit,
    onManualColumnChange: (String) -> Unit,
    onAdd: (SelectedPhoneColumn, Int?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${document.rows.size} ligne(s) analysée(s)", style = MaterialTheme.typography.bodySmall)
        selected?.let { column ->
            val excludedHeader = headerLine.toIntOrNull()?.takeIf { skipHeader }
            val emptyCells = document.rows.count { row ->
                row.line != excludedHeader && row.cells.getOrNull(column.index)?.value.isNullOrBlank()
            }
            Text("Colonne ${columnName(column.index)} · $emptyCells cellule(s) vide(s)")
        }
        if (candidates.isNotEmpty()) {
            Text("Colonnes candidates — choisissez celle qui contient les téléphones.")
            candidates.forEach { candidate ->
                val checked = selected?.index == candidate.index
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(SelectedPhoneColumn(candidate.index, candidate)) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = checked, onClick = { onSelect(SelectedPhoneColumn(candidate.index, candidate)) })
                    Column {
                        Text("${columnName(candidate.index)} · ${candidate.header ?: "sans en-tête"}")
                        Text(
                            "${candidate.nonEmpty} valeurs · ${candidate.valid} valides · ${candidate.invalid} invalides · confiance ${candidate.confidence}%",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        } else {
            Text("Aucune colonne assez fiable. Choisissez manuellement une colonne (A, B… ou numéro).")
        }
        OutlinedTextField(
            value = manualColumn,
            onValueChange = onManualColumnChange,
            label = { Text("Colonne manuelle (ex. A ou 1)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val manualIndex = columnIndexFromName(manualColumn)
        if (manualIndex != null) {
            OutlinedButton(
                onClick = { onSelect(SelectedPhoneColumn(manualIndex, null)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Utiliser la colonne ${columnName(manualIndex)}") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = skipHeader, onCheckedChange = onSkipHeaderChange)
            Text("Ignorer la ligne d'en-tête")
        }
        OutlinedTextField(
            value = headerLine,
            onValueChange = { onHeaderLineChange(it.filter(Char::isDigit)) },
            label = { Text("Ligne d'en-tête (vide si absente)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = skipHeader,
        )
        OutlinedButton(
            onClick = {
                selected?.let {
                    onAdd(it, headerLine.toIntOrNull())
                }
            },
            enabled = selected != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Ajouter les valeurs de cette colonne") }
    }
}

@Composable
private fun RecipientEditor(
    recipient: CampaignRecipient,
    validation: PhoneResult,
    onChange: (CampaignRecipient) -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = recipient.selected, onCheckedChange = { onChange(recipient.copy(selected = it)) })
            OutlinedTextField(
                value = recipient.phone,
                onValueChange = { onChange(recipient.copy(phone = it)) },
                label = { Text(if (recipient.sourceLine == null) "Numéro" else "Ligne ${recipient.sourceLine}") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.weight(1f),
                isError = validation is PhoneResult.Invalid,
            )
            TextButton(onClick = onRemove) { Text("Retirer") }
        }
        if (validation is PhoneResult.Invalid) {
            Text(validation.reason, Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (recipient.numericExcelValue) {
            Text(
                "Valeur Excel numérique : vérifiez l'indicatif et les zéros initiaux, qui ont pu être perdus.",
                Modifier.padding(start = 52.dp),
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun addRecipients(target: MutableList<CampaignRecipient>, values: List<CampaignRecipient>) {
    target.addAll(values)
}

private fun columnIndexFromName(value: String): Int? {
    val normalized = value.trim().uppercase()
    if (normalized.isEmpty()) return null
    normalized.toIntOrNull()?.let { return (it - 1).takeIf { index -> index >= 0 } }
    if (!normalized.all { it in 'A'..'Z' }) return null
    var result = 0
    normalized.forEach { result = result * 26 + it.code - 'A'.code + 1 }
    return result - 1
}

private fun columnName(index: Int): String {
    var value = index + 1
    val result = StringBuilder()
    while (value > 0) {
        val remainder = (value - 1) % 26
        result.insert(0, ('A'.code + remainder).toChar())
        value = (value - 1) / 26
    }
    return result.toString()
}

private fun estimateSegments(message: String): Int {
    if (message.isEmpty()) return 0
    var gsm7Length = 0
    message.forEach { gsm7Length += if (it in GSM_EXTENDED) 2 else 1 }
    val unicode = message.any { it !in GSM_BASIC && it !in GSM_EXTENDED }
    val limit = if (unicode) 70 else 160
    val multipartLimit = if (unicode) 67 else 153
    return when {
        gsm7Length <= limit && !unicode -> 1
        message.length <= 70 && unicode -> 1
        unicode -> (message.length + multipartLimit - 1) / multipartLimit
        else -> (gsm7Length + multipartLimit - 1) / multipartLimit
    }
}

private fun showDateTimePicker(context: android.content.Context, initial: Long, onPicked: (Long) -> Unit) {
    val calendar = Calendar.getInstance().apply { timeInMillis = initial }
    DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val selected = Calendar.getInstance().apply {
                        set(year, month, day, hour, minute, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onPicked(selected.timeInMillis)
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                true,
            ).show()
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH),
    ).apply {
        datePicker.minDate = System.currentTimeMillis()
    }.show()
}

private fun formatScheduleTime(timestamp: Long): String =
    SimpleDateFormat("EEE d MMM yyyy, HH:mm z", Locale.getDefault()).format(Date(timestamp))

private fun remainingTime(timestamp: Long): String {
    val minutes = ((timestamp - System.currentTimeMillis()).coerceAtLeast(0) / 60_000L)
    return "${minutes / 60} h ${minutes % 60} min"
}

private const val GSM_BASIC =
    "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
private const val GSM_EXTENDED = "^{}\\[~]|€"
