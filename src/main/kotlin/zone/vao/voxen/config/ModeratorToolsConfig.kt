package zone.vao.voxen.config

data class ModeratorToolsConfig(
    val enabled: Boolean,
    val dialogs: Boolean,
    val warningsEnabled: Boolean,
    val warningExpireMillis: Long,
    val notifyTarget: Boolean,
    val warningRules: List<WarningRule>,
    val notesEnabled: Boolean,
    val joinAlerts: Boolean,
    val deleteEnabled: Boolean,
    val deleteKeep: Int,
    val deleteButton: Boolean,
    val manageButton: Boolean,
) {

    val warningCutoff: Long
        get() = if (warningExpireMillis <= 0L) 0L else System.currentTimeMillis() - warningExpireMillis

    data class WarningRule(
        val at: Int,
        val repeat: Boolean,
        val commands: List<String>,
    ) {

        fun matches(count: Int): Boolean = when {
            at <= 0 -> false
            repeat -> count >= at && count % at == 0
            else -> count == at
        }
    }
}
