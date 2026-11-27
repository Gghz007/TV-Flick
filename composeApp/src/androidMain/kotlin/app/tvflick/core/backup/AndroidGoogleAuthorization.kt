package app.tvflick.core.backup

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable actual fun rememberGoogleAuthorization(): GoogleAuthorization {
    val context = LocalContext.current
    val client = remember(context) { Identity.getAuthorizationClient(context) }
    val prefs = remember(context) { context.getSharedPreferences("tvflick-google-connection", 0) }
    val pending = remember { GooglePending() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val continuation = pending.resolution
        pending.resolution = null
        if (pending.continuation === continuation) pending.continuation = null
        if (continuation?.isActive == true) {
            try {
                if (result.resultCode != Activity.RESULT_OK) throw GoogleSignInCancelled()
                val token = requireNotNull(client.getAuthorizationResultFromIntent(result.data).accessToken)
                prefs.edit().putBoolean("connected", true).apply()
                pending.lastToken = token; continuation.resume(token)
            } catch (error: Exception) { continuation.resumeWithException(error) }
        }
    }
    return remember(client, launcher) { object : GoogleAuthorization {
        override val configured = true // Android client registration is matched by package + signing certificate.
        override suspend fun token(interactive: Boolean): String = suspendCancellableCoroutine { continuation ->
            if (!interactive && !prefs.getBoolean("connected", false)) {
                continuation.resumeWithException(GoogleSignInRequired()); return@suspendCancellableCoroutine
            }
            // A cancelled system dialog may still return later; never give its result to a newer login.
            if (pending.continuation != null || pending.resolution != null) {
                continuation.resumeWithException(GoogleSignInCancelled()); return@suspendCancellableCoroutine
            }
            pending.continuation = continuation
            continuation.invokeOnCancellation { if (pending.continuation === continuation) pending.continuation = null }
            client.authorize(AuthorizationRequest.builder().setRequestedScopes(GOOGLE_BACKUP_SCOPES.map(::Scope)).build())
                .addOnSuccessListener { result ->
                    if (!continuation.isActive) return@addOnSuccessListener
                    if (result.hasResolution()) {
                        if (interactive) {
                            try {
                                pending.resolution = continuation
                                launcher.launch(IntentSenderRequest.Builder(requireNotNull(result.pendingIntent).intentSender).build())
                            } catch (error: Exception) {
                                pending.resolution = null
                                pending.continuation = null
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                        } else { pending.continuation = null; continuation.resumeWithException(GoogleSignInRequired()) }
                    } else {
                        pending.continuation = null
                        val token = result.accessToken
                        if (token == null) continuation.resumeWithException(IllegalStateException("No Google access token"))
                        else {
                            prefs.edit().putBoolean("connected", true).apply()
                            pending.lastToken = token; continuation.resume(token)
                        }
                    }
                }.addOnFailureListener { error ->
                    if (pending.continuation === continuation) pending.continuation = null
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }
        override suspend fun invalidateToken(token: String) {
            suspendCancellableCoroutine<Unit> { continuation ->
                client.clearToken(ClearTokenRequest.builder().setToken(token).build())
                    .addOnSuccessListener { if (continuation.isActive) continuation.resume(Unit) }
                    .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
            }
            if (pending.lastToken == token) pending.lastToken = null
        }
        override suspend fun signOut() {
            pending.continuation?.cancel()
            pending.continuation = null
            prefs.edit().putBoolean("connected", false).apply()
            val token = pending.lastToken
            pending.lastToken = null
            token?.let { invalidateToken(it) }
        }
        override fun close() {
            pending.continuation?.cancel()
            pending.continuation = null
        }
    } }
}
private class GooglePending {
    var continuation: CancellableContinuation<String>? = null
    var resolution: CancellableContinuation<String>? = null
    var lastToken: String? = null
}
