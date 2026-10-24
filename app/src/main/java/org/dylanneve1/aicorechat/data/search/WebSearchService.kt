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

/**
 * Zaawansowany serwis wyszukiwania WWW z kaskadą wyszukiwarek (Brave, DuckDuckGo, Yahoo)
 * oraz automatycznym wchodzeniem na znalezione strony i wyciąganiem ich pełnej treści (Web Scraping).
 */
class WebSearchService(private val app: Application) {

    fun isOnline(): Boolean {
        return try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return true
            val caps = cm.getNetworkCapabilities(network) ?: return true
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true
        }
    }

    suspend fun search(rawQuery: String): String = withContext(Dispatchers.IO) {
        try {
            val query = cleanQueryForWeb(rawQuery)

            // KROK 1: Pobieramy listę wyników WWW z kaskady wyszukiwarek (odporność na blokady)
            val searchHits = fetchWebSearchHits(query).ifEmpty {
                if (query != rawQuery) fetchWebSearchHits(rawQuery) else emptyList()
            }

            if (searchHits.isEmpty()) {
                return@withContext "Nie znaleziono stron WWW dla zapytania: $rawQuery"
            }

            // KROK 2: Równolegle wchodzimy na znalezione strony WWW i wyciągamy z nich treść
            val enrichedResults = coroutineScope {
                searchHits.take(MAX_RESULTS_TO_SCRAPE).mapIndexed { index, hit ->
                    async { scrapeAndFormatHit(index, hit) }
                }.awaitAll()
            }.filterNotNull()

            if (enrichedResults.isEmpty()) {
                // Jeśli strony zablokowały scraping, zwracamy same opisy z wyszukiwarki
                searchHits.take(MAX_RESULTS_TO_SCRAPE).mapIndexed { i, hit ->
                    "${i + 1}. ${hit.title} (${hostname(hit.url)})\nPodsumowanie: ${hit.snippet}"
                }.joinToString("\n\n")
            } else {
                enrichedResults.joinToString("\n\n")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            "Błąd podczas przeszukiwania stron WWW: ${e.message}"
        }
    }

    /**
     * Kaskada 3 pełnych wyszukiwarek WWW:
     * 1. DuckDuckGo HTML/Lite (z nagłówkami Chrome omijającymi blokadę botów)
     * 2. Brave Search HTML (niezależny indeks WWW, świetny jako alternatywa)
     * 3. Yahoo Search PL (indeks Bing/Yahoo - bardzo odporny na blokady z telefonów)
     */
    private fun fetchWebSearchHits(query: String): List<SearchHit> {
        val hits = mutableListOf<SearchHit>()

        // 1. DuckDuckGo (POST z pełnymi nagłówkami przeglądarki)
        runCatching { hits.addAll(searchDuckDuckGo(query)) }

        // 2. Brave Search (jeśli DDG zostało zablokowane lub zwróciło mało wyników)
        if (hits.size < 3) {
            runCatching { hits.addAll(searchBraveWeb(query)) }
        }

        // 3. Yahoo Search Polska (oparte na indeksie Bing WWW)
        if (hits.size < 3) {
            runCatching { hits.addAll(searchYahooWeb(query)) }
        }

        return hits
            .filter { it.url.startsWith("http") && it.title.isNotBlank() }
            .distinctBy { it.url }
    }

    private fun searchDuckDuckGo(query: String): List<SearchHit> {
        val doc = Jsoup.connect("https://html.duckduckgo.com/html/")
            .userAgent(USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "pl-PL,pl;q=0.9,en-US;q=0.8")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Origin", "https://html.duckduckgo.com")
            .header("Referer", "https://html.duckduckgo.com/")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "same-origin")
            .data("q", query)
            .data("kl", "pl-pl")
            .timeout(SEARCH_TIMEOUT_MS)
            .post()

        return doc.select("div.result, div.web-result").mapNotNull { el ->
            val linkEl = el.selectFirst("a.result__a") ?: return@mapNotNull null
            val title = linkEl.text().trim()
            val realUrl = decodeRedirectUrl(linkEl.attr("href"))
            val snippet = el.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
            if (title.isBlank() || !realUrl.startsWith("http")) null
            else SearchHit(title, realUrl, snippet)
        }
    }

    private fun searchBraveWeb(query: String): List<SearchHit> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://search.brave.com/search?q=$encoded&source=web"
        val doc = Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "pl-PL,pl;q=0.9,en-US;q=0.8")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .timeout(SEARCH_TIMEOUT_MS)
            .get()

        return doc.select("div.snippet[data-type=web], div.snippet").mapNotNull { el ->
            val linkEl = el.selectFirst("a[href^=http]") ?: return@mapNotNull null
            val href = linkEl.attr("href").trim()
            if (href.contains("brave.com")) return@mapNotNull null
            val title = el.selectFirst(".title, .snippet-title, h2, h3")?.text()?.trim()
                ?: linkEl.text().trim()
            val snippet = el.selectFirst(".snippet-description, . snippet-content, .description")
                ?.text()?.trim().orEmpty()
            if (title.isBlank()) null else SearchHit(title, href, snippet)
        }
    }

    private fun searchYahooWeb(query: String): List<SearchHit> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://pl.search.yahoo.com/search?p=$encoded&ei=UTF-8"
        val doc = Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .header("Accept-Language", "pl-PL,pl;q=0.9")
            .timeout(SEARCH_TIMEOUT_MS)
            .get()

        return doc.select("div.dd.algo, div.algo-sr").mapNotNull { el ->
            val linkEl = el.selectFirst("h3 a, a[href^=http]") ?: return@mapNotNull null
            val title = el.selectFirst("h3")?.text()?.trim() ?: linkEl.text().trim()
            val realUrl = decodeYahooRedirect(linkEl.attr("href"))
            val snippet = el.selectFirst("div.compText, p")?.text()?.trim().orEmpty()
            if (title.isBlank() || !realUrl.startsWith("http")) null
            else SearchHit(title, realUrl, snippet)
        }
    }

    /**
     * Wchodzi bezpośrednio na znalezioną stronę WWW, pobiera jej treść,
     * wyciąga najważniejsze akapity/dane i łączy z opisem z wyszukiwarki.
     */
    private fun scrapeAndFormatHit(index: Int, hit: SearchHit): String? {
        val host = hostname(hit.url)
        val pageContent = scrapeWebPageContent(hit.url)

        if (hit.title.isBlank() && hit.snippet.isBlank() && pageContent.isNullOrBlank()) {
            return null
        }

        return buildString {
            append("[Strona ${index + 1}: $host — ${hit.title}]\n")
            if (hit.snippet.isNotBlank()) {
                append("Opis z wyszukiwarki: ${hit.snippet}\n")
            }
            if (!pageContent.isNullOrBlank()) {
                append("Wyciągnięta treść strony:\n$pageContent")
            }
        }.trim()
    }

    private fun scrapeWebPageContent(targetUrl: String): String? {
        return try {
            val doc: Document = Jsoup.connect(targetUrl)
                .userAgent(USER_AGENT)
                .header("Accept-Language", "pl-PL,pl;q=0.9,en-US;q=0.8")
                .timeout(PAGE_TIMEOUT_MS)
                .followRedirects(true)
                .ignoreContentType(true)
                .maxBodySize(1024 * 1024) // max 1 MB HTML
                .get()

            // Usuwamy zbędne elementy strony (menu, stopki, skrypty, reklamy, ciasteczka)
            doc.select(
                "script, style, nav, footer, header, aside, form, noscript, iframe, svg, " +
                    "[class*=cookie], [id*=cookie], [class*=popup], [class*=banner], [class*=advert]",
            ).remove()

            val metaDesc = sequenceOf(
                doc.selectFirst("meta[name=description]")?.attr("content"),
                doc.selectFirst("meta[property=og:description]")?.attr("content"),
            ).mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }.firstOrNull()

            val mainBody = extractMainText(doc)

            val combined = buildString {
                if (!metaDesc.isNullOrBlank()) {
                    append(metaDesc)
                    append("\n")
                }
                if (!mainBody.isNullOrBlank()) {
                    append(mainBody)
                }
            }.trim()

            if (combined.isBlank()) null else clampText(combined, MAX_PAGE_CHARS)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractMainText(doc: Document): String? {
        val mainSelectors = listOf(
            "article",
            "main",
            "[role=main]",
            "[itemprop=articleBody]",
            "div[class*=article]",
            "div[class*=content]",
            "div[class*=post]",
            "#content",
        )

        for (selector in mainSelectors) {
            val container = doc.selectFirst(selector) ?: continue
            val text = extractParagraphsAndTables(container)
            if (text.length >= 120) return text
        }

        return extractParagraphsAndTables(doc.body())
    }

    private fun extractParagraphsAndTables(root: Element?): String {
        if (root == null) return ""
        val blocks = root.select("p, h1, h2, h3, li, tr")
            .mapNotNull { el ->
                val t = collapseWhitespace(el.text())
                if (t.length >= 25) t else null
            }
            .distinct()
            .take(18)

        if (blocks.isNotEmpty()) {
            return blocks.joinToString("\n")
        }
        return collapseWhitespace(root.text())
    }

    private fun cleanQueryForWeb(input: String): String {
        val prefixes = listOf(
            "wyszukaj w internecie", "sprawdź w internecie", "poszukaj w internecie",
            "powiedz mi", "napisz mi", "czy wiesz", "proszę",
        )
        var q = input.trim()
        for (p in prefixes) {
            if (q.lowercase().startsWith(p)) {
                q = q.substring(p.length).trim()
            }
        }
        return q.ifBlank { input.trim() }
    }

    private fun decodeRedirectUrl(href: String): String {
        return try {
            val h = when {
                href.startsWith("//") -> "https:$href"
                href.startsWith("/") -> "https://duckduckgo.com$href"
                else -> href
            }
            val idx = h.indexOf("uddg=")
            if (idx != -1) {
                val start = idx + 5
                val end = h.indexOf('&', start).let { if (it == -1) h.length else it }
                URLDecoder.decode(h.substring(start, end), "UTF-8")
            } else {
                h
            }
        } catch (e: Exception) {
            href
        }
    }

    private fun decodeYahooRedirect(href: String): String {
        return try {
            val rkIdx = href.indexOf("/RU=")
            if (rkIdx != -1) {
                val start = rkIdx + 4
                val end = href.indexOf("/RK=", start).let { if (it == -1) href.length else it }
                URLDecoder.decode(href.substring(start, end), "UTF-8")
            } else {
                href
            }
        } catch (e: Exception) {
            href
        }
    }

    private fun hostname(url: String): String {
        return try {
            URL(url).host.removePrefix("www.")
        } catch (e: Exception) {
            url
        }
    }

    private fun clampText(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val cut = text.substring(0, maxChars)
        val lastPeriod = cut.lastIndexOf(". ")
        return if (lastPeriod > maxChars / 2) {
            cut.substring(0, lastPeriod + 1)
        } else {
            "$cut..."
        }
    }

    private fun collapseWhitespace(value: String): String =
        value.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()

    private data class SearchHit(
        val title: String,
        val url: String,
        val snippet: String,
    )

    private companion object {
        private const val MAX_RESULTS_TO_SCRAPE = 4
        private const val SEARCH_TIMEOUT_MS = 8000
        private const val PAGE_TIMEOUT_MS = 7000
        private const val MAX_PAGE_CHARS = 900
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 10 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
