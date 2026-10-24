package org.dylanneve1.aicorechat.data.search

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class WebSearchService(private val app: Application) {
    fun isOnline(): Boolean {
        return try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    )
        } catch (e: Exception) {
            false
        }
    }

    suspend fun search(query: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val doc = fetchSearchDocument(query)
            val resultElements = doc.select("div.result, div.web-result, div.results_links")
                .take(SEARCH_RESULT_LIMIT)

            if (resultElements.isEmpty()) return@runCatching "Brak wyników wyszukiwania"

            val entries = coroutineScope {
                resultElements.mapIndexed { index, element ->
                    async { processResultElement(index, element) }
                }.awaitAll()
            }.filterNotNull()

            if (entries.isEmpty()) "Brak wyników wyszukiwania" else entries.joinToString("\n\n")
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            "Brak wyników (błąd: ${error.message})"
        }
    }

    private fun fetchSearchDocument(query: String): Document {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$encoded&kl=pl-pl"
        return Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .header("Accept-Language", "pl-PL,pl;q=0.9,en-US;q=0.8")
            .timeout(SEARCH_TIMEOUT_MS)
            .followRedirects(true)
            .get()
    }

    private suspend fun processResultElement(index: Int, element: Element): String? {
        val titleEl = element.selectFirst("a.result__a, h2 a, a.result-link") ?: return null
        var title = titleEl.text().trim()
        val rawHref = titleEl.attr("href").trim()
        val realUrl = decodeDuckRedirect(rawHref)
        if (!realUrl.startsWith("http")) return null

        val snippetEl = element.selectFirst(".result__snippet, .result-snippet")
        var summary = snippetEl?.text()?.trim()?.let(::clampSummary).orEmpty()

        val host = hostname(realUrl)
        val snapshot = scrapePage(realUrl)
        if (snapshot != null) {
            if (title.isBlank() && !snapshot.title.isNullOrBlank()) {
                title = snapshot.title
            }
            val snapshotSummary = snapshot.summary
                ?.let { collapseWhitespace(it) }
                ?.takeIf { it.isNotBlank() }
                ?.let(::clampSummary)
            if ((summary.isBlank() || summary.length < MIN_SNIPPET_LENGTH) && snapshotSummary != null) {
                summary = snapshotSummary
            }
        }

        val content = snapshot?.content
        if (title.isBlank() && summary.isBlank() && content.isNullOrBlank()) return null

        val header = buildHeader(host, title, snapshot?.published)

        return buildString {
            append("${index + 1}. ")
            append(header)
            if (summary.isNotBlank()) {
                append("\n- Podsumowanie: ")
                append(summary)
            }
            if (!content.isNullOrBlank()) {
                append("\n- Treść:\n")
                append(content)
            }
        }.trimEnd()
    }

    private fun buildHeader(host: String, title: String, dateIso: String?): String {
        return buildString {
            if (!dateIso.isNullOrBlank()) {
                append(dateIso)
                append(" — ")
            }
            append(host)
            if (title.isNotBlank()) {
                append(" — ")
                append(title)
            }
        }.ifBlank { host }
    }

    private fun decodeDuckRedirect(href: String): String {
        return try {
            val h = when {
                href.startsWith("//") -> "https:$href"
                href.startsWith("/") -> "https://duckduckgo.com$href"
                else -> href
            }
            if (h.startsWith("http")) {
                val idx = h.indexOf("uddg=")
                if (idx != -1) {
                    val start = idx + 5
                    val end = h.indexOf('&', start).let { if (it == -1) h.length else it }
                    val enc = h.substring(start, end)
                    URLDecoder.decode(enc, "UTF-8")
                } else {
                    h
                }
            } else {
                h
            }
        } catch (e: Exception) {
            href
        }
    }

    private fun hostname(url: String): String {
        return try {
            val u = URL(if (url.startsWith("http")) url else "https://$url")
            u.host.removePrefix("www.")
        } catch (e: Exception) {
            url
        }
    }

    private fun parseDateToIso(dateRaw: String): String? {
        val candidates = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
        )
        for (p in candidates) {
            try {
                val fmt = SimpleDateFormat(p, Locale.US)
                fmt.isLenient = true
                if (p.contains("'Z'")) fmt.timeZone = TimeZone.getTimeZone("UTC")
                val d = fmt.parse(dateRaw)
                if (d != null) {
                    val out = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    out.timeZone = TimeZone.getTimeZone("UTC")
                    return out.format(d)
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun scrapePage(targetUrl: String): PageSnapshot? {
        val document = fetchDocument(targetUrl) ?: return null
        val title = document.title().trim().takeIf { it.isNotEmpty() }
        val summary = sequenceOf(
            document.selectFirst("meta[name=description]")?.attr("content"),
            document.selectFirst("meta[property=og:description]")?.attr("content"),
            document.selectFirst("meta[name=twitter:description]")?.attr("content"),
        ).mapNotNull { it?.trim()?.takeIf { value -> value.isNotEmpty() } }
            .firstOrNull()
        val published = extractPublishedDate(document)
        val content = extractReadableContent(document)?.let { clampContent(it) }
        return if (title == null && summary == null && published == null && content == null) {
            null
        } else {
            PageSnapshot(title, summary, published, content)
        }
    }

    private fun fetchDocument(targetUrl: String): Document? {
        return try {
            Jsoup.connect(targetUrl)
                .userAgent(USER_AGENT)
                .header("Accept-Language", "pl-PL,pl;q=0.9")
                .timeout(PAGE_TIMEOUT_MS)
                .followRedirects(true)
                .ignoreContentType(true)
                .maxBodySize(0)
                .get()
        } catch (e: Exception) {
            null
        }
    }

    private fun extractPublishedDate(doc: Document): String? {
        val candidates = mutableListOf<String>()
        val selectors = listOf(
            "meta[property=article:published_time]",
            "meta[name=pubdate]",
            "meta[name=date]",
            "meta[itemprop=datePublished]",
            "meta[property=og:updated_time]",
        )
        for (selector in selectors) {
            doc.select(selector).forEach { element ->
                val value = element.attr("content").trim()
                if (value.isNotEmpty()) candidates.add(value)
            }
        }
        doc.select("time[datetime]").forEach { element ->
            val value = element.attr("datetime").trim()
            if (value.isNotEmpty()) candidates.add(value)
        }
        return candidates.asSequence()
            .mapNotNull { parseDateToIso(it) }
            .firstOrNull()
    }

    private fun extractReadableContent(doc: Document): String? {
        val selectors = listOf(
            "article",
            "main",
            "[role=main]",
            "div[itemprop=articleBody]",
            "div[class*=article-body]",
            "div[class*=post-content]",
            "#content",
        )
        for (selector in selectors) {
            val element = doc.selectFirst(selector) ?: continue
            val text = elementParagraphText(element)
            if (!text.isNullOrBlank() && text.length >= MIN_CONTENT_CHARS) {
                return text
            }
        }
        val paragraphs = doc.select("p")
            .mapNotNull { p ->
                val text = collapseWhitespace(p.text())
                if (text.length >= MIN_PARAGRAPH_CHARS) text else null
            }
            .take(MAX_PARAGRAPHS)
        return sanitizeParagraphs(paragraphs)
    }

    private fun elementParagraphText(element: Element): String? {
        val paragraphs = element.select("p")
            .mapNotNull { p ->
                val text = collapseWhitespace(p.text())
                if (text.length >= MIN_PARAGRAPH_CHARS) text else null
            }
            .take(MAX_PARAGRAPHS)
        val joined = sanitizeParagraphs(paragraphs)
        if (!joined.isNullOrBlank()) return joined
        val text = collapseWhitespace(element.text())
        return if (text.length >= MIN_CONTENT_CHARS) text else null
    }

    private fun sanitizeParagraphs(paragraphs: List<String>): String? {
        if (paragraphs.isEmpty()) return null
        val cleaned = paragraphs.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return null
        return cleaned.joinToString("\n\n")
    }

    private fun clampSummary(text: String, maxChars: Int = SUMMARY_CHAR_LIMIT): String {
        if (text.length <= maxChars) return text
        val cut = text.substring(0, maxChars)
        val split = cut.lastIndexOf(' ')
        val candidate = if (split >= maxChars / 2) cut.substring(0, split) else cut
        val normalized = candidate.trimEnd()
        val base = if (normalized.isNotEmpty()) normalized else cut.trimEnd()
        return if (base.endsWith("...")) base else "$base..."
    }

    private fun clampContent(text: String, maxChars: Int = PAGE_CONTENT_LIMIT): String {
        if (text.length <= maxChars) return text
        val cut = text.substring(0, maxChars)
        val candidate = sequenceOf(
            cut.lastIndexOf(". "),
            cut.lastIndexOf('\n'),
        ).filter { it >= maxChars / 2 }.maxOrNull() ?: cut.length
        val safe = cut.substring(0, candidate).trimEnd()
        val fallback = if (safe.isNotEmpty()) safe else cut.trimEnd()
        return "$fallback..."
    }

    private fun collapseWhitespace(value: String): String {
        return MULTISPACE_REGEX.replace(value.replace('\u00A0', ' '), " ").trim()
    }

    private data class PageSnapshot(
        val title: String?,
        val summary: String?,
        val published: String?,
        val content: String?,
    )

    private companion object {
        private const val SEARCH_RESULT_LIMIT = 4
        private const val SEARCH_TIMEOUT_MS = 8000
        private const val PAGE_TIMEOUT_MS = 8000
        private const val PAGE_CONTENT_LIMIT = 1500
        private const val MIN_PARAGRAPH_CHARS = 40
        private const val MIN_CONTENT_CHARS = 150
        private const val MAX_PARAGRAPHS = 15
        private const val MIN_SNIPPET_LENGTH = 80
        private const val SUMMARY_CHAR_LIMIT = 300
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 10 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private val MULTISPACE_REGEX = Regex("\\s+")
    }
}
