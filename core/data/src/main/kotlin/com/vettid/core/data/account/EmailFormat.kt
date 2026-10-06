package com.vettid.core.data.account

/** A plausible email address for the typed setup code (the API decides; this only guards typos). */
object EmailFormat {
    private const val MAX = 254
    private val RE = Regex("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$")

    fun isPlausible(email: String): Boolean = email.length <= MAX && RE.matches(email.trim())
}
