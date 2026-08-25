package zone.vao.voxen

/**
 * Turns the id half of a moderator name into something readable.
 * Registered with [VoxenApi.registerModeratorResolver].
 *
 * Voxen stores whoever acted as a plain string, so an addon acting for
 * somebody outside the game writes `discord:123456789` and gets it back on
 * every event, which is what keeps it from answering its own actions. That
 * string is no good on a screen, so a resolver turns the `123456789` half
 * into `4g0 (Naimad123)` wherever Voxen prints it.
 *
 * Runs on whichever thread is drawing — a chat message, a web page, a
 * dialog — and possibly once per row, so keep it fast, keep it thread safe
 * and cache on your side. Return null to leave the name as it was stored.
 */
fun interface ModeratorResolver {
    fun resolve(id: String): String?
}
