# Google sign-in and Drive backups

Status (27 November 2026): The TV Flick Google Cloud project, OAuth branding, and Windows Desktop and Android debug clients exist. Desktop JSON is installed in local application data. Google Drive API is enabled. Declared scopes are `openid`, `userinfo.email`, `userinfo.profile` and `drive.appdata`. The owner account is the sole test user. Windows and Android debug live login, token refresh and synthetic backup transfer passed. Android release signing and OAuth registration remain release tasks. Local export/import work offline.

The last confirmed publishing status is **Testing / External**. A public user needs the project to be **In production**. Open [Google Auth Platform](https://console.cloud.google.com/auth/overview), select **Audience**, and use **Publish app** when available. If the console requires additional Branding fields or verification, follow the shown requirement. [Google's audience and publishing documentation](https://support.google.com/cloud/answer/15549945).

Google lists `drive.appdata` as non-sensitive. It grants TV Flick access to its own backup data in the Google Drive of the account that consents. Keep this scope instead of requesting full Drive access. Publishing the OAuth audience does not give the app access to any account before that account authorizes it. [Google Drive scope list](https://developers.google.com/workspace/drive/api/guides/api-specific-auth).

## Google Cloud project

1. Open [Google Cloud Console](https://console.cloud.google.com/) under the account that will own TV Flick.
2. Create/select a project for TV Flick. Enable **Google Drive API** in APIs & Services → Library.
3. In Google Auth Platform, complete Branding (TV Flick, support email, developer contact). Configure Audience as External if the application is not limited to a Workspace organization. While Testing, add the Google accounts used for testing to Test users.
4. Data Access: request only `openid`, `email`, `profile` and `https://www.googleapis.com/auth/drive.appdata`. The app uses Drive's private appDataFolder; it does not need access to all user Drive documents.

## Windows

1. Clients → Create client → **Desktop app**. Download the OAuth client JSON.
2. Save that file as `%LOCALAPPDATA%\TV Flick\google-oauth.json`. Keep the downloaded `installed` object intact.
3. An alternate file can be selected using `TVFLICK_GOOGLE_OAUTH_CONFIG`. The app also supports a packaged `/google-oauth.json` resource when preparing a distribution for others. No client is currently bundled.
4. Restart TV Flick → Profile → Settings → Profile. Click the account icon beside Local profile. The system browser opens Google's consent screen; return to TV Flick after approving.

Windows uses Authorization Code + S256 PKCE, a random state value and an ephemeral `127.0.0.1` loopback listener. Refresh tokens are saved in `google-session.dat`, protected with Windows DPAPI for the current user. They are never part of `.tvbackup`, JSON, CSV or PDF. Signing out clears the local Google session and disables AutoExport; it does not delete Google Drive backups.

## Android

Create an **Android** OAuth client in the same project:

- Package: `app.tvflick`.
- Use `:androidApp:signingReport` to read the SHA-1 of the debug signing certificate. A release/store build needs its own client for the actual release or Play App Signing certificate.

The debug client was registered on 26 November 2026. Release signing and OAuth registration for the release certificate are pending.

Keep the local TMDB configuration and OAuth client documents outside the source repository. Supply build-time application configuration separately for the release artifacts. Google account passwords, sign-in codes, session files, refresh tokens and developer backups are excluded from both GitHub and the app packages.

The Android implementation uses Google Play services AuthorizationClient. Registration is matched by package and signing certificate; no Desktop client JSON is copied into Android. A device with Google Play services is required for this Google sign-in path.

## Verification after registration

Windows live transport verification on 26 November passed using the application's real authorization and backup implementations. `build-google-live.log` confirms consent, profile, Drive listing and session reuse. `build-google-roundtrip.log` confirms forced token refresh, byte-identical upload/download, restoration into an in-memory database and removal of the UUID-labelled synthetic backup.

On 27 November, Android debug sign-in via the app's Profile screen returned the test profile and Drive listing. The Android probe reused the session after relaunch, uploaded and downloaded a synthetic in-memory library, and separately verified token renewal and local sign-out. The Windows probe downloaded and restored that Android backup into an in-memory SQLite database, checked the test title and removed only the synthetic file. See `build/android-google-device.log`, `build/android-google-lifecycle-device.log` and `build-google-android-to-windows.log`.

Developer-only tasks (not part of automated test runs): `:composeApp:verifyGoogleLive` prepares an interactive authorization link in `build/google-verification/authorization-url.txt`; the owner approves consent. `:composeApp:verifyGoogleLive -PgoogleLiveRoundtrip=true` uses the saved session and creates/removes only its own synthetic test backup. No tokens, profile data or backup filenames are printed. Both tasks are excluded from packaged application code.

1. Sign in and check the account name/email. Export a small cloud backup with Image off.
2. Open SelectRestore and confirm that the new file appears. RESTORE previews counts and requires confirmation before replacing data. Try restoring into a test library, not the only copy of your real library.
3. Enable AutoExport and choose 1 day, 1 week or 30 days. Only application startup checks whether the last successful backup is old enough. Closing TV Flick means no automatic backup happens; there is no OS task/service. A failed upload does not advance the date and can retry on a later start.
4. Sign out and confirm AutoExport is off. Switching accounts and restoring a local backup also require AutoExport to be enabled again explicitly.

References: [Desktop OAuth](https://developers.google.com/identity/protocols/oauth2/native-app), [Android authorization](https://developer.android.com/identity/authorization), [Drive application data](https://developers.google.com/workspace/drive/api/guides/appdata).
