package com.diffuse.util

/**
 * The first exception of type [T] in this exception's cause chain (itself included), or null.
 * Lets callers recognise a root cause, such as Drive being full, even after another layer
 * wrapped it (e.g. a run aborted because several uploads in a row hit that same error).
 */
inline fun <reified T : Throwable> Throwable.causeOfType(): T? =
    generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.filterIsInstance<T>().firstOrNull()
