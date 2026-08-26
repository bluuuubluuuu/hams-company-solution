package com.klk.hams.provisioning

/** Outcome of a /manual-claim (bind specific unit) call. */
sealed interface BindResult {
    data class Success(val uniqueId: String) : BindResult
    data object NotFound : BindResult                         // 404 - unknown unique_id
    data class FingerprintInUse(val ownedUnit: String?) : BindResult  // 409 - this device already owns ownedUnit
    data object AlreadyBound : BindResult                     // 409 - target unit owned by another device
    data object Draining : BindResult                         // 409 - former owner is flushing before release
    data object Unauthorized : BindResult      // 401
    data object AdminAuthFailed : BindResult   // 401 - supervisor code rejected
    data object AdminNotConfigured : BindResult // 503 - backend missing supervisor-code config
    data class Error(val reason: String) : BindResult
}

/** Outcome of a /release call. */
sealed interface ReleaseResult {
    data object Success : ReleaseResult
    data object NotFound : ReleaseResult
    data object Unauthorized : ReleaseResult
    data object AdminAuthFailed : ReleaseResult
    data object AdminNotConfigured : ReleaseResult
    data class Error(val reason: String) : ReleaseResult
}

/**
 * Outcome of an OTP request. The code itself is NEVER returned to the phone -
 * the backend mails it to the administrator, who reads it back to whoever holds
 * the handset. That separation is the point: possession of a phone must not be
 * enough to authorise a pairing.
 */
sealed interface OtpRequestResult {
    data object Sent : OtpRequestResult              // backend accepted the request
    data object NotConfigured : OtpRequestResult     // OTP_REQUEST_URL blank in this build
    data object Unauthorized : OtpRequestResult      // 401 - shared secret rejected
    data class Error(val reason: String) : OtpRequestResult
}

/** Outcome of a /verify (binding re-check) call. */
sealed interface VerifyResult {
    data object Bound : VerifyResult
    data object Released : VerifyResult
    data object BoundOther : VerifyResult
    data class Keep(val reason: String) : VerifyResult
}
