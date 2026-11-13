package com.sgexpense.capture

import java.util.Locale

/**
 * Decides whether two alerts name the same payee. UOB masks names in some alerts
 * ("HWAXX WEX MIXX" for "HWANG WEI MING"), so trailing X's are treated as wildcards.
 * A blank name on either side counts as a match (nothing to contradict it).
 */
object PayeeMatcher {
    private fun words(s: String): List<String> =
        s.uppercase(Locale.ENGLISH).split(Regex("""[^A-Z0-9]+""")).filter { it.length >= 2 }

    /** Masked word "HWAXX" matches "HWANG"; plain words must be equal. */
    private fun wordMatches(a: String, b: String): Boolean {
        val stubA = a.trimEnd('X')
        val stubB = b.trimEnd('X')
        return when {
            a == b -> true
            a.endsWith("XX") && stubA.length >= 2 -> b.startsWith(stubA)
            b.endsWith("XX") && stubB.length >= 2 -> a.startsWith(stubB)
            else -> false
        }
    }

    fun samePayee(a: String, b: String): Boolean {
        val wa = words(a)
        val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return true
        return wordMatches(wa.first(), wb.first())
    }
}
