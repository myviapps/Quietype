package com.humanrewrite.keyboard.core

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import java.util.concurrent.Executors

/**
 * Boundary for Google Play Billing. Real Billing Library wiring: it can connect and launch a
 * purchase today, but it has nothing to sell until a subscription product with this ID exists in
 * Play Console (only the account owner can create that — see docs/play-launch-checklist.md).
 */
interface BillingGateway {
    /** [onMessage] reports why nothing launched (not connected, product missing) so the UI can tell the user. */
    fun startSubscriptionPurchase(activity: Activity, onMessage: (String) -> Unit = {})
    fun restorePurchases(onDone: (Boolean) -> Unit = {})
    fun close()
}

const val SUBSCRIPTION_PRODUCT_ID = "humanrewrite_monthly"

class PlayBillingGateway(
    context: Context,
    private val entitlementRepository: EntitlementRepository,
    private val integrityGateway: IntegrityGateway = IntegrityGateway(context)
) : BillingGateway, PurchasesUpdatedListener {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    init {
        connect()
        integrityGateway.prepare()
    }

    private fun connect(onReady: (() -> Unit)? = null) {
        if (client.isReady) {
            onReady?.invoke()
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) onReady?.invoke()
            }
            override fun onBillingServiceDisconnected() {
                // Reconnected lazily by the next purchase/restore call via connect().
            }
        })
    }

    override fun close() {
        client.endConnection()
        executor.shutdown()
    }

    override fun startSubscriptionPurchase(activity: Activity, onMessage: (String) -> Unit) {
        if (!client.isReady) {
            Log.w(TAG, "Billing not connected yet")
            connect()
            onMessage("Google Play isn't ready yet. Try again in a moment.")
            return
        }
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(SUBSCRIPTION_PRODUCT_ID)
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        client.queryProductDetailsAsync(params) { result, productDetailsList ->
            val details = productDetailsList.firstOrNull()
            val offerToken = details?.subscriptionOfferDetails?.firstOrNull()?.offerToken
            if (result.responseCode != BillingClient.BillingResponseCode.OK || details == null || offerToken == null) {
                Log.w(TAG, "No purchasable product yet: ${result.debugMessage}")
                onMessage("Subscriptions aren't available right now.")
                return@queryProductDetailsAsync
            }
            val offer = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .setOfferToken(offerToken)
                .build()
            val flowParams = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(offer)).build()
            client.launchBillingFlow(activity, flowParams)
        }
    }

    // [onDone] always runs on the main thread, so callers can touch views directly.
    override fun restorePurchases(onDone: (Boolean) -> Unit) {
        connect {
            val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
            client.queryPurchasesAsync(params) { _, purchases ->
                val active = purchases.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                if (active == null) {
                    onDone(false)
                    return@queryPurchasesAsync
                }
                handlePurchase(active, onDone)
            }
        }
    }

    // The subscription grants access only after our backend verifies the purchase token with
    // Google's Play Developer API (see EntitlementRepository) — Billing Library alone can't
    // confirm a purchase is genuine, since the token it hands back is client-reported.
    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) return
        purchases.forEach { handlePurchase(it) { } }
    }

    private fun handlePurchase(purchase: Purchase, onDone: (Boolean) -> Unit) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            onDone(false)
            return
        }
        // requestToken's callback lands on the main thread (Play Services Task default); hop to
        // the executor for the blocking HTTP verification call, then back for the caller.
        integrityGateway.requestToken(purchase.purchaseToken) { integrityToken ->
            if (executor.isShutdown) return@requestToken
            executor.execute {
                val verified = entitlementRepository.verifyPurchaseToken(purchase.purchaseToken, integrityToken)
                if (verified && !purchase.isAcknowledged) {
                    val ackParams = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                    client.acknowledgePurchase(ackParams) { }
                }
                mainHandler.post { onDone(verified) }
            }
        }
    }

    companion object {
        private const val TAG = "PlayBillingGateway"
    }
}
