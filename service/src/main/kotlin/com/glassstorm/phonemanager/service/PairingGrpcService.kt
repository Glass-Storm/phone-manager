package com.glassstorm.phonemanager.service

import com.glassstorm.phonemanager.domain.context.Context
import com.glassstorm.phonemanager.domain.context.FromContext
import com.glassstorm.phonemanager.domain.dto.PairOutcome
import com.glassstorm.phonemanager.domain.service.PairingService
import com.glassstorm.phonemanager.service.security.AuthInterceptor
import ecosys.v1.HeartbeatRequest
import ecosys.v1.HeartbeatResponse
import ecosys.v1.PairRequest
import ecosys.v1.PairResponse
import ecosys.v1.PairingServiceGrpcKt
import io.grpc.Status
import io.grpc.StatusException

/**
 * The pairing/liveness RPC surface.
 *
 * Thin by design: it maps protobuf messages to [PairingService] calls and back,
 * and owns no security logic of its own. Token enforcement already happened in
 * [AuthInterceptor] before any of these bodies ran; `Pair` is the single method
 * the interceptor deliberately leaves open.
 */
class PairingGrpcService(
    ctx: Context,
) : PairingServiceGrpcKt.PairingServiceCoroutineImplBase() {
    private val pairing: PairingService = FromContext<PairingService>(ctx)

    /** The one unauthenticated RPC: redeem the open-window PIN for a token. */
    override suspend fun pair(request: PairRequest): PairResponse {
        val outcome =
            pairing.pair(
                pin = request.pin,
                deviceName = request.deviceName,
                role = request.role.name,
            )
        return when (outcome) {
            is PairOutcome.Ok ->
                PairResponse
                    .newBuilder()
                    .setOk(true)
                    .setToken(outcome.token)
                    .setDeviceId(outcome.deviceId)
                    .build()

            is PairOutcome.Rejected ->
                PairResponse
                    .newBuilder()
                    .setOk(false)
                    .setRejectReason(outcome.reason)
                    .build()
        }
    }

    /**
     * Liveness. The bearer token was already verified, and the authenticated
     * device id is on this call's Context — so the heartbeat is attributed to the
     * TOKEN's device, and a peer cannot heartbeat on behalf of another device by
     * filling in a different `device_id`.
     */
    override suspend fun heartbeat(request: HeartbeatRequest): HeartbeatResponse {
        val deviceId =
            AuthInterceptor.deviceIdKey.get()
                ?: throw StatusException(Status.UNAUTHENTICATED.withDescription("missing bearer token"))
        pairing.touchLastSeen(deviceId, System.currentTimeMillis())
        return HeartbeatResponse
            .newBuilder()
            .setOk(true)
            .setServerTimeMs(System.currentTimeMillis())
            .build()
    }
}
