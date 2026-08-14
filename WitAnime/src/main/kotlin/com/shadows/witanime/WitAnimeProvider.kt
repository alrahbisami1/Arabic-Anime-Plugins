package com.shadows.witanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class WitAnimeProvider : MainAPI() {
    override var mainUrl = "https://witanime.onl/"
    override var name = "WitAnime"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override val mainPage = mainPageOf(
        "latest-anime" to "آخر الأنميات المضافة",
        "latest-episodes" to "آخر الحلقات"
    )

    // ===== main page =====
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(mainUrl).document
        return when (request.data) {
            "latest-episodes" -> {
                val items = document.select(".latest-episodes .episode-card").mapNotNull { el ->
                    el.toEpisodeCardSearch()
                }
                newHomePageResponse(request.name, items.distinctBy { it.url })
            }
            else -> {
                val items = document.select(".anime-card").mapNotNull { el ->
                    val href = el.selectFirst("a.image")?.attr("href")
                        ?: return@mapNotNull null
                    if (!href.contains("/anime/")) return@mapNotNull null
                    val title = el.selectFirst("h3")?.text() ?: return@mapNotNull null
                    val img = el.selectFirst("a.image")?.attr("style")
                        ?.let { Regex("url\\('([^']+)'\\)").find(it)?.groupValues?.get(1) }
                    val type = el.selectFirst(".anime-type")?.text()
                    newAnimeSearchResponse(title, href, tvTypeOf(type)) {
                        this.posterUrl = fixUrlNull(img)
                    }
                }
                newHomePageResponse(request.name, items.distinctBy { it.url })
            }
        }
    }

    private fun tvTypeOf(type: String?): TvType = when (type) {
        "فيلم" -> TvType.AnimeMovie
        else -> TvType.Anime
    }

    private fun Element.toEpisodeCardSearch(): AnimeSearchResponse? {
        val episodeHref = selectFirst("a.image")?.attr("href") ?: return null
        if (!episodeHref.contains("/episode/")) return null
        val animeHref = selectFirst("a[href*=/anime/]")?.attr("href") ?: return null
        val title = selectFirst("h4")?.text() ?: selectFirst("h3")?.text() ?: return null
        val img = selectFirst("a.image")?.attr("style")
            ?.let { Regex("url\\('([^']+)'\\)").find(it)?.groupValues?.get(1) }
        val epNum = Regex("الحلقة (\\d+)").find(selectFirst("h3")?.text().orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        return newAnimeSearchResponse(title, animeHref, TvType.Anime) {
            this.posterUrl = fixUrlNull(img)
            addDubStatus(false, epNum)
        }
    }

    // ===== search =====
    // Search results show the matching anime's latest episodes as episode-cards.
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}?s=$query").document
        return document.select(".episode-card").mapNotNull { it.toEpisodeCardSearch() }
            .distinctBy { it.url }
    }

    // ===== load =====
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()
            ?: throw ErrorLoadingException("No title")

        val poster = document.selectFirst(".anime-card .image")?.attr("style")
            ?.let { Regex("url\\('([^']+)'\\)").find(it)?.groupValues?.get(1) }

        val plot = document.selectFirst(".media-story .content p")?.text()

        val type = document.select(".media-info li").mapNotNull { li ->
            li.text().takeIf { it.contains("النوع") }?.substringAfter("النوع:")
                ?.trim()?.let { tvTypeOf(it) }
        }.firstOrNull() ?: TvType.Anime

        val episodes = getAllEpisodes(url, document)

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // WitAnime paginates episodes via /page/N/. Fetch until no next page (with a sane cap).
    private suspend fun getAllEpisodes(baseUrl: String, firstDoc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        var currentDoc = firstDoc
        var pageUrl = baseUrl
        var guard = 0
        while (guard++ < 60) {
            currentDoc.select("ul.episodes-lists li").forEach { li ->
                val href = li.selectFirst("a.image")?.attr("href")
                    ?: li.selectFirst("a.title")?.attr("href")
                    ?: return@forEach
                if (!href.contains("/episode/")) return@forEach
                if (episodes.any { it.data == href }) return@forEach
                val epName = li.selectFirst("h3")?.text()
                val epNum = Regex("الحلقة (\\d+)").find(epName.orEmpty())
                    ?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(newEpisode(href) {
                    this.name = epName
                    this.episode = epNum
                })
            }
            val next = currentDoc.selectFirst("a.next, a[rel=next], .pagination a[rel=next]")
                ?: currentDoc.select("link[rel=next]").firstOrNull()
            val nextUrl = next?.attr("href")
            if (nextUrl.isNullOrBlank() || nextUrl == pageUrl) break
            pageUrl = nextUrl
            if (!pageUrl.startsWith("http")) pageUrl = fixUrl(pageUrl)
            currentDoc = app.get(pageUrl).document
        }
        return episodes
    }

    // ===== load links =====
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val servers = document.select("a.episode-server").map { server ->
            server.attr("data-url") to server.text()
        }
        if (servers.isEmpty()) return false

        for ((serverUrl, serverName) in servers) {
            loadExtractor(serverUrl, referer = data, subtitleCallback = subtitleCallback) { link ->
                if (link.name.isNullOrBlank()) {
                    @Suppress("DEPRECATION", "DEPRECATION_ERROR")
                    callback.invoke(
                        ExtractorLink(
                            source = serverName.ifBlank { this.name },
                            name = serverName.ifBlank { link.name },
                            url = link.url,
                            referer = data,
                            quality = link.quality,
                        )
                    )
                } else {
                    callback.invoke(link)
                }
            }
        }
        return true
    }
}
