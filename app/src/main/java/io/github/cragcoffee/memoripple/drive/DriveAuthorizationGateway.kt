package io.github.cragcoffee.memoripple.drive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface DriveAuthorizationResult {
    data class Authorized(val accessToken: String) : DriveAuthorizationResult
    data class ResolutionRequired(val pendingIntent: PendingIntent?) : DriveAuthorizationResult
    data object Failed : DriveAuthorizationResult
}

interface DriveAuthorizationGateway {
    suspend fun authorize(): DriveAuthorizationResult
    fun resultFromIntent(data: Intent?): DriveAuthorizationResult
    suspend fun clearToken(accessToken: String)
}

class GoogleDriveAuthorizationGateway(
    context: Context,
    private val client: AuthorizationClient = Identity.getAuthorizationClient(context),
) : DriveAuthorizationGateway {
    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(Scopes.DRIVE_APPFOLDER)))
        .build()

    override suspend fun authorize(): DriveAuthorizationResult = try {
        client.authorize(request).awaitResult().toDriveResult()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        DriveAuthorizationResult.Failed
    }

    override fun resultFromIntent(data: Intent?): DriveAuthorizationResult = try {
        if (data == null) DriveAuthorizationResult.Failed
        else client.getAuthorizationResultFromIntent(data).toDriveResult()
    } catch (_: Exception) {
        DriveAuthorizationResult.Failed
    }

    override suspend fun clearToken(accessToken: String) {
        try {
            client.clearToken(
                ClearTokenRequest.builder().setToken(accessToken).build(),
            ).awaitResult()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A fresh authorize call remains the source of truth even if cache clearing fails.
        }
    }

    private fun AuthorizationResult.toDriveResult(): DriveAuthorizationResult {
        val resolution = pendingIntent
        if (hasResolution() && resolution != null) {
            return DriveAuthorizationResult.ResolutionRequired(resolution)
        }
        val token = accessToken
        return if (token.isNullOrBlank()) DriveAuthorizationResult.Failed
        else DriveAuthorizationResult.Authorized(token)
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
    // A failed Task is a failure, not a cancellation: cancelling the continuation here would
    // dress every GMS error as CancellationException, which the callers deliberately rethrow —
    // so no failure would ever reach their Failed mapping, and the operation would just
    // silently die. Only the canceled listener cancels.
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
    addOnCanceledListener { if (continuation.isActive) continuation.cancel() }
}
