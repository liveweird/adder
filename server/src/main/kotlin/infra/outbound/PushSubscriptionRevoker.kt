package ch.nokillswit.infra.outbound

import io.ktor.util.AttributeKey

/** Infrastructure seam used by logout without importing the top-level notifications feature. */
fun interface PushSubscriptionRevoker {
    suspend fun revoke(userId: UInt, subscriptionId: UInt)
}

val PushSubscriptionRevokerKey = AttributeKey<PushSubscriptionRevoker>("PushSubscriptionRevoker")
