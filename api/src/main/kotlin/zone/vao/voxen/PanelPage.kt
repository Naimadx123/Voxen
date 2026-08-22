package zone.vao.voxen

/**
 * A page your plugin adds to the Voxen web panel.
 * Registered with [VoxenApi.registerPanelPage].
 *
 * Both methods run on a panel thread, never on the main thread; hop to the
 * scheduler before touching the Bukkit API.
 */
interface PanelPage {

    /**
     * Returns the HTML body of the page. The panel supplies the frame, the
     * sidebar entry and the styling, so return content only, no `<html>`.
     * Run every value you did not write yourself through [PanelHtml.escape].
     */
    fun render(request: PanelRequest): String

    /** Handles a form posted from the page. Does nothing by default. */
    fun submit(request: PanelRequest) {}
}
