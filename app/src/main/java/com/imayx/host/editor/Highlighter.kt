package com.imayx.host.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.imayx.host.utils.PathUtil

enum class Lang { HTML, CSS, JS, JSON, XML, MD, TEXT }

class SyntaxPalette(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val tag: Color,
    val attr: Color
)

object Highlighter {
    const val MAX_HIGHLIGHT_CHARS = 150_000

    val EDITABLE = setOf("html", "htm", "css", "js", "mjs", "cjs", "json", "xml", "txt", "md", "svg", "webmanifest", "yml", "yaml", "csv", "ts", "map")

    fun isEditable(name: String): Boolean {
        val ext = PathUtil.extension(name)
        return ext in EDITABLE || name.equals("LICENSE", ignoreCase = true)
    }

    fun langFor(name: String): Lang = when (PathUtil.extension(name)) {
        "html", "htm" -> Lang.HTML
        "css" -> Lang.CSS
        "js", "mjs", "cjs", "ts" -> Lang.JS
        "json", "webmanifest", "map" -> Lang.JSON
        "xml", "svg" -> Lang.XML
        "md" -> Lang.MD
        else -> Lang.TEXT
    }

    private val jsRx = Regex(
        "(//[^\\n]*|/\\*[\\s\\S]*?\\*/)" +
            "|(\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'|`(?:\\\\.|[^`\\\\])*`)" +
            "|\\b(\\d+(?:\\.\\d+)?)\\b" +
            "|\\b(const|let|var|function|return|if|else|for|while|do|switch|case|break|continue|new|class|extends|import|export|from|default|async|await|try|catch|finally|throw|typeof|instanceof|in|of|this|null|undefined|true|false|void|delete|yield|static|super)\\b"
    )

    private val cssRx = Regex(
        "(/\\*[\\s\\S]*?\\*/)" +
            "|(\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*')" +
            "|(#[0-9a-fA-F]{3,8}\\b)" +
            "|((?<![\\w#.-])-?\\d*\\.?\\d+(?:px|em|rem|%|vh|vw|s|ms|deg)?)" +
            "|(@[a-zA-Z-]+)" +
            "|([a-zA-Z-]+)(?=\\s*:)"
    )

    private val jsonRx = Regex(
        "(\"(?:\\\\.|[^\"\\\\\\n])*\")(\\s*:)?" +
            "|\\b(true|false|null)\\b" +
            "|(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)"
    )

    private val markupOuter = Regex("(<!--[\\s\\S]*?-->)|(<[/?!]?[A-Za-z][^>]*>)")
    private val tagName = Regex("^<[/?!]?[A-Za-z0-9:_-]+")
    private val attrRx = Regex("([A-Za-z_:][A-Za-z0-9_:.-]*)(\\s*=\\s*)(\"[^\"]*\"|'[^']*')")

    private val mdRx = Regex(
        "(^#{1,6} .*$)|(```[\\s\\S]*?```)|(`[^`\\n]+`)|(\\*\\*[^*\\n]+\\*\\*)|(\\[[^\\]\\n]+\\]\\([^)\\n]+\\))",
        RegexOption.MULTILINE
    )

    fun highlight(text: String, lang: Lang, p: SyntaxPalette): AnnotatedString {
        if (lang == Lang.TEXT || text.length > MAX_HIGHLIGHT_CHARS) return AnnotatedString(text)
        return buildAnnotatedString {
            append(text)
            when (lang) {
                Lang.JS -> for (m in jsRx.findAll(text)) {
                    val color = when {
                        m.groups[1] != null -> p.comment
                        m.groups[2] != null -> p.string
                        m.groups[3] != null -> p.number
                        else -> p.keyword
                    }
                    addStyle(SpanStyle(color = color), m.range.first, m.range.last + 1)
                }
                Lang.CSS -> for (m in cssRx.findAll(text)) {
                    val color = when {
                        m.groups[1] != null -> p.comment
                        m.groups[2] != null -> p.string
                        m.groups[3] != null -> p.number
                        m.groups[4] != null -> p.number
                        m.groups[5] != null -> p.keyword
                        else -> p.attr
                    }
                    addStyle(SpanStyle(color = color), m.range.first, m.range.last + 1)
                }
                Lang.JSON -> for (m in jsonRx.findAll(text)) {
                    val color = when {
                        m.groups[1] != null && m.groups[2] != null -> p.attr
                        m.groups[1] != null -> p.string
                        m.groups[3] != null -> p.keyword
                        else -> p.number
                    }
                    val end = if (m.groups[1] != null) m.groups[1]!!.range.last + 1 else m.range.last + 1
                    addStyle(SpanStyle(color = color), m.range.first, end)
                }
                Lang.HTML, Lang.XML -> for (m in markupOuter.findAll(text)) {
                    if (m.groups[1] != null) {
                        addStyle(SpanStyle(color = p.comment), m.range.first, m.range.last + 1)
                    } else {
                        val tag = m.value
                        val base = m.range.first
                        tagName.find(tag)?.let { t ->
                            addStyle(SpanStyle(color = p.tag), base, base + t.range.last + 1)
                        }
                        for (a in attrRx.findAll(tag)) {
                            val n = a.groups[1]!!.range
                            val v = a.groups[3]!!.range
                            addStyle(SpanStyle(color = p.attr), base + n.first, base + n.last + 1)
                            addStyle(SpanStyle(color = p.string), base + v.first, base + v.last + 1)
                        }
                        if (tag.endsWith(">")) {
                            val close = if (tag.endsWith("/>")) 2 else 1
                            addStyle(SpanStyle(color = p.tag), base + tag.length - close, base + tag.length)
                        }
                    }
                }
                Lang.MD -> for (m in mdRx.findAll(text)) {
                    val color = when {
                        m.groups[1] != null -> p.keyword
                        m.groups[2] != null || m.groups[3] != null -> p.string
                        m.groups[4] != null -> p.attr
                        else -> p.tag
                    }
                    addStyle(SpanStyle(color = color), m.range.first, m.range.last + 1)
                }
                Lang.TEXT -> {}
            }
            append("")
        }
    }
}
