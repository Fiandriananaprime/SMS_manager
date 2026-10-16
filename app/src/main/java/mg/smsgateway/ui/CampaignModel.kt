package mg.smsgateway.ui

data class CampaignRecipient(
    val phone: String,
    val selected: Boolean = true,
    val sourceLine: Int? = null,
    val numericExcelValue: Boolean = false,
    val variables: Map<String, String> = emptyMap(),
)

data class ScheduledCampaignDraft(
    val id: String,
    val scheduledAt: Long,
    val message: String,
    val recipients: List<CampaignRecipient>,
)

sealed interface CampaignSendState {
    data object Idle : CampaignSendState
    data object Sending : CampaignSendState
    data class Accepted(val jobId: String, val scheduled: Boolean = false) : CampaignSendState
    data class Rejected(val reason: String) : CampaignSendState
}
