package com.lelloman.store.logger

interface Logger {
    fun audit(event: String, fields: Map<String, Any?> = emptyMap()) {}
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}
