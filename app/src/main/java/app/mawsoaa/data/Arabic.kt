package app.mawsoaa.data

private val DIAC = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED\\u0640\\u08D3-\\u08FF]")
private val WS = Regex("\\s+")

/** Search normalisation. MUST stay identical to scripts/common.py normalize_ar(). */
fun normalizeAr(s: String): String {
    val sb = StringBuilder(s.length)
    for (ch in DIAC.replace(s, "")) {
        sb.append(
            when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ى', 'ئ' -> 'ي'
                'ة' -> 'ه'
                'ؤ' -> 'و'
                else -> ch
            }
        )
    }
    return WS.replace(sb.toString(), " ").trim()
}

/** Builds a safe FTS4 prefix query ("term* term*") from user input, or null if nothing searchable. */
fun ftsQuery(q: String): String? {
    val terms = normalizeAr(q).split(' ')
        .map { t -> t.filter { it.isLetterOrDigit() } }
        .filter { it.isNotEmpty() }
    return if (terms.isEmpty()) null else terms.joinToString(" ") { "$it*" }
}

fun Int.toArabicDigits(): String =
    toString().map { if (it in '0'..'9') ('٠' + (it - '0')) else it }.joinToString("")
