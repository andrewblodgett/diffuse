package com.diffuse.backup.format

/**
 * Minimal, correct XML emission for the SMS Backup & Restore format. Hand-rolled
 * (rather than android.util.Xml) so the writers are pure Kotlin and unit-testable,
 * and so attribute escaping/ordering exactly match what the SyncTech app produces.
 */
object Xml {
    const val DECLARATION = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>"

    /** SMS Backup & Restore renders an absent value as the literal string `null`. */
    const val NULL = "null"

    /**
     * Escapes a value for use inside a double-quoted XML attribute. The five XML
     * entities are escaped; tab/newline/carriage-return are numeric-encoded so
     * parsers preserve them instead of normalising to spaces. Characters XML 1.0
     * forbids outright are dropped: other C0 controls, U+FFFE/U+FFFF, and unpaired
     * UTF-16 surrogates (a malformed message body would otherwise make the whole
     * document unparseable for a restore tool).
     */
    fun escapeAttr(value: String): String {
        val sb = StringBuilder(value.length + 16)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch == '\'' -> sb.append("&apos;")
                ch == '\n' -> sb.append("&#10;")
                ch == '\r' -> sb.append("&#13;")
                ch == '\t' -> sb.append("&#9;")
                ch.code < 0x20 || ch == '\uFFFE' || ch == '\uFFFF' -> Unit // illegal: drop
                ch.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> {
                    sb.append(ch).append(value[i + 1]) // a valid pair (e.g. an emoji)
                    i++
                }
                ch.isSurrogate() -> Unit // unpaired surrogate: drop
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }
}

/** Appends ` name="escaped"`; a null value becomes `name="null"`, per the format. */
internal fun StringBuilder.attr(name: String, value: String?): StringBuilder {
    append(' ').append(name).append("=\"")
    append(Xml.escapeAttr(value ?: Xml.NULL))
    append('"')
    return this
}

internal fun StringBuilder.attr(name: String, value: Long): StringBuilder =
    attr(name, value.toString())

internal fun StringBuilder.attr(name: String, value: Int): StringBuilder =
    attr(name, value.toString())
