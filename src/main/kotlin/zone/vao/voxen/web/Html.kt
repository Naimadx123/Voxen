package zone.vao.voxen.web

import zone.vao.voxen.PanelHtml
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object Html {

    fun escape(value: String): String = PanelHtml.escape(value)

    fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    fun badge(value: String): String = escape(value.trim().take(1).uppercase().ifEmpty { "•" })

    fun document(title: String, nav: String, body: String, user: String, refresh: Int): String = """
        |<!DOCTYPE html>
        |<html lang="en">
        |<head>
        |<meta charset="utf-8">
        |<meta name="viewport" content="width=device-width, initial-scale=1">
        |${if (refresh > 0) "<meta http-equiv=\"refresh\" content=\"$refresh\">" else ""}
        |<title>${escape(title)}</title>
        |<style>
        |$STYLE
        |</style>
        |</head>
        |<body>
        |<div class="app">
        |<aside class="side">
        |<a class="brand" href="/"><span class="ico brand-ico">${badge(title)}</span><span>${escape(title)}</span></a>
        |<nav>$nav</nav>
        |<div class="who"><span class="ico">${badge(user)}</span><span>${escape(user)}</span></div>
        |</aside>
        |<main>$body</main>
        |</div>
        |</body>
        |</html>
    """.trimMargin()

    private val STYLE = """
        :root {
          color-scheme: light dark;
          --bg: #f5f6f8; --card: #ffffff; --side: #ffffff; --line: #e4e7ec; --text: #14171c;
          --muted: #6b7686; --accent: #2f7de1; --soft: rgba(47, 125, 225, .12);
          --shadow: 0 1px 2px rgba(16, 24, 40, .05), 0 8px 24px rgba(16, 24, 40, .05);
        }
        @media (prefers-color-scheme: dark) {
          :root {
            --bg: #0f1216; --card: #171b21; --side: #12161b; --line: #262c35; --text: #e6eaf0;
            --muted: #8d99a8; --accent: #5aa9ff; --soft: rgba(90, 169, 255, .14);
            --shadow: 0 1px 2px rgba(0, 0, 0, .35);
          }
        }
        * { box-sizing: border-box; }
        body { margin: 0; background: var(--bg); color: var(--text); font: 15px/1.55 system-ui, -apple-system, "Segoe UI", sans-serif; -webkit-font-smoothing: antialiased; }
        a { color: var(--accent); }
        .app { display: flex; min-height: 100vh; min-height: 100dvh; }
        .side { position: sticky; top: 0; flex: none; width: 246px; height: 100vh; height: 100dvh; display: flex; flex-direction: column; padding: 18px 14px; background: var(--side); border-right: 1px solid var(--line); }
        .brand { display: flex; align-items: center; gap: 10px; padding: 6px 8px 20px; color: var(--text); text-decoration: none; font-weight: 600; letter-spacing: .2px; }
        .ico { display: grid; place-items: center; flex: none; width: 26px; height: 26px; border-radius: 8px; background: var(--soft); color: var(--accent); font-size: 12px; font-weight: 700; }
        .brand-ico { background: var(--accent); color: #fff; }
        nav { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
        nav a { display: flex; align-items: center; gap: 10px; padding: 9px 10px; border-radius: 9px; color: var(--muted); text-decoration: none; font-size: 14px; white-space: nowrap; transition: background .15s ease, color .15s ease; }
        nav a:hover { background: var(--soft); color: var(--text); }
        nav a.active { background: var(--soft); color: var(--accent); font-weight: 600; }
        .who { display: flex; align-items: center; gap: 10px; margin-top: auto; padding: 12px 8px 2px; border-top: 1px solid var(--line); color: var(--muted); font-size: 13px; overflow: hidden; }
        .who span:last-child { overflow: hidden; text-overflow: ellipsis; }
        main { flex: 1; min-width: 0; padding: 28px 32px 56px; }
        h1 { font-size: 22px; letter-spacing: -.2px; margin: 0 0 18px; }
        h2 { font-size: 12px; margin: 28px 0 10px; color: var(--muted); text-transform: uppercase; letter-spacing: .6px; }
        .tabs { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 18px; }
        .tabs a { padding: 6px 13px; border: 1px solid var(--line); border-radius: 999px; background: var(--card); color: var(--muted); text-decoration: none; font-size: 13px; transition: border-color .15s ease, color .15s ease; }
        .tabs a:hover { color: var(--text); }
        .tabs a.active { border-color: transparent; background: var(--accent); color: #fff; }
        .tabs .switch { margin-left: auto; }
        .card { background: var(--card); border: 1px solid var(--line); border-radius: 12px; box-shadow: var(--shadow); overflow-x: auto; }
        .card + .card, .card + .actions { margin-top: 14px; }
        table { border-collapse: collapse; width: 100%; }
        th, td { padding: 11px 16px; text-align: left; border-bottom: 1px solid var(--line); vertical-align: top; }
        th { font-size: 11px; text-transform: uppercase; letter-spacing: .6px; color: var(--muted); font-weight: 600; }
        tbody tr:last-child td { border-bottom: none; }
        tbody tr { transition: background .15s ease; }
        tbody tr:hover td { background: color-mix(in srgb, var(--text) 3%, transparent); }
        .tag { display: inline-block; padding: 2px 9px; border-radius: 999px; font-size: 12px; font-weight: 600; border: 1px solid var(--line); color: var(--muted); }
        .tag.open { border-color: #e0a800; color: #e0a800; }
        .tag.claimed { border-color: var(--accent); color: var(--accent); }
        .tag.resolved { border-color: #3fb950; color: #3fb950; }
        .tag.dismissed { border-color: #f85149; color: #f85149; }
        .tag.answered { border-color: #3fb950; color: #3fb950; }
        .tag.closed { border-color: var(--line); color: var(--muted); }
        .quote { margin: 0; padding: 18px; font-family: ui-monospace, "Cascadia Mono", Consolas, monospace; }
        tr.marked td { background: color-mix(in srgb, var(--accent) 14%, transparent); }
        .muted { color: var(--muted); font-size: 13px; }
        form.actions { display: flex; gap: 8px; flex-wrap: wrap; margin: 14px 0; }
        button { font: inherit; font-size: 13px; padding: 7px 13px; border: 1px solid var(--line); border-radius: 8px; background: var(--card); color: var(--text); cursor: pointer; transition: border-color .15s ease, color .15s ease; }
        button:hover { border-color: var(--accent); color: var(--accent); }
        input.reply { flex: 1 1 280px; min-width: 180px; font: inherit; font-size: 13px; padding: 7px 12px; border: 1px solid var(--line); border-radius: 8px; background: var(--card); color: var(--text); }
        input.reply:focus { outline: none; border-color: var(--accent); }
        .empty { padding: 40px; text-align: center; color: var(--muted); }
        .cards { display: grid; gap: 16px; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); }
        .cards a { display: block; padding: 22px; background: var(--card); border: 1px solid var(--line); border-radius: 12px; box-shadow: var(--shadow); color: var(--text); text-decoration: none; font-weight: 600; transition: border-color .15s ease, transform .15s ease; }
        .cards a:hover { border-color: var(--accent); transform: translateY(-2px); }
        @media (max-width: 900px) {
          .app { flex-direction: column; }
          .side { position: static; width: auto; height: auto; flex-direction: row; align-items: center; gap: 14px; padding: 10px 14px; border-right: none; border-bottom: 1px solid var(--line); overflow-x: auto; }
          .brand { padding: 0; }
          .brand span:last-child { display: none; }
          nav { flex-direction: row; }
          .who { margin-top: 0; padding: 0 0 0 14px; border-top: none; border-left: 1px solid var(--line); }
          main { padding: 20px 16px 44px; }
        }
    """.trimIndent()
}
