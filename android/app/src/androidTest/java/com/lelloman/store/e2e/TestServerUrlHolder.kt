package com.lelloman.store.e2e

/**
 * Singleton holder for the test server URL.
 * Must be set before the test activity is created.
 */
object TestServerUrlHolder {
    @Volatile
    var serverUrl: String = "http://localhost:8080"
}
