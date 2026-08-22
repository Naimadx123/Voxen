package zone.vao.voxen.config

data class ReportsConfig(
    val enabled: Boolean,
    val dialogs: Boolean,
    val web: Boolean,
    val chatButton: Boolean,
    val chatButtonStaffOnly: Boolean,
    val messageKeep: Int,
    val context: Int,
    val muteDurations: List<String>,
    val auditLimit: Int,
    val reasons: List<Reason>,
    val freeText: Boolean,
    val minLength: Int,
    val maxLength: Int,
    val cooldownMillis: Long,
    val allowSelf: Boolean,
    val allowOffline: Boolean,
    val allowDuplicates: Boolean,
    val notifyStaff: Boolean,
    val notifyReporter: Boolean,
    val queueLimit: Int,
    val expireDays: Int,
    val actions: List<Action>,
) {

    fun reason(id: String): Reason? = reasons.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }

    data class Reason(
        val id: String,
        val label: String,
        val permission: String?,
    )

    data class Action(
        val label: String,
        val command: String,
        val permission: String?,
        val console: Boolean,
        val resolve: Boolean,
    )
}
