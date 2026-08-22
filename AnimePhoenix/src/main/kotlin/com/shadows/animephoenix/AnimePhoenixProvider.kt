package com.shadows.animephoenix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class AnimePhoenixProvider : MainAPI() {
    override var mainUrl = "https://anime-phoenix.com/"
    override var name = "AnimePhoenix"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override val mainPage = mainPageOf(
        "latest" to "أحدث الحلقات",
        "animes" to "أحدث الأعمال",
        "movies" to "أفلام الأنمي",
        "top" to "الأكثر رواجاً"
    )

    private fun Element.toSearchResponse(): SearchResponse {
        val href = attr("href")
        val img = selectFirst("img.FJ-Phoenix-Anastasia-EpCard-Img")
        val title = selectFirst(".FJ-Phoenix-Anastasia-EpCard-Name")?.text()
            ?: img?.attr("alt")
            ?: return newAnimeSearchResponse("Unknown", "", TvType.Anime)
        val type = when {
            href.contains("/movies/") -> TvType.AnimeMovie
            else -> TvType.Anime
        }
        return newAnimeSearchResponse(title, fixUrl(href), type) {
            this.posterUrl = fixUrlNull(img?.attr("src"))
        }
    }

    private fun Element.toHeroSearchResponse(): SearchResponse {
        val href = attr("href")
        val img = selectFirst("img.FJ-Phoenix-Anastasia-Hero-Img")
        val title = selectFirst(".FJ-Phoenix-Anastasia-Hero-Title")?.text()
            ?: img?.attr("alt")
            ?: return newAnimeSearchResponse("Unknown", "", TvType.Anime)
        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            this.posterUrl = fixUrlNull(img?.attr("src"))
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val items = when (request.data) {
            "latest" -> doc.select("main.FJ-Phoenix-Anastasia-Latest a.FJ-Phoenix-Anastasia-EpCard")
                .mapNotNull { runCatching { it.toSearchResponse() }.getOrNull() }
            "animes" -> doc.select("section.FJ-Phoenix-Anastasia-Movies a.FJ-Phoenix-Anastasia-EpCard")
                .filter { !it.attr("href").contains("/movies/") }
                .mapNotNull { runCatching { it.toSearchResponse() }.getOrNull() }
            "movies" -> doc.select("section.FJ-Phoenix-Anastasia-Movies a.FJ-Phoenix-Anastasia-EpCard")
                .filter { it.attr("href").contains("/movies/") }
                .mapNotNull { runCatching { it.toSearchResponse() }.getOrNull() }
            else -> doc.select("a.FJ-Phoenix-Anastasia-Hero-Card")
                .mapNotNull { runCatching { it.toHeroSearchResponse() }.getOrNull() }
        }
        return newHomePageResponse(request.name, items.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = fixUrl("/search/?q=$query")
        val inlineJs = app.get(searchUrl).document
            .selectFirst("script:containsData(fjSearchPageData)")?.data()
            ?: return emptyList()
        val configJson = Regex("\\{[^{}]*\\}").find(inlineJs)?.value ?: return emptyList()
        val config = runCatching { parseJson<PhoenixConfig>(configJson) }.getOrNull() ?: return emptyList()

        val body = runCatching {
            app.post(
                config.ajaxUrl ?: "${mainUrl}wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "phoenix_search",
                    "nonce" to (config.nonce ?: ""),
                    "q" to query,
                    "type" to "all",
                    "genre" to "",
                    "status" to "",
                    "year" to "",
                    "season" to "",
                    "sort" to "relevance",
                    "page" to "1",
                    "per_page" to "25",
                    "dropdown" to "0"
                ),
                referer = searchUrl
            ).text
        }.getOrNull() ?: return emptyList()

        val response = runCatching { parseJson<PhoenixAjaxResponse>(body) }.getOrNull()
            ?: return emptyList()

        return response.data?.results.orEmpty().mapNotNull { item ->
            val url = item.url ?: return@mapNotNull null
            val title = item.titleAr ?: return@mapNotNull null
            val type = when (item.itemType) {
                "movie" -> TvType.AnimeMovie
                else -> TvType.Anime
            }
            newAnimeSearchResponse(title, url, type) {
                this.posterUrl = fixUrlNull(item.thumbnailUrl)
            }
        }.distinctBy { it.url }
    }

    private fun episodeUrlToAnimeUrl(url: String): String {
        val slug = url.substringAfter("/episodes/").trimEnd('/')
            .replace(Regex("-episode-\\d+$"), "")
        return fixUrl("/animes/$slug")
    }

    private fun Element.ldJson(): LdMedia? {
        return select("script[type=application/ld+json]")
            .firstNotNullOfOrNull { script ->
                runCatching { parseJson<LdMedia>(script.data()) }.getOrNull()
                    ?.takeIf { !it.name.isNullOrBlank() && it.description != null }
            }
    }

    private fun yearFromMeta(doc: org.jsoup.nodes.Document): Int? {
        return doc.select("li.FJ-Phoenix-Meta-Item")
            .firstOrNull { it.select("i.uil-calendar-alt").isNotEmpty() }
            ?.text()?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
    }

    override suspend fun load(url: String): LoadResponse {
        var fixed = fixUrl(url)

        if (fixed.contains("/episodes/")) {
            fixed = episodeUrlToAnimeUrl(fixed)
        }

        val doc = app.get(fixed).document

        val ld = doc.ldJson()
        val title = ld?.name?.trim()
            ?: doc.selectFirst("h1")?.ownText()?.trim()
                ?.replace(Regex("\\s*\\|\\s*أنمي فينيكس.*$"), "")
                ?.replace(Regex("^أنمي\\s+"), "")
                ?.replace("مترجم", "")
                ?.trim()
            ?: throw ErrorLoadingException("No title")

        val poster = fixUrlNull(ld?.image)
            ?: fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))

        val plot = ld?.description?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        val score = ld?.aggregateRating?.ratingValue?.let { Score.from10(it) }
        val year = yearFromMeta(doc)
            ?: ld?.datePublished?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }

        val isMovie = fixed.contains("/movies/")

        if (isMovie) {
            val watchUrl = fixed.trimEnd('/') + "/watch"
            return newMovieLoadResponse(title, fixed, TvType.AnimeMovie, watchUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score
            }
        }

        val episodes = doc.select("a.FJ-EpPill").mapNotNull { el ->
            val href = el.attr("href")
            if (!href.contains("/episodes/")) return@mapNotNull null
            newEpisode(fixUrl(href)) {
                this.episode = Regex("(\\d+)\\s*$").find(el.ownText())?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("-episode-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
            }
        }.distinctBy { it.data }.sortedBy { it.episode }

        return newAnimeLoadResponse(title, fixed, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = score
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val template = doc.selectFirst("#player-html-template") ?: return false
        var count = 0

        val sourceEl = template.selectFirst("video source")
        val videoSrc = sourceEl?.attr("src")?.trim().orEmpty()

        if (videoSrc.isNotEmpty()) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoSrc,
                    type = if (videoSrc.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = mainUrl
                    this.quality = Regex("(\\d{3,4})p").find(videoSrc)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                }
            )
            count++
        } else {
            template.select("iframe").mapNotNull { it.attr("src").trim().takeIf { src -> src.isNotEmpty() } }
                .forEach { iframeUrl ->
                    runCatching {
                        loadExtractor(iframeUrl, referer = data, subtitleCallback = subtitleCallback) { extracted ->
                            callback.invoke(extracted)
                            count++
                        }
                    }
                }
        }

        return count > 0
    }
}

