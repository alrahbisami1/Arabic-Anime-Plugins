package com.shadows.anime4up

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import okio.ByteString.Companion.decodeBase64

class Anime4upProvider : MainAPI() {
    override var mainUrl = "https://w1.anime4up.rest/"
    override var name = "Anime4up"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Others)
    override val mainPage = mainPageOf(
        "latest" to "أحدث الأنميات",
        "latest-episodes" to "أحدث الحلقات"
    )

    private fun Element.toSearchResponse(): SearchResponse {
        val img = selectFirst(".hover img")
        val title = img?.attr("alt")
            ?: selectFirst(".anime-card-title h3 a")?.text()
            ?: return newAnimeSearchResponse("Unknown", "", TvType.Anime)
        val href = selectFirst(".anime-card-title h3 a")?.attr("href")
            ?: selectFirst(".overlay")?.attr("href")
            ?: ""
        // episode links come as /episode/<slug>-<الحلقة-N>-مترجمة/, normalize to the anime page
        val url = href
            .replace(Regex("-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-.*"), "")
            .replace("episode", "anime")
        val poster = img?.attr("data-image") ?: img?.attr("src")
        val typeText = selectFirst(".anime-card-type")?.text().orEmpty()
        val type = when {
            typeText.contains(Regex("TV|Special")) -> TvType.Anime
            typeText.contains(Regex("OVA|ONA")) -> TvType.OVA
            typeText.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
            else -> TvType.Others
        }
        return newAnimeSearchResponse(title, url, type) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    // ===== main page =====
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val homeList = doc.select(".page-content-container .main-widget").mapNotNull { widget ->
            val title = widget.selectFirst(".main-didget-head h3")?.text() ?: return@mapNotNull null
            if (title == "الأنميات المثبتة") return@mapNotNull null
            val list = widget.select(".anime-card-themex").mapNotNull {
                runCatching { it.toSearchResponse() }.getOrNull()
        }.distinctBy { it.url }
            if (list.isEmpty()) null else HomePageList(title, list)
        }
        return newHomePageResponse(homeList)
    }

    // ===== search =====
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?search_param=animes&s=$query").document
        return doc.select(".anime-card-themex").mapNotNull {
            runCatching { it.toSearchResponse() }.getOrNull()
        }.distinctBy { it.url }
    }

    // ===== load =====
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.anime-details-title")?.text()
            ?: doc.selectFirst("h1")?.text()
            ?: throw ErrorLoadingException("No title")

        val poster = doc.selectFirst(".anime-thumbnail img")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val description = doc.selectFirst("p.anime-story")?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val year = doc.select(".anime-info:contains(بداية العرض)").firstOrNull()
            ?.text()?.replace("بداية العرض:", "")?.trim()?.toIntOrNull()

        val typeText = doc.selectFirst(".anime-info:contains(النوع)")?.text().orEmpty()
        val type = when {
            typeText.contains(Regex("TV|Special")) -> TvType.Anime
            typeText.contains(Regex("OVA|ONA")) -> TvType.OVA
            typeText.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
            else -> TvType.Others
        }

        val malId = doc.selectFirst("a.anime-mal")?.attr("href")
            ?.replace(Regex(".*e/"), "")?.replace("/", "")?.toIntOrNull()

        val episodes = doc.select("#episodesList .anime-card-themex").mapNotNull { el ->
            val href = el.selectFirst(".overlay")?.attr("href")
                ?: el.selectFirst(".ep_num a")?.attr("href")
                ?: return@mapNotNull null
            val epTitle = el.selectFirst(".ep_num a")?.text()
            val epPoster = el.selectFirst(".hover img")?.attr("data-image")
            newEpisode(href) {
                this.name = epTitle
                this.posterUrl = fixUrlNull(epPoster)
                this.episode = epTitle?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
            }
        }.distinctBy { it.data }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
            this.year = year
            addMalId(malId)
            addEpisodes(if (title.contains("مدبلج")) DubStatus.Dubbed else DubStatus.Subbed, episodes)
        }
    }

    // ===== load links =====
    // Watch links are stored base64-encoded in <input name="wl"> as JSON {fhd, hd, sd},
    // plus optional moshahda mirrors in <input name="moshahda">.
    data class WatchLinks(
        @JsonProperty("fhd") val fhd: Map<String, String>? = null,
        @JsonProperty("hd") val hd: Map<String, String>? = null,
        @JsonProperty("sd") val sd: Map<String, String>? = null,
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val seen = mutableSetOf<String>()
        var found = false

        suspend fun pushServer(url: String) {
            if (url.isBlank() || !seen.add(url)) return
            found = true
            loadExtractor(url, referer = data, subtitleCallback = subtitleCallback) { extracted ->
                callback.invoke(extracted)
            }
        }

        // Modern layout: watch servers listed as <li data-watch="...">.
        for (li in doc.select("ul#episode-servers li[data-watch]")) {
            pushServer(li.attr("data-watch"))
        }

        // Modern layout: download table fallback.
        for (tr in doc.select("div.download-list table.table tbody tr")) {
            pushServer(tr.selectFirst("td.td-link a")?.attr("href").orEmpty())
        }

        // Legacy layout: base64 JSON in <input name="wl">.
        val wl = doc.selectFirst("input[name=\"wl\"]")?.attr("value")
        if (!wl.isNullOrBlank()) {
            val links = runCatching {
                parseJson<WatchLinks>(wl.decodeBase64()?.utf8() ?: "")
            }.getOrNull()
            if (links != null) {
                listOfNotNull(links.fhd, links.hd, links.sd).flatMap { it.values }.forEach { pushServer(it) }
            }
        }

        // Legacy layout: moshahda mirrors.
        val moshahda = doc.selectFirst("input[name=\"moshahda\"]")?.attr("value")
        if (!moshahda.isNullOrBlank()) {
            val id = moshahda.decodeBase64()?.utf8().orEmpty()
            if (id.isNotEmpty()) {
                mapOf(
                    "Original" to "download_o",
                    "720" to "download_x",
                    "480" to "download_h",
                    "360" to "download_n",
                    "240" to "download_l",
                ).forEach { (quality, code) ->
                    found = true
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} Moshahda",
                            url = "https://moshahda.net/$id.html?$code",
                        ) {
                            this.referer = "https://moshahda.net"
                            this.quality = quality.toIntOrNull() ?: 1080
                        }
                    )
                }
            }
        }

        // Legacy fallback: direct server links via data-ep-url.
        for (el in doc.select("a[data-ep-url]")) {
            pushServer(el.attr("data-ep-url"))
        }

        return found
    }
}
