package com.shadows.witanime

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class WitAnimeProvider : MainAPI() {
    override var mainUrl = "https://witanime.you/"
    override var name = "WitAnime"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "" to "\u0622\u062e\u0631 \u0627\u0644\u0623\u0646\u0645\u064a\u0627\u062a \u0627\u0644\u0645\u0636\u0627\u0641\u0629",
        "episode/" to "\u0622\u062e\u0631 \u0627\u0644\u062d\u0644\u0642\u0627\u062a",
        "\u0642\u0627\u0626\u0645\u0629-%D8%A7\u0644\u0627\u0646\u0645\u064A/" to "\u0642\u0627\u0626\u0645\u0629 \u0627\u0644\u0623\u0646\u0645\u064A",
    )

    private fun Element.imgSrc(): String? {
        val el = selectFirst("img") ?: return null
        return el.attr("src").takeIf { it.startsWith("http") }
            ?: el.attr("data-src").takeIf { it.startsWith("http") }
            ?: el.attr("data-lazy-src").takeIf { it.startsWith("http") }
    }

    private fun Element.toAnimeCard(): AnimeSearchResponse? {
        val link = selectFirst("a[href*='/anime/']")
            ?: selectFirst("a[href]")
            ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        if (!href.contains("/anime/")) return null
        val title = selectFirst("h3, h2")?.text()
            ?: selectFirst(".anime-title, .card-title")?.text()
            ?: link.text().takeIf { it.isNotBlank() }
            ?: return null
        val poster = imgSrc()
        val typeStr = selectFirst("a[href*='anime-type'], .type-badge")?.text()
        return newAnimeSearchResponse(title.trim(), href, tvTypeOf(typeStr)) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    private fun Element.toEpisodeCard(): AnimeSearchResponse? {
        val epLink = selectFirst("a[href*='/episode/']") ?: return null
        val epHref = epLink.attr("href")
        val animeLink = selectFirst("a[href*='/anime/']")
        val animeHref = animeLink?.attr("href") ?: return null
        val title = animeLink.text().takeIf { it.isNotBlank() }
            ?: selectFirst("h3, h2")?.text()
            ?: return null
        val poster = imgSrc()
        val epNum = Regex("""\u0627\u0644\u062d\u0644\u0642\u0629[- ]?(\d+)""")
            .find(epHref + " " + epLink.text())?.groupValues?.get(1)?.toIntOrNull()
        return newAnimeSearchResponse(title.trim(), animeHref, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
            addDubStatus(false, epNum)
        }
    }

    private fun tvTypeOf(type: String?): TvType = when {
        type == null -> TvType.Anime
        type.contains("\u0641\u064a\u0644\u0645") || type.contains("movie", true) -> TvType.AnimeMovie
        type.contains("OVA", true) || type.contains("ONA", true) || type.contains("Special", true) -> TvType.OVA
        else -> TvType.Anime
    }

    private fun String.base64Decode(): String? {
        return try {
            val cleaned = this.trim()
            String(Base64.decode(cleaned, Base64.DEFAULT))
        } catch (_: Exception) {
            try {
                String(Base64.decode(this, Base64.NO_WRAP))
            } catch (_: Exception) {
                null
            }
        }
    }

    // ===== main page =====
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) mainUrl + request.data else {
            val base = "${mainUrl}${request.data}"
            "${base}page/$page/"
        }
        val doc = app.get(url).document
        val items = mutableListOf<SearchResponse>()

        doc.select("a[href*='/anime/']").distinctBy { it.attr("href") }.forEach { a ->
            val card = a.closest("div") ?: a.parent() ?: return@forEach
            val href = a.attr("href")
            val title = card.selectFirst("h3, h2")?.text()
                ?: a.text().takeIf { it.isNotBlank() } ?: return@forEach
            if (title.length < 2) return@forEach
            val poster = card.imgSrc()
            val typeStr = card.selectFirst("a[href*='anime-type']")?.text()
            items.add(newAnimeSearchResponse(title.trim(), href, tvTypeOf(typeStr)) {
                this.posterUrl = fixUrlNull(poster)
            })
        }

        if (items.isEmpty() || request.data.contains("episode")) {
            doc.select("a[href*='/episode/']").distinctBy { it.attr("href") }.forEach { a ->
                val card = a.closest("div") ?: a.parent() ?: return@forEach
                val epHref = a.attr("href")
                val animeLink = card.selectFirst("a[href*='/anime/']") ?: return@forEach
                val animeHref = animeLink.attr("href")
                val title = animeLink.text().takeIf { it.isNotBlank() } ?: return@forEach
                if (title.length < 2) return@forEach
                val poster = card.imgSrc()
                val epNum = Regex("""\u0627\u0644\u062d\u0644\u0642\u0629[- ]?(\d+)""")
                    .find(epHref + " " + a.text())?.groupValues?.get(1)?.toIntOrNull()
                items.add(newAnimeSearchResponse(title.trim(), animeHref, TvType.Anime) {
                    this.posterUrl = fixUrlNull(poster)
                    addDubStatus(false, epNum)
                })
            }
        }

        return newHomePageResponse(request.name, items.distinctBy { it.url })
    }

    // ===== search =====
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("${mainUrl}?s=$query").document
        val results = mutableListOf<SearchResponse>()

        doc.select("a[href*='/episode/'], a[href*='/anime/']").distinctBy { it.attr("href") }.forEach { a ->
            val card = a.closest("div") ?: a.parent() ?: return@forEach
            val href = a.attr("href")
            if (href.contains("/anime/")) {
                val title = card.selectFirst("h3, h2")?.text()
                    ?: a.text().takeIf { it.isNotBlank() } ?: return@forEach
                if (title.length < 2) return@forEach
                results.add(newAnimeSearchResponse(title.trim(), href, TvType.Anime) {
                    this.posterUrl = fixUrlNull(card.imgSrc())
                })
            } else if (href.contains("/episode/")) {
                val animeLink = card.selectFirst("a[href*='/anime/']") ?: return@forEach
                val animeHref = animeLink.attr("href")
                val title = animeLink.text().takeIf { it.isNotBlank() } ?: return@forEach
                if (title.length < 2) return@forEach
                val epNum = Regex("""\u0627\u0644\u062d\u0644\u0642\u0629[- ]?(\d+)""")
                    .find(href + " " + a.text())?.groupValues?.get(1)?.toIntOrNull()
                results.add(newAnimeSearchResponse(title.trim(), animeHref, TvType.Anime) {
                    this.posterUrl = fixUrlNull(card.imgSrc())
                    addDubStatus(false, epNum)
                })
            }
        }

        return results.distinctBy { it.url }
    }

    // ===== load =====
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        if (url.contains("/episode/")) {
            val animeUrl = doc.selectFirst("a[href*='/anime/']")?.attr("href")
            if (animeUrl != null) return loadAnimePage(animeUrl)
            val title = doc.selectFirst("h1, h2, .episode-anime-title")?.text() ?: "Unknown"
            val epNum = Regex("""\u0627\u0644\u062d\u0644\u0642\u0629[- ]?(\d+)""")
                .find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            return newAnimeLoadResponse(title, url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, listOf(
                    newEpisode(url) { episode = epNum; name = "\u0627\u0644\u062d\u0644\u0642\u0629 $epNum" }
                ))
            }
        }

        return loadAnimePage(url)
    }

    private suspend fun loadAnimePage(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, .anime-title, .anime-details h1")?.text()?.trim()
            ?: throw ErrorLoadingException("No title")

        val poster = doc.selectFirst(".anime-poster img, .anime-image img, .anime-cover img")?.run {
            attr("src").takeIf { it.startsWith("http") }
                ?: attr("data-src").takeIf { it.startsWith("http") }
        } ?: doc.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }

        val description = doc.selectFirst(".anime-story, .story, .anime-description, .description")?.text()?.trim()

        val typeStr = doc.selectFirst("a[href*='anime-type']")?.text()
        val type = tvTypeOf(typeStr)

        val slug = url.trimEnd('/').substringAfterLast('/')
        val episodes = buildEpisodeList(slug, doc)

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun buildEpisodeList(animeSlug: String, doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // Strategy 1: openEpisode('base64') onclick attribute (witanime.you encodes URLs in onclick)
        doc.select("[onclick*=openEpisode]").forEach { el ->
            val onclick = el.attr("onclick")
            val b64 = Regex("""openEpisode\(['"]([A-Za-z0-9+/=]+)['"]\)""").find(onclick)
                ?.groupValues?.get(1) ?: return@forEach
            val href = b64.base64Decode() ?: return@forEach
            if (!href.startsWith("http")) return@forEach
            if (episodes.any { it.data == href }) return@forEach
            val text = el.text().trim()
            val epNum = extractEpNumber(href + " " + text)
            episodes += newEpisode(href) {
                name = text.ifBlank { null }
                episode = epNum
            }
        }
        if (episodes.isNotEmpty()) {
            return episodes.sortedBy { it.episode ?: Int.MAX_VALUE }
        }

        // Strategy 2: links containing the anime slug with real URLs
        doc.select("a[href*='/episode/$animeSlug']").forEach { a ->
            val href = a.attr("href")
            if (href.isBlank() || href.startsWith("javascript:")) return@forEach
            if (episodes.any { it.data == href }) return@forEach
            val text = a.text().trim()
            val epNum = extractEpNumber(href + " " + text)
            episodes += newEpisode(href) {
                name = text.ifBlank { null }
                episode = epNum
            }
        }
        if (episodes.isNotEmpty()) {
            return episodes.sortedBy { it.episode ?: Int.MAX_VALUE }
        }

        // Strategy 3: any /episode/ link with real URL containing the slug
        doc.select("a[href*='/episode/']")
            .filter { it.attr("href").contains(animeSlug, true) }
            .forEach { a ->
                val href = a.attr("href")
                if (href.isBlank() || href.startsWith("javascript:")) return@forEach
                if (episodes.any { it.data == href }) return@forEach
                val text = a.text().trim()
                val epNum = extractEpNumber(href + " " + text)
                episodes += newEpisode(href) {
                    name = text.ifBlank { null }
                    episode = epNum
                }
            }
        if (episodes.isNotEmpty()) {
            return episodes.sortedBy { it.episode ?: Int.MAX_VALUE }
        }

        // Strategy 4: data-* attributes on elements containing episode text
        doc.select("a, div, li, span").filter {
            it.attr("onclick").isBlank() && it.text().contains("\u0627\u0644\u062d\u0644\u0642\u0629")
        }.forEach { el ->
            val dataUrl = listOf("data-url", "data-href", "data-link", "data-episode")
                .map { el.attr(it) }
                .firstOrNull { it.startsWith("http") }
            if (dataUrl != null && episodes.none { it.data == dataUrl }) {
                val text = el.text().trim()
                val epNum = extractEpNumber(dataUrl + " " + text)
                episodes += newEpisode(dataUrl) {
                    name = text.ifBlank { null }
                    episode = epNum
                }
            }
        }
        if (episodes.isNotEmpty()) {
            return episodes.sortedBy { it.episode ?: Int.MAX_VALUE }
        }

        // Strategy 5: construct URLs from episode count in info text
        val infoText = doc.selectFirst(".anime-info, .info-list, .order, .anime-details")?.text() ?: ""
        val epCount = Regex("""\u0639\u062f\u062f\s*\u0627\u0644\u062d\u0644\u0642\u0628\u062a?\D*(\d+)""").find(infoText)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(\d+)\s*\u062d\u0644\u0642\u0629""").find(infoText)?.groupValues?.get(1)?.toIntOrNull()

        if (epCount != null && epCount in 1..3000) {
            for (i in 1..epCount) {
                episodes += newEpisode(
                    "${mainUrl}episode/$animeSlug-\u0627\u0644\u062d\u0644\u0642\u0629-$i/"
                ) {
                    name = "\u0627\u0644\u062d\u0644\u0642\u0629 $i"
                    episode = i
                }
            }
            if (episodes.isNotEmpty()) return episodes
        }

        // Strategy 6: count episode text entries on the page and construct URLs
        val epHeaders = doc.select("a, h3, li, div, span")
            .filter { el ->
                val t = el.text().trim()
                t.contains("\u0627\u0644\u062d\u0644\u0642\u0629") && extractEpNumber(t) != null
                    && el.select("a[href*='/episode/']").isEmpty()
                    && el.attr("onclick").isBlank()
            }
        val epNumbers = epHeaders.mapNotNull { el -> extractEpNumber(el.text()) }
            .filter { it > 0 }.distinct().sorted()

        if (epNumbers.isNotEmpty()) {
            for (epNum in epNumbers) {
                val epUrl = "${mainUrl}episode/$animeSlug-\u0627\u0644\u062d\u0644\u0642\u0629-$epNum/"
                episodes += newEpisode(epUrl) {
                    name = "\u0627\u0644\u062d\u0644\u0642\u0629 $epNum"
                    episode = epNum
                }
            }
        }

        return episodes
    }

    private fun extractEpNumber(text: String): Int? =
        Regex("""\u0627\u0644\u062d\u0644\u0642\u0629[- ]?(\d+)""").find(text)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""[Ee]p(?:isode)?[- ]?(\d+)""").find(text)
                ?.groupValues?.get(1)?.toIntOrNull()

    // ===== load links =====
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var found = false

        // 1. Decode resourceRegistry / configRegistry from script tags
        // witanime.you stores server URLs as reversed-base64 in window.resourceRegistry
        // and uses configRegistry for a byte offset to trim from the end
        val registries = mutableMapOf<String, String>() // serverId -> reversed-base64
        val configMap = mutableMapOf<String, Map<String, Any>>() // serverId -> config

        doc.select("script").forEach { script ->
            val js = script.data().takeIf { it.isNotBlank() } ?: return@forEach

            // Extract resourceRegistry: window.resourceRegistry = {"id": "reversedData", ...}
            Regex("""window\.resourceRegistry\s*=\s*(\{[\s\S]*?\});""").find(js)?.let { match ->
                tryParseJson<Map<String, String>>(match.groupValues[1])?.forEach { (k, v) ->
                    registries[k] = v
                }
            }

            // Extract configRegistry: window.configRegistry = {"id": {"k": "base64key", "d": [...]}, ...}
            Regex("""window\.configRegistry\s*=\s*(\{[\s\S]*?\});""").find(js)?.let { match ->
                try {
                    tryParseJson<Map<String, Map<String, Any>>>(match.groupValues[1])?.forEach { (k, v) ->
                        configMap[k] = v
                    }
                } catch (_: Exception) {}
            }
        }

        // Decode server URLs from registries
        if (registries.isNotEmpty()) {
            val frameworkHash = "9933" + "bd27-92ea-" + "4ee9-807d-" + "e612029d6318"
            doc.select("[data-server-id]").forEach { el ->
                val serverId = el.attr("data-server-id")
                val reversed = registries[serverId] ?: return@forEach
                try {
                    val cleaned = reversed.replace(Regex("[^A-Za-z0-9+/=]"), "")
                    val config = configMap[serverId]
                    val decoded = String(Base64.decode(cleaned, Base64.DEFAULT))
                    val offset = if (config != null) {
                        val indexKey = String(Base64.decode(config["k"] as? String ?: "", Base64.DEFAULT))
                        val dArray = config["d"] as? List<*>
                        dArray?.getOrNull(indexKey.toIntOrNull() ?: 0) as? Double ?: 0.0
                    } else 0.0
                    val url = decoded.dropLast(offset.toInt())
                    if (url.startsWith("http")) {
                        val finalUrl = if (url.contains("yonaplay.net/embed.php")) {
                            "$url&apiKey=$frameworkHash"
                        } else url
                        found = true
                        safeLoadExtractor(finalUrl, data, subtitleCallback, callback)
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. data-embed / data-src / data-url / data-link attributes on server buttons
        doc.select("[data-embed],[data-src],[data-url],[data-link]").forEach { el ->
            val embedUrl = listOf("data-embed", "data-src", "data-url", "data-link")
                .map { el.attr(it) }
                .firstOrNull { it.startsWith("http") }
                ?: return@forEach
            found = true
            safeLoadExtractor(embedUrl, data, subtitleCallback, callback)
        }

        // 3. iframes already in the HTML
        doc.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("src").takeIf { it.startsWith("http") }
                ?: iframe.attr("data-src").takeIf { it.startsWith("http") }
                ?: return@forEach
            found = true
            safeLoadExtractor(src, data, subtitleCallback, callback)
        }

        // 4. scan <script> tags for embedded URLs and server data
        doc.select("script").forEach { script ->
            val js = script.data().takeIf { it.isNotBlank() } ?: return@forEach

            Regex(""""(?:embed|src|url|link|file)"\s*:\s*"(https?://[^"]+)"""")
                .findAll(js).map { it.groupValues[1] }.forEach {
                    found = true
                    safeLoadExtractor(it, data, subtitleCallback, callback)
                }

            Regex("""(?:var|const|let)\s+\w+\s*=\s*(\[[\s\S]+?]);""")
                .findAll(js).forEach { match ->
                    try {
                        val arr = tryParseJson<List<Map<String, String>>>(match.groupValues[1])
                        arr?.forEach inner@{ srv ->
                            val foundUrl = srv["embed"] ?: srv["url"]
                                ?: srv["src"] ?: srv["link"] ?: return@inner
                            if (foundUrl.startsWith("http")) {
                                found = true
                                safeLoadExtractor(foundUrl, data, subtitleCallback, callback)
                            }
                        }
                    } catch (_: Exception) {}
                }

            Regex("""(https?://(?:ok\.ru|streamwish\.\w+|dailymotion\.com|videa\.hu|yonaplay\.\w+|fliqcast\.\w+|filemoon\.\w+|vidhide\.\w+|ktpubs\.\w+|uqload\.\w+|soraplay\.\w+)/[^\s"'<>]+)""")
                .findAll(js).map { it.groupValues[1] }.forEach {
                    found = true
                    safeLoadExtractor(it, data, subtitleCallback, callback)
                }
        }

        return found
    }

    private suspend fun safeLoadExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            loadExtractor(url, referer, subtitleCallback, callback)
        } catch (_: Exception) {
        }
    }
}
