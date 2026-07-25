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
     * parsers preserve them instead of normalising to spaces; other C0 control
     * characters (illegal in XML 1.0, even as numeric refs) are dropped.
     */
    fun escapeAttr(value: String): String {
        val sb = StringBuilder(value.length + 16)
        for (ch in value) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                '\n' -> sb.append("&#10;")
                '\r' -> sb.append("&#13;")
                '\t' -> sb.append("&#9;")
                else -> if (ch.code >= 0x20) sb.append(ch) // drop other control chars
            }
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
