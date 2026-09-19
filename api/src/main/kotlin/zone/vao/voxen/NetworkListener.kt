package zone.vao.voxen

/**
 * Receives payloads other servers sent with [VoxenApi.sendNetworkMessage].
 * Registered with [VoxenApi.registerNetworkListener].
 *
 * Runs on the server thread, so Bukkit calls are safe. A server never hears
 * its own messages, and Voxen has already checked the signature and dropped
 * duplicates before this is called.
 */
fun interface NetworkListener {
    fun onMessage(channel: String, payload: String, fromServer: String)
}
