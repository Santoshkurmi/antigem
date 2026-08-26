package com.example.gemini.data.web

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String
)

data class WebpageContent(
    val title: String,
    val url: String,
    val text: String,
    val charCount: Int
)

/**
 * Free native Web Search and Webpage Reader.
 * Requires zero API keys or tokens. Uses DuckDuckGo HTML search & Jsoup content extractor.
 */
object WebSearchManager {

    private const val TAG = "WebSearchManager"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Searches DuckDuckGo for top web results.
     */
    suspend fun search(query: String, maxResults: Int = 5): Result<List<SearchResult>> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://html.duckduckgo.com/html/?q=$encodedQuery"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Search HTTP request failed with code ${response.code}"))
            }

            val html = response.body?.string() ?: return@withContext Result.failure(Exception("Empty search response body"))
            val doc = Jsoup.parse(html)

            val results = mutableListOf<SearchResult>()
            val resultElements = doc.select(".results .result")

            for (element in resultElements) {
                if (results.size >= maxResults) break

                val titleElem = element.selectFirst(".result__title a") ?: continue
                val snippetElem = element.selectFirst(".result__snippet")

                val title = titleElem.text().trim()
                var resultUrl = titleElem.attr("href")

                // Extract actual destination URL from DDG uddg redirect parameter if present
                if (resultUrl.contains("uddg=")) {
                    try {
                        val parsedUri = Uri.parse(resultUrl)
                        val actualUrl = parsedUri.getQueryParameter("uddg")
                        if (!actualUrl.isNullOrBlank()) {
                            resultUrl = actualUrl
                        }
                    } catch (_: Exception) {}
                }

                // If relative link, convert to absolute
                if (resultUrl.startsWith("//")) {
                    resultUrl = "https:$resultUrl"
                }

                val snippet = snippetElem?.text()?.trim() ?: ""

                if (title.isNotBlank() && resultUrl.isNotBlank() && !resultUrl.startsWith("/")) {
                    results.add(
                        SearchResult(
                            title = title,
                            url = resultUrl,
                            snippet = snippet
                        )
                    )
                }
            }

            if (results.isEmpty()) {
                // Fallback attempt: check lite duckduckgo version
                val liteResults = searchLite(query, maxResults)
                if (liteResults.isSuccess && liteResults.getOrNull()?.isNotEmpty() == true) {
                    return@withContext liteResults
                }
            }

            Result.success(results)
        } catch (e: Exception) {
            Log.e(TAG, "Search failed for query: $query", e)
            Result.failure(e)
        }
    }

    private fun searchLite(query: String, maxResults: Int): Result<List<SearchResult>> {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://lite.duckduckgo.com/lite/?q=$encodedQuery"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = httpClient.newCall(request).execute()
            val html = response.body?.string() ?: return Result.failure(Exception("Empty lite response"))
            val doc = Jsoup.parse(html)

            val results = mutableListOf<SearchResult>()
            val links = doc.select("a.result-link")
            val snippets = doc.select("td.result-snippet")

            for (i in links.indices) {
                if (results.size >= maxResults) break
                val title = links[i].text().trim()
                val href = links[i].attr("href")
                val snippet = if (i < snippets.size) snippets[i].text().trim() else ""

                if (title.isNotBlank() && href.isNotBlank()) {
                    results.add(SearchResult(title, href, snippet))
                }
            }
            return Result.success(results)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /**
     * Reads and extracts clean readable markdown text from a target URL.
     */
    suspend fun readUrl(url: String, maxLength: Int = 6000): Result<WebpageContent> = withContext(Dispatchers.IO) {
        try {
            var targetUrl = url.trim()
            if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
                targetUrl = "https://$targetUrl"
            }

            val request = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Failed to fetch URL, HTTP ${response.code}"))
            }

            val contentType = response.header("Content-Type") ?: ""
            if (!contentType.contains("text/html") && !contentType.contains("application/xhtml") && !contentType.contains("text/plain")) {
                val rawBody = response.body?.string()?.take(maxLength) ?: ""
                return@withContext Result.success(
                    WebpageContent(
                        title = targetUrl,
                        url = targetUrl,
                        text = rawBody,
                        charCount = rawBody.length
                    )
                )
            }

            val html = response.body?.string() ?: return@withContext Result.failure(Exception("Empty webpage body"))
            val doc: Document = Jsoup.parse(html, targetUrl)

            val pageTitle = doc.title().trim().ifEmpty { targetUrl }

            // Strip non-content and noise elements
            doc.select("script, style, noscript, nav, footer, header, iframe, svg, form, aside, .advertisement, .ads, .sidebar, .menu, .cookie-banner, .popup").remove()

            // Find main container or fallback to body
            val mainContentElem: Element = doc.selectFirst("article, main, [role=main], .content, #content, .post, .article") ?: doc.body()

            val textBuilder = StringBuilder()

            // Extract headings, paragraphs, and list items
            val contentNodes = mainContentElem.select("h1, h2, h3, h4, h5, h6, p, li, pre, blockquote, table")
            if (contentNodes.isNotEmpty()) {
                for (node in contentNodes) {
                    val text = node.text().trim()
                    if (text.isBlank()) continue

                    val tagName = node.tagName().lowercase()
                    when {
                        tagName.startsWith("h") -> {
                            val level = tagName.removePrefix("h").toIntOrNull() ?: 2
                            textBuilder.append("\n\n" + "#".repeat(level) + " " + text + "\n")
                        }
                        tagName == "li" -> {
                            textBuilder.append("\n- ").append(text)
                        }
                        tagName == "pre" -> {
                            textBuilder.append("\n```\n").append(text).append("\n```\n")
                        }
                        tagName == "blockquote" -> {
                            textBuilder.append("\n> ").append(text).append("\n")
                        }
                        else -> {
                            textBuilder.append("\n\n").append(text)
                        }
                    }

                    if (textBuilder.length >= maxLength) break
                }
            } else {
                textBuilder.append(mainContentElem.text().trim())
            }

            val cleanText = textBuilder.toString().trim()
            val truncatedText = if (cleanText.length > maxLength) {
                cleanText.take(maxLength) + "\n\n...(content truncated for length)"
            } else cleanText

            Result.success(
                WebpageContent(
                    title = pageTitle,
                    url = targetUrl,
                    text = truncatedText.ifEmpty { "(No readable text found on page)" },
                    charCount = truncatedText.length
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to read URL: $url", e)
            Result.failure(e)
        }
    }
}
