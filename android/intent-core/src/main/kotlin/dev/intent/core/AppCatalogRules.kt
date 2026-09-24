package dev.intent.core

/** App-picker grouping. Values are the wire names used by the RN Choose Apps screen. */
enum class AppCategory(val wire: String) {
    SOCIAL("social"),
    VIDEO("video"),
    GAMES("games"),
    SHOPPING("shopping"),
    NEWS("news"),
    BROWSERS("browsers"),
    OTHER("other");

    companion object {
        // android.content.pm.ApplicationInfo.CATEGORY_* (stable API constants since 26).
        private const val ANDROID_GAME = 0
        private const val ANDROID_AUDIO = 1
        private const val ANDROID_VIDEO = 2
        private const val ANDROID_SOCIAL = 4
        private const val ANDROID_NEWS = 5

        /**
         * Many popular apps never declare `android:appCategory`, so well-known packages win over
         * the declared value; the declared value wins over "other".
         */
        fun of(packageName: String, androidCategory: Int?): AppCategory =
            KNOWN[packageName] ?: when (androidCategory) {
                ANDROID_SOCIAL -> SOCIAL
                ANDROID_VIDEO, ANDROID_AUDIO -> VIDEO
                ANDROID_GAME -> GAMES
                ANDROID_NEWS -> NEWS
                else -> OTHER
            }

        private val KNOWN: Map<String, AppCategory> = buildMap {
            listOf(
                "com.instagram.android", "com.instagram.barcelona", "com.facebook.katana", "com.facebook.lite",
                "com.twitter.android", "com.snapchat.android", "com.reddit.frontpage", "com.linkedin.android",
                "com.pinterest", "com.tumblr", "org.telegram.messenger", "com.whatsapp", "com.discord",
                "in.mohalla.sharechat", "com.bereal.ft",
            ).forEach { put(it, SOCIAL) }
            listOf(
                "com.google.android.youtube", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
                "com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient", "in.startv.hotstar",
                "com.mxtech.videoplayer.ad", "in.mohalla.video", "com.jio.media.ondemand", "tv.twitch.android.app",
                "com.spotify.music",
            ).forEach { put(it, VIDEO) }
            listOf(
                "in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping", "com.flipkart.android",
                "com.myntra.android", "com.meesho.supply", "com.ril.ajio", "com.shopsy.app", "com.einnovation.temu",
                "com.zhiliaoapp.shein", "com.contextlogic.wish",
            ).forEach { put(it, SHOPPING) }
            listOf(
                "com.nis.app", "com.eterno", "com.google.android.apps.magazines", "flipboard.app",
                "com.guardian", "com.nytimes.android",
            ).forEach { put(it, NEWS) }
            listOf(
                "com.android.chrome", "org.mozilla.firefox", "com.brave.browser", "com.opera.browser",
                "com.microsoft.emmx", "com.sec.android.app.sbrowser", "com.duckduckgo.mobile.android",
                "com.vivo.browser", "com.mi.globalbrowser",
            ).forEach { put(it, BROWSERS) }
        }
    }
}

/**
 * Banking, payment, authenticator and password apps. We recommend not monitoring them (PRD §40):
 * a checkpoint in front of a one-time code or a payment is friction with no upside.
 * Heuristic by design; the user can still add one after a warning.
 */
object SensitiveApps {
    private val EXACT = setOf(
        "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp",
        "in.amazon.mShop.android.shopping.pay", "com.dreamplug.androidapp", "com.mobikwik_new",
        "com.google.android.apps.walletnfcrel", "com.paypal.android.p2pmobile", "com.revolut.revolut",
        "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy",
        "com.x8bit.bitwarden", "com.lastpass.lpandroid", "com.onepassword.android", "com.agilebits.onepassword",
        "keepass2android.keepass2android", "com.dashlane", "com.samsung.android.spay",
        "com.sbi.lotusintouch", "com.sbi.SBIFreedomPlus", "com.csam.icici.bank.imobile", "com.snapwork.hdfc",
        "com.axis.mobile", "com.msf.kbank.mobile", "com.bankofbaroda.mconnect", "com.infrasofttech.indianbank",
    )

    private val KEYWORDS = listOf(
        "bank", "banking", "wallet", "upi", "pay", "authenticator", "password", "passwords", "vault",
        "2fa", "otp", "netbanking",
    )

    fun isSensitive(packageName: String, label: String): Boolean {
        if (packageName in EXACT) return true
        val words = (label.lowercase() + " " + packageName.lowercase().replace('.', ' ').replace('_', ' '))
            .split(' ', '-')
        return words.any { it in KEYWORDS }
    }
}
