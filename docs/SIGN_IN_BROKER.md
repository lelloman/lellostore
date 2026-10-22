# Embedded sign-in broker

LelloStore embeds the LelloAuth broker from the `vendor/lello-auth` git submodule,
pinned to `d89fe69da8880eba58429ab1fea1d34bc921d5a2`. Initialize it with:

```sh
git submodule update --init --recursive
```

Gradle `includeBuild` isolates its project names from the store's domain,
localdata and remoteapi modules. CI initializes the same gitlink. Kotlin/KSP and
Hilt are aligned with the broker's Kotlin 2.2 metadata. The host now requires
Android 11 (API 30), as specified by AUTH-1 and the broker library.

The broker screens are reviewed for daily use in AUTH-21: LelloStore visual
tokens, English/Italian copy, light/dark themes, 48 dp targets, large-text and
landscape wrapping, a coarse screen-reader countdown, and cancellation after an
expired approval returning to the caller. Synthetic screenshot fixtures live in
LelloAuth's debug source set (`broker/src/debug/.../BrokerPreviews.kt`); they are
not part of a release build.

Production broker enrollment and approval remain disabled, including the
exported App Link activity and the Accounts settings entry. Enabling production
requires a reviewed change after AUTH-22; `-PbrokerEnabled=true` affects debug
only. Release App Links use `auth.lelloman.com` and the release package/signature.

For a development build, use the existing store server setting/build default
(`default.server.url` in `android/local.properties`) for store discovery. Add:

```properties
broker.enabled=true
broker.dev.issuer=https://your-development-issuer
```

Equivalent Gradle properties are `-PbrokerEnabled=true` and
`-PbrokerDevIssuer=https://your-development-issuer`. The extra development issuer
is required to build Android's static App Link host filter; the actual login
issuer still comes from the selected store's `/api/server-config`. A native login
is selected only when this discovered issuer exactly matches the configured
broker issuer and at least one account is connected. Unsupported native request
shapes, an unreadable account store, or no accounts use the existing browser
path. Debug opt-in rejects the production issuer. The development server must
register `com.lelloman.store.debug` with the certificate of the debug keystore
that built the APK, its dedicated broker client, and the exact store OIDC callback
and client from discovery (see LelloAuth's `docs/OPERATIONS.md`). No signing keys
or machine-specific certificates belong in this repository.

After a successful hosted login, the user can connect the phone immediately or
later under Settings → Accounts. The library provides hosted enrollment,
connected account selection/disconnect, and an explicit approval screen. The
fingerprint/PIN toggle reads and writes `LelloAuthBroker.settings`, defaults off,
and reports a storage failure without pretending the setting was saved. Device
management is available per connected account through its Devices button.
Rename and revoke require an explicit confirmation and a fresh phone credential
prompt even with the approval toggle off; with it on, strong biometric or phone
credential is accepted. Disconnect deletes local credentials and the phone key.
Successful self-revocation performs the same local cleanup; expired or revoked
broker credentials require reconnecting. Revoked devices stay visible with
mutations disabled. Lists clear on account selection and backgrounding.

LelloStore itself sends the unchanged AppAuth request to the explicit embedded
broker activity. AppAuth's public `createStartForResultIntent` API manages its
callback and cancellation; token exchange and refresh stay in `AuthStoreImpl`.
The response must match both state and the complete original request, including
PKCE and redirect. Duplicate callbacks cannot start parallel token exchanges.
After process death a missing pending store login fails closed and the user
retries; no cached approval proof is restored. The library validates incoming
links and server-owned recipients, checks package certificates, and delivers
callbacks by explicit component. Its browser fallback uses `/authenticator/hosted`,
which is outside the broker's `/authorize` link filter.

## Verification

Use JDK 17 and Android SDK 36:

```sh
cd android
./gradlew lint test --no-daemon
```

`LoginResponseBindingTest` covers wrong state, request/redirect/PKCE replacement
and recreated requests. `EmbeddedSignInBrokerTest` covers the disabled default.
The host device tests retain the broker's phone-key and account-store tests and
exercise the real host accounts activity and exported approval activity.

Use an isolated Android 11+ **emulator**, with a screen lock and synthetic PIN
`2468`. Never run these commands against a physical phone. Build the synthetic
server, then run the adapted LelloAuth fixture from the repository root:

```sh
(cd android && ./gradlew :app:assembleDebug) # also creates the local debug keystore
cargo build --manifest-path vendor/lello-auth/Cargo.toml -p lello-auth-server
python3 scripts/broker-enrollment-fixture.py --serial emulator-5554
```

The fixture refuses a non-emulator serial and other attached devices, uses
synthetic accounts and a temporary HTTPS issuer/database, configures the debug
host identity and caller certificate, and pins all adb/Gradle calls to the chosen
emulator. It runs `ServerEnrollmentDeviceTest` on the **store APK**: two accounts
enroll without prompts; explicit approval succeeds with the setting off; with it
on, cancelling the PIN prompt signs nothing and retrying after a successful PIN
completes; wrong certificates and a second incoming request are refused; a fresh
approval model contains no proof; an expired transaction cannot sign (this test
waits for the real two-minute expiry). It exchanges the returned code and checks
nonce/audience and server registration/grant counts. The injected test CA and
synthetic callback receiver exist only in debug builds. Cleanup stops the server
and removes the adb reverse tunnel and temporary credentials.

For the additional encrypted-storage/key tests:

```sh
cd android
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -PbrokerEnabled=true -PbrokerDevIssuer=https://localhost:19443 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lelloman.store.auth.PhoneKeyDeviceTest,com.lelloman.store.auth.AccountStoreDeviceTest
```

To check account storage independently in a fresh instrumentation process, run
this class alone (the initialization rule runs before the Accounts activity is
launched, including with `HiltTestApplication`):

```sh
cd android
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -PbrokerEnabled=true -PbrokerDevIssuer=https://localhost:19443 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lelloman.store.auth.AccountStoreDeviceTest
```

Rebuild ordinary debug afterward to disable the feature and remove the fixture
CA. Manual release qualification still needs a discovered development store,
hosted store login and phone connection, a separately configured Pezzottify
caller (AUTH-25), store logout/re-login through the broker, and token refresh in
both apps. Verify disabled/no-connected-account browser behavior, cancellation,
revocation, offline retry and App Links for both signing identities. AUTH-22 owns
production enablement and real biometric/device evidence.


AUTH-17 device-management reproduction (isolated emulator only): run the server
fixture above against the pinned broker/server source. It now checks per-account
device isolation, cross-account mutation rejection, cancelled PIN confirmation,
PIN-confirmed rename and self-revoke in the real LelloStore Accounts activity,
rejection of the revoked access/refresh credentials, and local disconnect of the
remaining account. The HTTPS fixture forwards PATCH and asserts exactly one
server registration was revoked. The test PIN is `2468`.

For manual qualification, open Settings → Accounts with two connected synthetic
accounts. Open Devices on each and verify only that account's devices appear.
Rename a phone: cancel the system prompt once (no change), then confirm it.
Revoke a second registration and refresh to see Revoked; revoke this phone and
verify its account disappears and is no longer offered for sign-in. Repeat with
the biometric toggle on and off. Revoke or invalidate a connection from another
session, then refresh its Devices list: a reconnect message replaces the expired
connection. Disconnect the other account and verify a new sign-in uses browser
fallback. Offline rename/revoke must report failure and allow a fresh attempt.
Production features remain disabled pending AUTH-22.
