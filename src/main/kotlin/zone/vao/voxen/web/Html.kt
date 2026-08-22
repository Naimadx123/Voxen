package zone.vao.voxen.web

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object Html {

    fun escape(value: String): String = buildString(value.length) {
        for (char in value) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }

    fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    fun document(title: String, nav: String, body: String): String = """
        |<!DOCTYPE html>
        |<html lang="en">
        |<head>
        |<meta charset="utf-8">
        |<meta name="viewport" content="width=device-width, initial-scale=1">
        |<title>${escape(title)}</title>
        |<style>
        |$STYLE
        |</style>
        |</head>
        |<body>
        |<header><span class="brand">${escape(title)}</span><nav>$nav</nav></header>
        |<main>$body</main>
        |</body>
        |</html>
    """.trimMargin()

    private val STYLE = """
        :root { color-scheme: light dark; --bg: #f4f5f7; --card: #ffffff; --line: #d9dce1; --text: #1c1f24; --muted: #5b6472; --accent: #1d9bf0; }
        @media (prefers-color-scheme: dark) {
          :root { --bg: #14171c; --card: #1c2027; --line: #2c323b; --text: #e7eaee; --muted: #94a0b0; --accent: #58b6f7; }
        }
        * { box-sizing: border-box; }
        body { margin: 0; background: var(--bg); color: var(--text); font: 15px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }
        header { display: flex; align-items: center; gap: 24px; flex-wrap: wrap; padding: 14px 24px; background: var(--card); border-bottom: 1px solid var(--line); }
        .brand { font-weight: 600; letter-spacing: .3px; }
        nav { display: flex; gap: 16px; flex-wrap: wrap; }
        nav a { color: var(--muted); text-decoration: none; }
        nav a:hover, nav a.active { color: var(--accent); }
        main { max-width: 1100px; margin: 0 auto; padding: 24px; }
        h1 { font-size: 20px; margin: 0 0 16px; }
        .tabs { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 16px; }
        .tabs a { padding: 6px 12px; border: 1px solid var(--line); border-radius: 999px; color: var(--muted); text-decoration: none; font-size: 13px; }
        .tabs a.active { border-color: var(--accent); color: var(--accent); }
        .card { background: var(--card); border: 1px solid var(--line); border-radius: 10px; overflow-x: auto; }
        table { border-collapse: collapse; width: 100%; }
        th, td { padding: 10px 14px; text-align: left; border-bottom: 1px solid var(--line); vertical-align: top; }
        th { font-size: 12px; text-transform: uppercase; letter-spacing: .5px; color: var(--muted); }
        tr:last-child td { border-bottom: none; }
        .tag { display: inline-block; padding: 2px 8px; border-radius: 999px; font-size: 12px; border: 1px solid var(--line); color: var(--muted); }
        .tag.open { border-color: #e0a800; color: #e0a800; }
        .tag.claimed { border-color: var(--accent); color: var(--accent); }
        .tag.resolved { border-color: #3fb950; color: #3fb950; }
        .tag.dismissed { border-color: #f85149; color: #f85149; }
        h2 { font-size: 15px; margin: 24px 0 10px; color: var(--muted); text-transform: uppercase; letter-spacing: .5px; }
        .quote { margin: 0; padding: 16px; font-family: ui-monospace, "Cascadia Mono", Consolas, monospace; }
        tr.marked td { background: color-mix(in srgb, var(--accent) 14%, transparent); }
        .muted { color: var(--muted); font-size: 13px; }
        form.actions { display: flex; gap: 6px; flex-wrap: wrap; margin: 0; }
        button { font: inherit; font-size: 13px; padding: 5px 10px; border: 1px solid var(--line); border-radius: 6px; background: transparent; color: var(--text); cursor: pointer; }
        button:hover { border-color: var(--accent); color: var(--accent); }
        .empty { padding: 32px; text-align: center; color: var(--muted); }
        .cards { display: grid; gap: 16px; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); }
        .cards a { display: block; padding: 20px; background: var(--card); border: 1px solid var(--line); border-radius: 10px; color: var(--text); text-decoration: none; }
        .cards a:hover { border-color: var(--accent); }
    """.trimIndent()
}