data class PhoenixConfig(
    @JsonProperty("ajax_url") val ajaxUrl: String? = null,
    @JsonProperty("nonce") val nonce: String? = null,
)

data class PhoenixResult(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("title_ar") val titleAr: String? = null,
    @JsonProperty("thumbnail_url") val thumbnailUrl: String? = null,
    @JsonProperty("item_type") val itemType: String? = null,
)

data class PhoenixResults(
    @JsonProperty("results") val results: List<PhoenixResult>? = null,
    @JsonProperty("total") val total: Int? = null,
    @JsonProperty("total_pages") val totalPages: Int? = null,
    @JsonProperty("page") val page: Int? = null,
)

data class PhoenixAjaxResponse(
    @JsonProperty("success") val success: Boolean? = false,
    @JsonProperty("data") val data: PhoenixResults? = null,
)

data class LdRating(
    @JsonProperty("ratingValue") val ratingValue: String? = null,
    @JsonProperty("bestRating") val bestRating: String? = null,
)

data class LdSeries(
    @JsonProperty("@type") val type: String? = null,
    @JsonProperty("name") val name: String? = null,
)

data class LdMedia(
    @JsonProperty("@type") val type: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("image") val image: String? = null,
    @JsonProperty("datePublished") val datePublished: String? = null,
    @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
    @JsonProperty("partOfSeries") val partOfSeries: LdSeries? = null,
    @JsonProperty("aggregateRating") val aggregateRating: LdRating? = null,
)
