# Generic store product roadmap

The product will become a generic, publicly available Android app distribution system. The existing `lellostore` repository will become the configuration and deployment of that product at `store.lelloman.com`. The public product name is undecided; Appiciello is a candidate, not a selected name.

The [deployment coupling audit](PRODUCT_DECOUPLING_AUDIT.md) records the starting implementation issues. This roadmap defines the intended destination and migration stages; the [specification](PRODUCT_SPEC.md) tracks implementation and verification progress.

## Repository ownership

| Public product repository | LelloStore deployment repository |
| --- | --- |
| Android client, backend, web administration UI, publisher | Pinned upstream release or image digest |
| Protocols, migrations, tests, build and release tooling | Deployment manifests, domain and reverse-proxy configuration |
| Generic setup workflow and configuration schemas | Instance settings, authentication-provider configuration and branding |
| Documentation and runnable deployment examples | References to externally stored secrets and operational runbooks |
| Product branding and optional integration support | Backup, restore and upgrade procedures for this instance |

Reusable fixes and features belong upstream. The deployment repository consumes published artifacts and supported configuration rather than maintaining copied application code or a permanent patch set. Runtime data, uploaded apps, credentials, and signing keys remain outside source control.

The default target is the same Android APK and backend image for every instance. The product has its own name and application identity; the connected store can present its configured name, logo, and server address within that product. Per-instance Android builds should not be necessary to connect to a different store.

## Migration stages

| Stage | Deliverable | Completion condition |
| --- | --- | --- |
| 1. Decouple the existing code | Replace personal service assumptions with explicit configuration, isolate sessions/data by server, and remove local checkout dependencies. | A second independent deployment works without editing application code or using personal infrastructure. |
| 2. Make backend deployment approachable | Provide a runnable deployment example, validated configuration, and an operator setup flow for public URL, authentication, initial administrator, storage, and optional services. | A new operator can deploy, complete setup, sign in, publish an app, and follow documented backup/upgrade steps. |
| 3. Configure Android before login | Introduce a server setup screen accepting a manually entered server address or a scanned QR code, followed by server verification and the advertised login flow. | Both entry methods produce the same configuration, and switching server safely resets the previous connection and session. |
| 4. Generalize authentication | Make authentication a server-advertised contract with provider-independent identity and authorization handling across Android, web, backend, and publisher. | Independent deployments with different supported providers work with the same client builds; no client requires LelloAuth. |
| 5. Select and apply the product name | Rename repository-facing and user-facing product identity, documentation, artifacts, and release tooling consistently. | Naming is consistent and Android package, callback, recovery, and signing migration choices are documented and tested. |
| 6. Publish the generic product | Apply the selected Apache 2.0 license, complete source/dependency distribution preparation, and publish the renamed project with working CI, releases, and setup documentation. | An outside contributor can obtain and build the product without private dependencies or configuration. |
| 7. Convert LelloStore into a deployment | Replace application ownership in this repository with configuration that consumes the public product, and migrate the current service. | The live instance runs an upstream release with its existing catalog, access rules, integrations, and a verified recovery procedure. |

The authentication and public configuration contracts must be designed during stage 1 because backend onboarding and Android setup depend on them. Stage 4 completes the supported authentication behavior; it should not force stages 2 and 3 to invent temporary, incompatible login configuration.

## Backend setup design

Setup should establish a validated configuration and initial administrative access. An unconfigured server must not become publicly claimable: use an operator-held setup credential or a local setup command. Once initialized, administration uses normal authenticated access.

Keep one configuration model for interactive onboarding and automated deployment. Define precedence and ownership for environment/file settings versus persisted settings; avoid a UI that appears to save settings that an environment variable silently overrides. Authentication changes need validation and a recovery path so an operator cannot accidentally lock out administration.

A public, versioned server configuration endpoint supplies only the information clients need before login: instance identity, protocol compatibility, authentication method and public client parameters, and enabled capabilities. Secrets and private signing material stay server-side. The web UI loads this configuration at runtime so moving between deployments does not require rebuilding the image.

## Android setup design

The intended sequence is **enter server address or scan QR → validate server and show its identity → confirm connection → authenticate → open catalog**.

Use a versioned QR payload containing the server address, with optional non-sensitive setup metadata. Treat the server as authoritative for current configuration; do not embed passwords, access tokens, or client secrets in ordinary setup QR codes. Show the destination before connecting, and handle invalid codes, unreachable servers, unsupported protocol versions, and authentication failures as separate actionable states.

Begin with one active store. Switching must coordinate credentials, cached catalog data, preferences tied to that store, push registrations, and pending download/install operations. Completing an old login or request after a switch must not restore the old session or overwrite the new store's data. Supporting several simultaneously active stores is a separate scope decision.

## Authentication design boundary

Remove both the hardcoded LelloAuth provider and the assumption that entering an API URL is sufficient to configure login. Keep user identity separate from provider-specific claims and map roles through server configuration. Identity must account for the issuing provider, especially when an operator migrates authentication.

Provider-independent OIDC is a proposed first implementation because the current clients and backend already use it. The authentication contract should explicitly identify the supported method and let clients reject unsupported methods clearly. Built-in accounts, directory authentication, and anonymous catalog access are open product choices, not implied commitments. Avoid building a new password service merely to remove a hardcoded issuer.

## Renaming and publication decisions

Choose the product name later; the public repository destination is also undecided. The release Android application ID is confirmed as `com.lelloman.store`. Keep its OAuth callback scheme, recovery companion identity, IPC contracts, and permissions when applying the product rename. Debug builds retain the `.debug` suffix. Preserve compatible release signing and increasing version codes, and test the upgrade of existing LelloStore installations before distributing renamed builds.

Apache License 2.0 is selected for original project code. Before publication, verify dependency and vendored-source redistribution and decide whether to publish reviewed history or start from a reviewed source snapshot. Preserve the existing repository and deployment history until the upstream extraction and operational migration are verified.

## End to end acceptance

Use two deployments with independent domains, databases, and authentication configurations. Both must run the same released image and accept the same Android client. Test manual and QR onboarding, web administration, publishing, installation, updates, server switching, disabled optional features, and interrupted operations. Exercise backup/restore and the migration of the existing LelloStore instance. The final deployment repository should configure all instance differences without application-code changes.
