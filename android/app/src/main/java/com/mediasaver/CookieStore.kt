package com.mediasaver

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Holds the user's own site logins as a Netscape cookies.txt for yt-dlp.
 *
 * Sites gate content behind a session rather than behind anything technical:
 * X refuses sensitive posts to any logged-out client, and Vimeo and Reddit
 * refuse everything. yt-dlp reads a cookies file with --cookies, so signing in
 * once inside the app makes those readable.
 *
 * The file lives in app-private storage. It holds a live session, so it is
 * treated as a credential: never logged, and wiped on sign out.
 */
object CookieStore {

    /** A site the user can sign into. */
    data class Site(
        val key: String,
        val label: String,
        val loginUrl: String,
        /**
         * Written as the cookie domain, with the include-subdomains flag set.
         * The leading dot matters: yt-dlp looks X's session up on api.x.com, so
         * a cookie scoped to plain "x.com" would not be found.
         */
        val cookieDomain: String,
        /** URLs to read cookies for; some sites split them across hosts. */
        val readFrom: List<String>,
        /** Cookie that proves the sign-in actually completed. */
        val sessionCookie: String,
    )

    val SITES = listOf(
        Site("x", "X (Twitter)", "https://x.com/login", ".x.com",
            listOf("https://x.com", "https://api.x.com"), "auth_token"),
        Site("instagram", "Instagram", "https://www.instagram.com/accounts/login/", ".instagram.com",
            listOf("https://www.instagram.com"), "sessionid"),
        Site("reddit", "Reddit", "https://www.reddit.com/login", ".reddit.com",
            listOf("https://www.reddit.com"), "reddit_session"),
        Site("vimeo", "Vimeo", "https://vimeo.com/log_in", ".vimeo.com",
            listOf("https://vimeo.com"), "vimeo"),
    )

    private const val FILE_NAME = "cookies.txt"
    private const val PREFS = "cookies"
    private const val KEY_SITES = "signed_in"
    private const val HEADER = "# Netscape HTTP Cookie File"

    private val _signedIn = MutableStateFlow<Set<String>>(emptySet())
    val signedIn: StateFlow<Set<String>> = _signedIn.asStateFlow()

    fun file(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

    fun hasCookies(context: Context): Boolean = file(context).let { it.isFile && it.length() > 0 }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        _signedIn.value = sitesInFile(context)
    }

    /**
     * Which sites the cookies file actually carries a session for. Derived from
     * the file rather than from a stored flag, so the UI cannot claim a sign-in
     * that is not really there.
     */
    fun sitesInFile(context: Context): Set<String> {
        val target = file(context)
        if (!target.isFile) return emptySet()
        val lines = runCatching { target.readLines() }.getOrDefault(emptyList())
        val present = mutableSetOf<String>()
        for (site in SITES) {
            val base = site.cookieDomain.removePrefix(".")
            val hasSession = lines.any { line ->
                val parts = line.split('\t')
                if (parts.size < 7 || parts[5] != site.sessionCookie) return@any false
                // Exporters disagree about the leading dot: some write ".x.com"
                // with the subdomains flag, others a host-only "x.com". Both are
                // valid and yt-dlp accepts either, so match on the bare domain
                // rather than on one exact spelling.
                val domain = parts[0].removePrefix(".")
                domain == base || domain.endsWith(".$base")
            }
            if (hasSession) present += site.key
        }
        return present
    }

    /**
     * Read whatever the WebView collected for this site and merge it into the
     * cookies file, replacing any previous entries for the same domain.
     */
    fun capture(context: Context, site: Site): Boolean {
        val manager = CookieManager.getInstance()
        manager.flush()

        val pairs = LinkedHashMap<String, String>()
        for (url in site.readFrom) {
            val header = manager.getCookie(url) ?: continue
            header.split(';').forEach { part ->
                val name = part.substringBefore('=').trim()
                val value = part.substringAfter('=', "").trim()
                if (name.isNotEmpty()) pairs[name] = value
            }
        }
        // Any site sets throwaway cookies to a mere visitor. Only the session
        // cookie proves the login completed - without this check a sign-in that
        // was blocked (X's bot detection does this) would still report success.
        if (!pairs.containsKey(site.sessionCookie)) return false

        val expiry = (System.currentTimeMillis() / 1000) + 365L * 24 * 60 * 60
        val newLines = pairs.map { (name, value) ->
            // domain, include-subdomains, path, secure, expiry, name, value
            listOf(site.cookieDomain, "TRUE", "/", "TRUE", expiry.toString(), name, value)
                .joinToString("\t")
        }

        val target = file(context)
        val kept = if (target.isFile) {
            target.readLines().filter { line ->
                line.isNotBlank() && !line.startsWith("#") &&
                    line.substringBefore('\t') != site.cookieDomain
            }
        } else emptyList()

        target.writeText((listOf(HEADER) + kept + newLines).joinToString("\n") + "\n")
        // Not world-readable: this is a live session.
        runCatching { target.setReadable(false, false); target.setReadable(true, true) }

        _signedIn.value = sitesInFile(context)
        return true
    }

    /** True once the site's session cookie is present, i.e. the login worked. */
    fun isSignedIn(site: Site): Boolean {
        val manager = CookieManager.getInstance()
        return site.readFrom.any { url ->
            manager.getCookie(url)?.split(';')?.any {
                it.substringBefore('=').trim() == site.sessionCookie
            } == true
        }
    }

    /**
     * Take a cookies.txt exported from a desktop browser. A fallback for sites
     * whose bot detection refuses to render a login inside a WebView - X's
     * fingerprinting script does exactly that.
     *
     * @return how many cookie lines were imported, or null if the file was not
     *   in Netscape format.
     */
    data class ImportResult(val cookies: Int, val sites: Set<String>)

    fun importFrom(context: Context, uri: Uri): ImportResult? {
        val text = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().decodeToString()
        } ?: return null

        val lines = text.lines()
        val cookieLines = lines.filter { line ->
            line.isNotBlank() && !line.trimStart().startsWith("#") && line.count { it == '\t' } >= 6
        }
        if (cookieLines.isEmpty()) return null

        file(context).writeText((listOf(HEADER) + cookieLines).joinToString("\n") + "\n")
        runCatching { file(context).setReadable(false, false); file(context).setReadable(true, true) }

        val domains = cookieLines.mapNotNull { it.substringBefore('\t').removePrefix(".").ifBlank { null } }
            .map { it.substringBefore('.') }
            .toSet()
        val sites = sitesInFile(context)
        _signedIn.value = sites
        return ImportResult(cookieLines.size, sites)
    }

    fun signOut(context: Context) {
        file(context).delete()
        prefs(context).edit().remove(KEY_SITES).apply()
        _signedIn.value = emptySet()
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
    }
}
