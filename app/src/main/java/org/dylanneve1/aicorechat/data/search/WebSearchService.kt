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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

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
            val collectedBlocks = mutableListOf<String>()

            // 1. Jeśli pytanie dotyczy kursów walut, pobieramy też twarde dane z API NBP
            fetchNbpRatesIfRelevant(rawQuery)?.let { collectedBlocks.add(it) }

            // 2. Pobieramy wyniki z kaskady wyszukiwarek WWW (DDG -> Brave -> Yahoo)
            val searchHits = fetchWebSearchHits(query).ifEmpty {
                if (query != rawQuery) fetchWebSearchHits(rawQuery) else emptyList()
            }

            // 3. Wchodzimy na znalezione strony WWW i wyciągamy ich treść
            if (searchHits.isNotEmpty()) {
                val scrapedPages = coroutineScope {
                    searchHits.take(MAX_RESULTS_TO_SCRAPE).mapIndexed { index, hit ->
                        async { scrapeAndFormatHit(index, hit) }
                    }.awaitAll()
                }.filterNotNull()

                if (scrapedPages.isNotEmpty()) {
                    collectedBlocks.addAll(scrapedPages)
                } else {
                    searchHits.take(MAX_RESULTS_TO_SCRAPE).forEachIndexed { i, hit ->
                        collectedBlocks.add("[Wynik ${i + 1}: ${hit.title}] ${hit.snippet}")
                    }
                }
            }

            // 4. Uzupełniamy o Google News RSS i Wikipedię PL, jeśli wyników WWW było mało
            if (collectedBlocks.size < 2) {
                collectedBlocks.addAll(searchGoogleNewsRss(query))
                collectedBlocks.addAll(searchWikipediaFullText(query))
            }

            if (collectedBlocks.isEmpty()) {
                "Brak wyników dla zapytania: $rawQuery"
            } else {
                collectedBlocks.take(5).joinToString("\n\n")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            "Błąd wyszukiwania: ${e.message}"
        }
    }

    private fun fetchNbpRatesIfRelevant(query: String): String? {
        val q = query.lowercase()
        val keywords = listOf("kurs", "walut", "dolar", "usd", "euro", "eur", "frank", "chf", "funt", "gbp", "złot")
        if (keywords.none { q.contains(it) }) return null

        return try {
            val jsonStr = Jsoup.connect("https://api.nbp.pl/api/exchangerates/tables/A/?format=json")
                .userAgent(USER_AGENT)
                .ignoreContentType(true)
                .timeout(SEARCH_TIMEOUT_MS)
                .execute()
                .body()

            val table = JSONArray(jsonStr).getJSONObject(0)
            val date = table.optString("effectiveDate", "")
            val rates = table.getJSONArray("rates")
            val wanted = setOf("USD", "EUR", "CHF", "GBP", "JPY", "CZK", "NOK", "SEK")
            val sb = StringBuilder("[Tabela kursów NBP z dnia $date]\n")
            for (i in 0 until rates.length()) {
                val r = rates.getJSONObject(i)
                val code = r.getString("code")
                if (code in wanted) {
                    sb.append("- ${r.getString("currency")} ($code): ${r.getDouble("mid")} PLN\n")
                }
            }
            sb.toString().trim()
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchWebSearchHits(query: String): List<SearchHit> {
        val hits = mutableListOf<SearchHit>()

        runCatching { hits.addAll(searchDuckDuckGo(query)) }
        if (hits.size < 3) {
            runCatching { hits.addAll(searchBraveWeb(query)) }
        }
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
        val doc = Jsoup.connect("https://search.brave.com/search?q=$encoded&source=web")
            .userAgent(USER_AGENT)
            .header("Accept-Language", "pl-PL,pl;q=0.9,en-US;q=0.8")
            .timeout(SEARCH_TIMEOUT_MS)
            .get()

        return doc.select("div.snippet[data-type=web], div.snippet").mapNotNull { el ->
            val linkEl = el.selectFirst("a[href^=http]") ?: return@mapNotNull null
            val href = linkEl.attr("href").trim()
            if (href.contains("brave.com")) return@mapNotNull null
            val title = el.selectFirst(".title, .snippet-title, h2, h3")?.text()?.trim()
                ?: linkEl.text().trim()
            val snippet = el.selectFirst(".snippet-description, .snippet-content, .description")
                ?.text()?.trim().orEmpty()
            if (title.isBlank()) null else SearchHit(title, href, snippet)
        }
    }

    private fun searchYahooWeb(query: String): List<SearchHit> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = Jsoup.connect("https://pl.search.yahoo.com/search?p=$encoded&ei=UTF-8")
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

    private fun searchGoogleNewsRss(query: String): List<String> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val xml = Jsoup.connect("https://news.google.com/rss/search?q=$encoded&hl=pl&gl=PL&ceid=PL:pl")
                .userAgent(USER_AGENT)
                .timeout(SEARCH_TIMEOUT_MS)
                .ignoreContentType(true)
                .execute()
                .body()

            val doc = Jsoup.parse(xml, "", Parser.xmlParser())
            doc.select("item").take(3).mapNotNull { item ->
                val title = item.selectFirst("title")?.text()?.trim().orEmpty()
                val desc = Jsoup.parse(item.selectFirst("description")?.text().orEmpty()).text().trim()
                if (title.isBlank()) null else "[Wiadomość] $title — $desc"
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun searchWikipediaFullText(query: String): List<String> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url =
                "https://pl.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&utf8=1&format=json&srlimit=2"
            val jsonStr = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .ignoreContentType(true)
                .timeout(SEARCH_TIMEOUT_MS)
                .execute()
                .body()

            val searchArray = JSONObject(jsonStr).optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
            val list = mutableListOf<String>()
            for (i in 0 until searchArray.length()) {
                val item = searchArray.getJSONObject(i)
                val title = item.optString("title")
                val snippet = Jsoup.parse(item.optString("snippet")).text().trim()
                if (title.isNotBlank() && snippet.isNotBlank()) {
                    list.add("[Wikipedia: $title] $snippet")
                }
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun scrapeAndFormatHit(index: Int, hit: SearchHit): String? {
        val host = hostname(hit.url)
        val pageContent = scrapeWebPageContent(hit.url)
        if (hit.title.isBlank() && hit.snippet.isBlank() && pageContent.isNullOrBlank()) return null

        return buildString {
            append("[Źródło ${index + 1}: $host — ${hit.title}]\n")
            if (hit.snippet.isNotBlank()) {
                append("Opis: ${hit.snippet}\n")
            }
            if (!pageContent.isNullOrBlank()) {
                append("Treść strony: $pageContent")
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
                .maxBodySize(1024 * 1024)
                .get()

            doc.select(
                "script, style, nav, footer, header, aside, form, noscript, iframe, svg, " +
                    "[class*=cookie], [id*=cookie], [class*=popup], [class*=banner]",
            ).remove()

            val metaDesc = sequenceOf(
                doc.selectFirst("meta[name=description]")?.attr("content"),
                doc.selectFirst("meta[property=og:description]")?.attr("content"),
            ).mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }.firstOrNull()

            val mainBody = extractMainText(doc)
            val combined = buildString {
                if (!metaDesc.isNullOrBlank()) append(metaDesc).append("\n")
                if (!mainBody.isNullOrBlank()) append(mainBody)
            }.trim()

            if (combined.isBlank()) null else clampText(combined, MAX_PAGE_CHARS)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractMainText(doc: Document): String? {
        val selectors = listOf("article", "main", "[role=main]", "[itemprop=articleBody]", "div[class*=content]", "#content")
        for (selector in selectors) {
            val container = doc.selectFirst(selector) ?: continue
            val text = extractParagraphs(container)
            if (text.length >= 100) return text
        }
        return extractParagraphs(doc.body())
    }

    private fun extractParagraphs(root: Element?): String {
        if (root == null) return ""
        val blocks = root.select("p, h1, h2, h3, li, tr")
            .mapNotNull { el ->
                val t = collapseWhitespace(el.text())
                if (t.length >= 25) t else null
            }
            .distinct()
            .take(15)
        return if (blocks.isNotEmpty()) blocks.joinToString("\n") else collapseWhitespace(root.text())
    }

    private fun cleanQueryForWeb(input: String): String {
        val stopPhrases = listOf(
            "wyszukaj w internecie", "sprawdź w internecie", "poszukaj w internecie",
            "powiedz mi", "napisz mi", "czy wiesz", "jaki jest dzisiaj", "jaka jest dzisiaj",
            "jaki jest aktualny", "jaki jest", "jaka jest", "jakie są", "proszę", "podaj",
        )
        var cleaned = input.lowercase().replace(Regex("[?!.,;:\"]"), " ")
        for (phrase in stopPhrases) {
            cleaned = cleaned.replace(Regex("\\b$phrase\\b"), " ")
        }
        val finalQuery = cleaned.replace(Regex("\\s+"), " ").trim()
        return if (finalQuery.length >= 2) finalQuery else input.trim()
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
        return if (lastPeriod > maxChars / 2) cut.substring(0, lastPeriod + 1) else "$cut..."
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
        private const val PAGE_TIMEOUT_MS = 6000
        private const val MAX_PAGE_CHARS = 700
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 10 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
