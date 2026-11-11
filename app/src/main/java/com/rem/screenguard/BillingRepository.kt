package com.rem.screenguard

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import java.util.concurrent.CopyOnWriteArraySet

/**
 * v0.19.0 (play-iap): whether the Supporter Pack themes may be
 * selected. Play is the source of truth; Prefs.supporterCached is
 * only the cache of the last SUCCESSFUL Play query (see
 * BillingRepository). This gate is the single read path the UI uses.
 */
object SupporterGate {

    // Entitlement comes ONLY from the Play-verified cache: the flag
    // BillingRepository rewrites after each successful queryPurchases
    // round-trip. There is no other way in — no debug pref, no
    // build-time switch (the phase-1 emulator hook was stripped for
    // the release build).
    fun entitled(ctx: Context): Boolean = Prefs.supporterCached(ctx)
}

/**
 * v0.19.0 (play-iap): Play Billing for the Supporter Pack. The only
 * thing money buys in ScreenGuard is the right to select the five
 * supporter themes; protection is never sold and never reads this
 * file. GuardService does not import it, and any billing failure —
 * no Play Store, no network, a dead service connection — leaves the
 * guard exactly as it was: themes simply stay locked. (The same
 * structural isolation as the tripwire classifier: a broken extra
 * can never take the judge down.)
 *
 * Entitlement: queryPurchasesAsync runs on every app start (the UI
 * calls refresh()). A SUCCESSFUL query rewrites the cached flag
 * either way, so a refunded purchase un-entitles at the next
 * successful query. A FAILED query changes nothing — the last known
 * state stands and the next start re-verifies. Purchases are
 * acknowledged as soon as they are seen PURCHASED: an unacknowledged
 * non-consumable is auto-refunded by Google after three days.
 */
object BillingRepository : PurchasesUpdatedListener {

    const val PRODUCT_ID = "supporter_pack"

    private const val MAX_RETRIES = 6

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var appCtx: Context? = null
    @Volatile private var client: BillingClient? = null
    @Volatile private var connected = false
    @Volatile private var connectInFlight = false
    @Volatile private var retryAttempt = 0
    @Volatile private var retryScheduled = false
    @Volatile private var details: ProductDetails? = null

    /** True once billing setup has finished OK in this process. */
    @Volatile var available: Boolean = false
        private set

    /** True once a details query has answered OK in this process. */
    @Volatile var productChecked: Boolean = false
        private set

    /** Localised price of the Supporter Pack, once Play returns it. */
    @Volatile var priceText: String? = null
        private set

    private val stateListeners = CopyOnWriteArraySet<() -> Unit>()
    private val messageListeners = CopyOnWriteArraySet<(String) -> Unit>()

    fun addStateListener(l: () -> Unit) { stateListeners.add(l) }
    fun removeStateListener(l: () -> Unit) { stateListeners.remove(l) }
    fun addMessageListener(l: (String) -> Unit) { messageListeners.add(l) }
    fun removeMessageListener(l: (String) -> Unit) { messageListeners.remove(l) }

    private fun log(line: String) {
        appCtx?.let { DebugLog.log(it, line) }
    }

    private fun notifyState() {
        mainHandler.post { stateListeners.forEach { it() } }
    }

    private fun notifyMessage(msg: String) {
        mainHandler.post { messageListeners.forEach { it(msg) } }
    }

    /**
     * Entry point: called on app start (MainActivity) and by the
     * Themes screen. Connects if needed; once connected, re-queries
     * owned purchases (entitlement truth) and the product details
     * (price for the unlock button).
     */
    fun refresh(ctx: Context) {
        appCtx = ctx.applicationContext
        val c = client ?: buildClient(ctx.applicationContext).also { client = it }
        if (connected) {
            queryPurchases(c)
            if (details == null) queryDetails(c)
        } else if (!connectInFlight) {
            startConnection(c)
        }
    }

    private fun buildClient(ctx: Context): BillingClient =
        BillingClient.newBuilder(ctx)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .enableAutoServiceReconnection()
            .build()

