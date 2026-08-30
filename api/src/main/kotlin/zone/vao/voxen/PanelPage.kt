package zone.vao.voxen

/**
 * A page your plugin adds to the Voxen web panel.
 * Registered with [VoxenApi.registerPanelPage].
 *
 * Every method runs on a panel thread, never on the main thread; hop to the
 * scheduler before touching the Bukkit API.
 */
interface PanelPage {

    /**
     * Returns the HTML body of the page. The panel supplies the frame, the
     * sidebar entry and the styling, so return content only, no `<html>`.
     * Build it with [PanelHtml] and everything is escaped and themed for you.
     */
    fun render(request: PanelRequest): String = ""

    /** Handles a form posted from the page. Does nothing by default. */
    fun submit(request: PanelRequest) {}

    /**
     * Answers the request yourself, for anything that is not an HTML page in
     * the panel frame: JSON, a download, a redirect, or your own routing off
     * [PanelRequest.path] and [PanelRequest.method]. Return null to fall back
     * to [render] and [submit], which is what happens by default.
     */
    fun handle(request: PanelRequest): PanelResponse? = null

    /**
     * Whether the page is reachable right now. A page that returns false is
     * hidden from the sidebar and answers with 404, without being
     * unregistered. Useful while the feature behind it is switched off.
     */
    fun enabled(): Boolean = true
}
