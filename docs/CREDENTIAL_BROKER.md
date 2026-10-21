# Credential requests from other apps

The Android client can issue credentials to other apps on the same device. An app
asks for a credential of some *type*, LelloStore shows one confirmation screen naming
the calling app and what it asks for, and after the user taps **Confirm** the credential
is returned in the activity result. Nothing is issued without that confirmation.

Implemented types:

| Type | Credential | Who can issue it |
| --- | --- | --- |
| `lellostore.device_token` | A new [device token](../README.md#zero-login-devices-with-a-device-token) as `device-token.json` | A LelloStore signed in with an administrator OIDC session |

## Contract

The strings below are a public API; callers are built against them and they are never
renamed. They are defined in `CredentialContract` (`android/app/.../credential/`).

Start the exported activity **for result**, with an explicit package:

- Action `com.lelloman.store.action.ISSUE_CREDENTIAL`, category `DEFAULT`, package
  `com.lelloman.store` (debug builds are `com.lelloman.store.debug`).

Extras in:

| Extra | Type | Meaning |
| --- | --- | --- |
| `com.lelloman.store.extra.CREDENTIAL_TYPE` | String, required | The credential type, e.g. `lellostore.device_token` |
| `com.lelloman.store.extra.SUBJECT_NAME` | String, required | What the credential is for, shown to the user. Trimmed; 1 to 64 characters, without control or bidirectional-override characters. For device tokens it is the device name and the token's name on the server |
| `com.lelloman.store.extra.REPLACE_EXISTING` | Boolean, optional (default `false`) | Replace earlier credentials of the same subject. Device tokens: after creating the new token, revoke the older unrevoked tokens with exactly the same name |
| `com.lelloman.store.extra.PARAMS` | Bundle, optional | Type-specific parameters; every value must be a String. Device tokens take none |

Result:

- `RESULT_OK` with `com.lelloman.store.extra.CREDENTIAL_TYPE` (the requested type) and
  `com.lelloman.store.extra.CREDENTIAL` (String).
- `RESULT_CANCELED`, normally with `com.lelloman.store.extra.ERROR`:

| Error | When |
| --- | --- |
| `cancelled` | The user declined (Cancel or Back) |
| `not_logged_in` | LelloStore has no session, or the server rejected it (`401`) |
| `not_authorized` | The account may not issue this credential, e.g. the server answered `403` |
| `unsupported_type` | This LelloStore version does not know the type |
| `invalid_request` | Wrong action, missing type, invalid subject name, non-String params or unreadable extras |
| `caller_unknown` | The activity was not started for result, so the caller cannot be identified |
| `network` | The server could not be reached or failed; also a confirmed request whose process died before it finished |

A `RESULT_CANCELED` without `ERROR` means the activity ended without reaching a decision
(for example the system removed it).

For `lellostore.device_token`, `CREDENTIAL` is exactly the `device-token.json` the web admin
**Device tokens** page downloads and encodes in its QR code: same keys, order and escaping,
no whitespace (only the token below is fake):

```json
{"version":1,"server_url":"https://store.example.com","name":"Mi Box (living room)","token":"lellostore_dev_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}
```

`server_url` is the store LelloStore is signed in to. The new token's principal is
`device-token:<id>`. With `REPLACE_EXISTING` it takes over the access of the tokens it
replaces (see [Behaviour](#behaviour)); otherwise, and when they had no access, it joins the
server's `all` system group so the device does not start with an empty catalogue. Narrow it
afterwards as described in the README if the device should see less.
Treat the result as a secret: do not log it, and store it only where the device needs it.

### Caller example

```kotlin
private val requestDeviceToken = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
) { result ->
    val data = result.data
    if (result.resultCode == Activity.RESULT_OK && data != null) {
        val deviceTokenJson = data.getStringExtra("com.lelloman.store.extra.CREDENTIAL")
        // Hand deviceTokenJson to the device; never log it.
    } else {
        val error = data?.getStringExtra("com.lelloman.store.extra.ERROR") ?: "cancelled"
        // Show a message for `not_logged_in`, `not_authorized`, `network`, ...
    }
}

fun askLelloStore(deviceName: String) {
    val intent = Intent("com.lelloman.store.action.ISSUE_CREDENTIAL")
        .setPackage("com.lelloman.store")
        .putExtra("com.lelloman.store.extra.CREDENTIAL_TYPE", "lellostore.device_token")
        .putExtra("com.lelloman.store.extra.SUBJECT_NAME", deviceName)
        .putExtra("com.lelloman.store.extra.REPLACE_EXISTING", true)
    try {
        requestDeviceToken.launch(intent)
    } catch (_: ActivityNotFoundException) {
        // LelloStore is not installed, or too old to issue credentials.
    }
}
```

A caller targeting Android 11+ also needs to see LelloStore:

```xml
<queries>
    <package android:name="com.lelloman.store" />
</queries>
```

## Behaviour

- **Caller identity** comes from `getCallingActivity()`, never from an extra. The screen
  shows the caller's label and icon (LelloStore holds `QUERY_ALL_PACKAGES`, so they load on
  Android 11+) with its package name below, because any app can pick any label. If the
  package cannot be resolved the package name is shown alone.
- **Confirmation text** comes from the type, for example "PezzotTV wants a LelloStore device
  token for “Mi Box (living room)”. The device will be able to install and update apps from
  your store.", followed by the account the credential is created with.
- **Not signed in**: the screen says so and offers **Open LelloStore**; the request ends with
  `not_logged_in` and the caller asks again after the user has signed in. The browser login
  is not run inside the request, because AppAuth's single-task authorization activity would
  join LelloStore's task rather than the caller's and the result could be lost.
- **Device-token sessions** (a provisioned LelloStore) are not administrators, so a device
  token request from such a device ends with `not_authorized`.
- **At most one issue per confirmation.** Repeated taps, Back while issuing, and
  configuration changes reuse the one request in flight. A confirmation is recorded in saved
  state; if the process dies after it, the restored screen ends with `network` instead of
  offering a second confirmation. A new request from the caller starts over.
- **The credential never leaves the result intent.** It is not logged, persisted, notified,
  shown or kept in saved state; the local log records only the caller package, the type and
  the outcome (`issued` or the error).
- **Hardening**: exported but confirmation-gated; `excludeFromRecents`, empty `taskAffinity`;
  touches are dropped while another window obscures it (`filterTouchesWhenObscured`) and
  other apps' overlays are hidden on Android 12+ (`setHideOverlayWindows`).
- **Access of a new device token.** With `REPLACE_EXISTING`, LelloStore lists the tokens before
  creating the new one: the new token joins the union of the app groups of the unrevoked tokens
  with exactly the same name (in the create request, so atomically) and then receives the union
  of their direct app grants (`beta` wins over `stable` for the same app). When there is
  nothing to take over (the first token for that name, a request without `REPLACE_EXISTING`,
  or earlier tokens without groups or grants) it joins the `all` system group, if the server
  has one. If the earlier access cannot be read completely (listing tokens, one token's grants
  or the groups fails), the token is still issued with what could be read and never falls back
  to `all`, so a failed read cannot widen a device's access; an administrator completes it on
  the web. The caller is not told: the result contract is unchanged and the caller could not
  fix access anyway. The local log records `credential.device_token.access` with counts only.
- **Replacing device tokens** is best effort. Older means created no later than the new token
  (server times have one-second resolution); revoked tokens, other names and newer tokens are
  left alone. If listing, a revocation or applying a grant fails the new token is still returned.

## Adding a credential type

A type is one `CredentialIssuer` (`android/domain/.../credential/CredentialIssuer.kt`) plus
one Hilt binding. The activity, confirmation screen, caller identity, validation of the
generic extras, the one-issue guarantee and the result are shared.

For example, to let LelloStore provision the identity of pezzottify:

1. Pick a stable type id, namespaced by its owner: `pezzottify.identity`. It becomes part of
   the contract; document it in the table at the top of this file.
2. Implement the issuer in `:app` (`com.lelloman.store.credential`):

   ```kotlin
   class PezzottifyIdentityIssuer @Inject constructor(
       @ApplicationContext private val context: Context,
       private val api: PezzottifyIdentityApi,
   ) : CredentialIssuer {
       override val type = "pezzottify.identity"

       // Optional: type-specific checks of request.params; return INVALID_REQUEST to refuse.
       override fun validate(request: CredentialRequest): CredentialError? = null

       override fun describe(request: CredentialRequest) = CredentialDescription(
           title = context.getString(R.string.credential_pezzottify_title),
           message = context.getString(R.string.credential_pezzottify_message, request.callerLabel, request.subjectName),
       )

       override suspend fun issue(request: CredentialRequest): IssueResult =
           api.provision(request.subjectName).fold(
               onSuccess = { IssueResult.Success(it.toWireFormat()) },
               onFailure = { IssueResult.Failure(it.toCredentialError()) },
           )
   }
   ```

   `describe` must say plainly what the caller will be able to do. `issue` is called at most
   once per confirmation; map refusals to the existing errors (`403` → `NOT_AUTHORIZED`,
   `401` → `NOT_LOGGED_IN`, transport → `NETWORK`), rethrow `CancellationException`, and keep
   the credential out of logs and `toString()`.
3. Bind it in `CredentialModule`:

   ```kotlin
   @Binds
   @IntoSet
   abstract fun bindPezzottifyIdentityIssuer(issuer: PezzottifyIdentityIssuer): CredentialIssuer
   ```

   `CredentialIssuerRegistry` refuses two issuers with the same type at startup.
4. Add English and Italian strings, a unit test of the issuer like
   `DeviceTokenCredentialIssuerTest`, and the credential's format to this document.

No new error codes are needed for a new type; if one ever is, add it to `CredentialError`
and to the table above, since callers built against the current list will treat an unknown
code as a generic failure.
