# AdBlock module and rule delivery

## Implemented

`adblock-plugin` is registered in the application's real `PluginRegistry` on Windows and Android. The existing per-source Manager setting controls the request context passed to Source Plugin / Resolver; there is no global module switch. Initial requests and every source-page/API redirect are checked before sending. HLS manifests and primary segments remain protected from ad rules. The video decoder does not implement an AdBlock engine.

The bundled revision 1 is a conservative seed of four explicit ad-server hosts: `ad.doubleclick.net`, `fls.doubleclick.net`, `g.doubleclick.net`, `static.doubleclick.net`. The host classification was checked against [EasyList's ad-server source](https://github.com/easylist/easylist/blob/master/easylist/easylist_adservers.txt) on 2026-11-21. This is not an imported full EasyList, an ABP-syntax interpreter, or a promise to remove all advertisements. Current parsers do not load arbitrary page scripts, banners or popups in the first place. Embedded video advertisements are not removed.

Matching uses a parsed HTTP(S) host, exact domain/subdomain boundaries and case-sensitive URL paths. Query strings, fragments and user-info cannot masquerade as a blocked host/path. `supportedSourceIds` limit rules to local configured source identities; the Manager setting still independently disables all filtering for a source.

## Independent data update

At application startup, the module optionally loads UTF-8 `rules.json` from:

- Windows: `%LOCALAPPDATA%/TV Flick/modules/adblock/rules.json`.
- Android: the application's private `files/modules/adblock/rules.json`.

This is a developer/distributor deployment contract, not a new user-facing screen or an automatic remote download. Files are not created or modified by the reader. Deploy a complete file using an atomic replacement, then restart TV Flick. Rules are loaded asynchronously; bundled rules apply until validation finishes.

Example replacement (the example domain is deliberately non-production):

```json
{
  "schemaVersion": 1,
  "revision": 2,
  "blockedHosts": ["ads.example.test"],
  "blockedPaths": ["/advert/"],
  "sourceIds": []
}
```

The file replaces the entire ruleset. Empty `sourceIds` means all sources whose AdBlock setting is enabled. Limits: 256 KiB, 4096 DNS host suffixes, 128 path fragments, 128 source IDs. Unsupported schema/fields, malformed hosts, overly broad paths, invalid UTF-8 and non-increasing revisions are rejected before the active snapshot changes. Reinstalling the exact current snapshot is harmless. Invalid local input keeps the bundled/current rules and displays a short settings error. Deleting the optional file restores bundled rules at next launch. There is no persisted anti-rollback ledger: a restarted app compares the local revision to the bundled revision.

Rule documents cannot contain executable scripts, native libraries, regular expressions, URL rewrites or substitute media. Path rules are intentionally limited and can still require per-source tuning.

## Signed remote rules channel (implemented 2026-11-26)

The application can check one distributor-controlled HTTPS URL at startup, after restoring its last signed rules. Configure both Gradle properties when preparing the release:

```properties
tvflick.adblockUpdateUrl=https://your-release-host/path/rules.signed.json
tvflick.adblockPublicKey=BASE64_X509_RSA_PUBLIC_KEY
```

Both are currently empty: the channel is disabled and no update host is contacted. The public key is pinned in the built application; video source settings cannot replace it. No private release key has been generated or installed by Codex.

The signed envelope has exactly `schemaVersion: 1`, `payload` (Base64 of the complete UTF-8 rules JSON) and `signature` (Base64). Signature input is the UTF-8 prefix `TV-FLICK-ADBLOCK-RULES-V1\n` followed by the exact payload bytes, using SHA256withRSA and a 2048–8192-bit RSA key. The fixed prefix prevents reuse as another module's signed content. Schema compatibility is validated after signature verification. The envelope cannot specify an alternate algorithm, key or download URL.

The release helper runs with JDK 21 without external libraries:

```text
java tools/SignAdBlockRules.java rules.json /secure/location/private-key.pem rules.signed.json
```

It expects a PKCS#8 PEM RSA private key kept outside the repository/distribution, refuses to overwrite an existing output and prints only the public-key build property. The helper signs bytes; the client still validates the rule schema. Publish the envelope at the configured HTTPS endpoint with a direct 200 response. Redirects are intentionally not followed.

Requests have an 8-second total timeout and a 384-KiB envelope limit; the decoded rules limit remains 256 KiB. Network errors, invalid signatures, incompatible data and revisions at or below the active revision do not change the running or stored rules. A successful download is validated, saved atomically to `rules.signed.json` beside the optional local file, then activated. Windows and Android API 26+ use a flushed staging file and atomic replacement, without a non-atomic fallback. Unsupported filesystems retain the old file and decline the update.

On startup the cache is reverified with the pinned key before use. This preserves the accepted revision across ordinary restarts and rejects network replay of older packages. It is not tamper-proof storage against an actor who can replace/delete the app's local files. The optional developer `rules.json` remains supported: the higher valid revision wins. A corrupt signed cache reports a short settings error and suppresses remote installation for that run; restore/delete that cache before retrying. Key rotation currently requires an application release and cache migration/removal.

The signature API is available on both targets. The focused tests exercise real RSA signatures, restart/replay, tampering/wrong key, redirect/size limits, failed writes and atomic replacement. A loopback HTTPS test uses CIO, a temporary certificate trusted by that test client and the production file store. It verifies a download, activation and a fresh instance restoring persisted rules. Android compilation also passes. [Android Signature API](https://developer.android.com/reference/java/security/Signature)

## Independent module artifacts for Windows and Android

`./gradlew stagePluginArtifacts` produces separate Windows/JVM JARs, Android AARs and Gradle module metadata in `build/plugin-repository`. The five versions are pinned by `tvflick.plugins.<module>.version` in `gradle.properties`.

1. To stage one new module version, increment its property and run, for example, `./gradlew :adblock-plugin:publishAllPublicationsToPluginStagingRepository`. Keep the already staged dependency versions in the repository.
2. Build an application using the artifacts with `./gradlew :composeApp:compileKotlinDesktop :androidApp:assembleDebug "-Ptvflick.useStagedPlugins=true"`. The same flag applies when packaging. Normal source builds use project modules.
3. To roll back, pin the previous version and rebuild. Keep old artifacts alongside new ones. Changing the shared API may require coordinating consumer module versions, so validate the resulting dependency graph before distributing builds.

Verified on 27 November: all five modules produced both platform variants. The Windows and Android application builds resolved the separately staged artifacts. Both selected an alternate AdBlock coordinate when only its version was changed, then resolved the original after reverting the pin.

## Updating an installed Windows module

The Windows release can enable manual updates for its Video and AdBlock modules via `tvflick.desktopModulePublicKey`. This is a Base64 X.509 RSA public key embedded in the release. Keep the executable-module private key separate from the AdBlock rules key. Code updates execute with application privileges, so sign only reviewed JARs built for the same host interfaces and runtime dependencies.

Stage the release image and the replacement JAR. Use the exact shipped file name listed in the image's `app` directory as the target. Example developer command:

```powershell
.\gradlew.bat :composeApp:signDesktopModule `
  "-PdesktopModuleImage=C:\stage\TV Flick" `
  "-PdesktopModuleTarget=video-plugin-desktop-0.5.1-shipped-hash.jar" `
  "-PdesktopModuleReplacement=C:\stage\video-new.jar" `
  "-PdesktopModulePrivateKeyFile=C:\secure\windows-module-signing.pem" `
  "-PdesktopModuleOutput=C:\stage\video-update.tvmodule"
```

For each supported installed version, sign a transition from its exact JAR bytes. Close all instances and use the developer tool. It runs in a separate JVM, so the installed app's JARs remain available for replacement.

```powershell
.\gradlew.bat :composeApp:applyDesktopModule `
  "-PdesktopModuleAction=install" `
  "-PdesktopModuleImage=C:\installed\TV Flick" `
  "-PdesktopModulePackage=C:\stage\video-update.tvmodule"
```

A successful operation reports `Windows module install complete`. For rollback, set `desktopModuleAction=rollback` and use the same signed package. Keep the package until rollback is no longer needed. An install replaces exactly the JAR named in the package. It reads the signing key pinned in the installed core JAR and verifies the signature, the SHA-256 fingerprint of the host's nonreplaceable application JARs, the replacement hash and the expected installed hash. The original JAR remains in `.module-rollback`. Rollback verifies its hash before restoring it. A process lock blocks updates while the application is open. Atomic moves require a writable application folder and suitable filesystem.

## Release activation

Publish `rules.signed.json` at the canonical HTTPS URL once the release host is available. Embed the corresponding public key, then verify the published endpoint and startup. Production credentials and URLs remain release configuration.

On Android the signed rules update independently. Executable code ships with the signed APK/AAB. Google Play requires its update mechanism for installed executable code. Play Feature Delivery keeps module versions tied to the base release. [Google Play policy](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en) · [Play Feature Delivery](https://developer.android.com/guide/playcore/feature-delivery)
