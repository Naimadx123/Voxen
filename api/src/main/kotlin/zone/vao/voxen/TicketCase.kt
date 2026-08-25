package zone.vao.voxen

/** A ticket together with its whole conversation, oldest message first. */
data class TicketCase(
    val ticket: TicketInfo,
    val messages: List<TicketInfo.Message>,
)