    private fun startConnection(c: BillingClient) {
        connectInFlight = true
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connectInFlight = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    connected = true
                    available = true
                    retryAttempt = 0
                    log("BILLING connected")
                    queryPurchases(c)
                    queryDetails(c)
                } else {
                    connected = false
                    available = false
                    log("BILLING unavailable rc=${result.responseCode} ${result.debugMessage}")
                    scheduleRetry()
                }
                notifyState()
            }

            override fun onBillingServiceDisconnected() {
                connected = false
                log("BILLING disconnected")
                // enableAutoServiceReconnection covers most drops; a
                // manual backstop retry runs anyway.
                scheduleRetry()
                notifyState()
            }
        })
    }

    /**
     * Exponential backoff — 1, 2, 4, 8, 16, then a 30 s cap — with at
     * most MAX_RETRIES attempts per process run. The next app start
     * begins a fresh cycle, so a Play-less device is never spun
     * forever and protection never waits on any of this.
     */
    private fun scheduleRetry() {
        if (retryScheduled || retryAttempt >= MAX_RETRIES) return
        retryScheduled = true
        val delayMs = minOf(30_000L, 1000L shl retryAttempt)
        retryAttempt++
        mainHandler.postDelayed({
            retryScheduled = false
            val c = client ?: return@postDelayed
            if (!connected && !connectInFlight) startConnection(c)
        }, delayMs)
    }

    private fun queryDetails(c: BillingClient) {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        c.queryProductDetailsAsync(params) { result, queryResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                productChecked = true
                val pd = queryResult.productDetailsList
                    .firstOrNull { it.productId == PRODUCT_ID }
                details = pd
                priceText = pd?.oneTimePurchaseOfferDetailsList
                    ?.firstOrNull()?.formattedPrice
                log(
                    "BILLING details " + if (pd != null) {
                        "ok price=$priceText"
                    } else {
                        "product not returned by Play"
                    }
                )
            } else {
                log("BILLING details failed rc=${result.responseCode}")
            }
            notifyState()
        }
    }

    private fun queryPurchases(c: BillingClient) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        c.queryPurchasesAsync(params) { result, purchases ->
            val ctx = appCtx ?: return@queryPurchasesAsync
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                var entitled = false
                for (p in purchases) {
                    if (PRODUCT_ID in p.products &&
                        p.purchaseState == Purchase.PurchaseState.PURCHASED
                    ) {
                        entitled = true
                        acknowledgeIfNeeded(c, p)
                    }
                }
                // A successful query is the truth, both ways: a
                // purchase refunded since the last check un-entitles
                // here, at the next app start at the latest.
                Prefs.setSupporterCached(ctx, entitled)
                log("BILLING entitlement verified: $entitled")
            } else {
                // Failed query: the last known state stands; the
                // next app start re-verifies.
                log("BILLING entitlement query failed rc=${result.responseCode} — keeping last known state")
            }
            notifyState()
        }
    }

    private fun acknowledgeIfNeeded(c: BillingClient, p: Purchase) {
        if (p.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(p.purchaseToken)
            .build()
        c.acknowledgePurchase(params) { result ->
            log("BILLING acknowledge rc=${result.responseCode}")
        }
    }

    /**
     * Launch the purchase flow from the Themes screen. Play requires
     * an Activity. Outcomes arrive in onPurchasesUpdated and are
     * surfaced through the message listeners as plain sentences.
     */
    fun buy(activity: Activity) {
        refresh(activity)
        val c = client
        if (c == null || !connected) {
            notifyMessage("Purchases are unavailable right now.")
            return
        }
        val pd = details
        if (pd == null) {
            if (!productChecked) queryDetails(c)
            notifyMessage(
                if (productChecked) "The Supporter Pack isn't available yet."
                else "Loading the Supporter Pack — try again in a moment."
            )
            return
        }
        val offerToken = pd.oneTimePurchaseOfferDetailsList
            ?.firstOrNull()?.offerToken
        val paramsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(pd)
        if (offerToken != null) paramsBuilder.setOfferToken(offerToken)
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(paramsBuilder.build()))
            .build()
        val result = c.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            log("BILLING launch failed rc=${result.responseCode} ${result.debugMessage}")
            notifyMessage("Couldn't start the purchase (error ${result.responseCode}).")
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                var granted = false
                purchases?.forEach { p ->
                    if (PRODUCT_ID in p.products) {
                        when (p.purchaseState) {
                            Purchase.PurchaseState.PURCHASED -> {
                                client?.let { acknowledgeIfNeeded(it, p) }
                                granted = true
                            }
                            Purchase.PurchaseState.PENDING ->
                                notifyMessage(
                                    "Purchase is pending — themes unlock when it completes."
                                )
                        }
                    }
                }
                if (granted) {
                    appCtx?.let { Prefs.setSupporterCached(it, true) }
                    log("BILLING purchase granted")
                    notifyMessage("Supporter Pack unlocked — thank you.")
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED ->
                notifyMessage("Purchase cancelled.")
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                appCtx?.let { refresh(it) }
                notifyMessage("You already own the Supporter Pack.")
            }
            else -> {
                log("BILLING purchase failed rc=${result.responseCode} ${result.debugMessage}")
                notifyMessage("Purchase didn't go through (error ${result.responseCode}).")
            }
        }
        notifyState()
    }
}
