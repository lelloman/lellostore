package com.lelloman.store.domain.credential

/**
 * The credential types LelloStore can issue, keyed by [CredentialIssuer.type].
 * Built from the DI set of issuers; two issuers claiming the same type is a wiring error.
 */
class CredentialIssuerRegistry(issuers: Set<CredentialIssuer>) {

    private val byType: Map<String, CredentialIssuer> = issuers.associateBy { it.type }

    init {
        require(byType.size == issuers.size) {
            "Duplicate credential types: ${issuers.groupBy { it.type }.filterValues { it.size > 1 }.keys}"
        }
        require(byType.keys.none { it.isBlank() }) { "A credential type must not be blank" }
    }

    val types: Set<String> get() = byType.keys

    operator fun get(type: String): CredentialIssuer? = byType[type]
}
