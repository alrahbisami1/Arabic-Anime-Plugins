package com.shadows.witanime

import com.lagradost.cloudstream3.*
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
        "" to "آخر الأنميات المضافة",
        "episode/" to "آخر الحلقات",
        "\u0642\u0627\u0626\u0645\u0629-%D8%A7\u0644\u0627\u0646\u0645\u064A/" to "قائمة الأنمي",
    )

    // ===== helpers =====

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

    // ===== main page =====
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) mainUrl + request.data else {
            val base = if (request.data.endsWith("/")) "${mainUrl}${request.data}"
            else "${mainUrl}${request.data}"
            "${base}page/$page/"
        }
        val doc = app.get(url).document
        val items = mutableListOf<SearchResponse>()

        // 1. Anime cards (e.g. pinned anime, latest anime)
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

        // 2. Episode cards (latest episodes listing)
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

        // Search results may be episode cards or anime cards
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
            // Find parent anime page
            val animeUrl = doc.selectFirst("a[href*='/anime/']")?.attr("href")
            if (animeUrl != null) return loadAnimePage(animeUrl)
            // Fallback: create minimal episode response
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

        // Strategy 1: links containing the anime slug with real URLs
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

        // Strategy 2: any /episode/ link with real URL containing the slug
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

        // Strategy 3: data-* attributes on episode elements (witanime.you uses javascript:void(0)
        // hrefs but may have data-url/data-href/data-link attributes with real episode URLs)
        doc.select("a").filter { it.text().contains("\u0627\u0644\u062d\u0644\u0642\u0629") }.forEach { a ->
            val dataUrl = listOf("data-url", "data-href", "data-link", "data-episode")
                .map { a.attr(it) }
                .firstOrNull { it.startsWith("http") }
            if (dataUrl != null && episodes.none { it.data == dataUrl }) {
                val text = a.text().trim()
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

        // Strategy 4: scan <script> tags for episode data / URLs
        doc.select("script").forEach { script ->
            val js = script.data().ifBlank { return@forEach }
            Regex(""""episode_url"\s*:\s*"(https?://[^"]+)"""")
                .findAll(js).map { it.groupValues[1] }.forEach { epUrl ->
                    if (episodes.none { it.data == epUrl }) {
                        val epNum = extractEpNumber(epUrl)
                        episodes += newEpisode(epUrl) {
                            episode = epNum
                            name = epNum?.let { "\u0627\u0644\u062d\u0644\u0642\u0629 $it" }
                        }
                    }
                }
            Regex("""(?:episode|ep)_number["\s:]+(\d+)""", RegexOption.IGNORE_CASE)
                .findAll(js).map { it.groupValues[1].toInt() }.forEach { epNum ->
                    if (episodes.none { it.episode == epNum }) {
                        episodes += newEpisode("") { episode = epNum }
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
            if (episodes.isNotEmpty()) {
                return episodes
            }
        }

        // Strategy 6: count episode text entries on the page and construct URLs
        val epHeaders = doc.select("a, h3, li, div, span")
            .filter { el ->
                val t = el.text().trim()
                t.contains("\u0627\u0644\u062d\u0644\u0642\u0629") && extractEpNumber(t) != null
                    && el.select("a[href*='/episode/']").isEmpty()
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

        // 1. data-embed / data-src / data-url / data-link attributes on server buttons
        doc.select("[data-embed],[data-src],[data-url],[data-link]").forEach { el ->
            val embedUrl = listOf("data-embed", "data-src", "data-url", "data-link")
                .map { el.attr(it) }
                .firstOrNull { it.startsWith("http") }
                ?: return@forEach
            found = true
            safeLoadExtractor(embedUrl, data, subtitleCallback, callback)
        }

        // 2. iframes already in the HTML
        doc.select("iframe[src], iframe[data-src]").forEach { iframe ->
            val src = iframe.attr("src").takeIf { it.startsWith("http") }
                ?: iframe.attr("data-src").takeIf { it.startsWith("http") }
                ?: return@forEach
            found = true
            safeLoadExtractor(src, data, subtitleCallback, callback)
        }

        // 3. scan <script> tags for embedded URLs and server data
        doc.select("script").forEach { script ->
            val js = script.data().takeIf { it.isNotBlank() } ?: return@forEach

            // Pattern A: JSON-style embed URLs
            Regex(""""(?:embed|src|url|link|file)"\s*:\s*"(https?://[^"]+)"""")
                .findAll(js).map { it.groupValues[1] }.forEach {
                    found = true
                    safeLoadExtractor(it, data, subtitleCallback, callback)
                }

            // Pattern B: JS array of server objects
            Regex("""(?:var|const|let)\s+\w+\s*=\s*(\[[\s\S]+?]);""")
                .findAll(js).forEach { match ->
                    try {
                        val arr = com.lagradost.cloudstream3.utils.AppUtils.tryParseJson<List<Map<String, String>>>(match.groupValues[1])
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

            // Pattern C: bare URLs that look like known embed hosts
            Regex("""(https?://(?:ok\.ru|streamwish\.\w+|dailymotion\.com|videa\.hu|yonaplay\.\w+)/[^\s"'<>]+)""")
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
