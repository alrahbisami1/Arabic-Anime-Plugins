package com.shadows.anime3rb

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class Anime3rbProvider : MainAPI() {
    override var mainUrl = "https://anime3rb.com/"
    override var name = "Anime3rb"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override val mainPage = mainPageOf(
        "latest" to "آخر الحلقات"
    )

    private val titlesSitemap = "https://anime3rb.com/storage/sitemaps/titles_sitemap.xml"
    private val sitemapCacheMs = 60 * 60 * 1000
    private var cachedSitemapSlugs: List<Pair<String, String>>? = null // slug -> name-from-slug

    // ===== search =====
    // /search is blocked by Cloudflare, /livewire/update returns 500.
    // The only stable, unblocked index is the titles sitemap (6386 title URLs).
    override suspend fun search(query: String): List<SearchResponse> {
        val slugs = getSitemapTitles()
        val q = query.trim().lowercase()
        return slugs.asSequence()
            .filter { (slug, name) ->
                slug.contains(q) || name.contains(q)
            }
            .take(30)
            .map { (slug, name) ->
                newAnimeSearchResponse(name, "/titles/$slug", TvType.Anime) {
                    this.posterUrl = null
                }
            }
            .toList()
    }

    private suspend fun getSitemapTitles(): List<Pair<String, String>> {
        cachedSitemapSlugs?.let { return it }
        val doc = app.get(titlesSitemap, cacheTime = sitemapCacheMs).document
        val titles = doc.select("url > loc").mapNotNull { loc ->
            val url = loc.text()
            val slug = url.substringAfterLast("/titles/", "").trimEnd('/')
            if (slug.isBlank()) null
            else slug to slug.replace("-", " ")
        }
        cachedSitemapSlugs = titles
        return titles
    }

    // ===== main page =====
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(mainUrl).document
        val items = document.select("a.video-card").mapNotNull { it.toVideoCardSearch() }
        return newHomePageResponse(request.name, items.distinctBy { it.url })
    }

    private fun Element.toVideoCardSearch(): AnimeSearchResponse? {
        val episodeUrl = attr("href")
        if (!episodeUrl.contains("/episode/")) return null
        val title = selectFirst("h3.title-name")?.text() ?: return null
        val img = selectFirst("img")
        val epNumber = selectFirst("p.number")?.text()?.let {
            Regex("\\d+").find(it)?.value?.toIntOrNull()
        }
        return newAnimeSearchResponse(title, episodeUrl, TvType.Anime) {
            this.posterUrl = fixUrlNull(img?.attr("src") ?: img?.attr("data-src"))
            addDubStatus(false, epNumber)
        }
    }

    // ===== load =====
    // url can be either /episode/<slug>/<n> (from main page) or /titles/<slug> (from search)
    override suspend fun load(url: String): LoadResponse {
        val titleUrl = if (url.contains("/episode/")) {
            val slug = url.substringAfter("/episode/").substringBefore("/")
            "/titles/$slug"
        } else url

        val fixed = fixUrl(titleUrl)
        val document = app.get(fixed).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.replace(" - Anime3rb أنمي عرب", "")
            ?.replace("أنمي ", "")
            ?.replace("مترجم", "")
            ?.trim()
            ?: document.selectFirst("h1")?.text()
            ?: throw ErrorLoadingException("No title")

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img[src*=images.anime3rb.com]")?.attr("src")

        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.select("p.sm\\:text-\\[1\\.04rem\\]").firstOrNull()?.text()

        val episodeLinks = document.select("a[href*=/episode/]").mapNotNull { el ->
            val href = el.attr("href")
            if (!href.contains("/episode/")) return@mapNotNull null
            val epNum = href.substringAfterLast("/").toIntOrNull()
            val epPoster = el.selectFirst("img")?.attr("src")
            newEpisode(href) {
                this.posterUrl = fixUrlNull(epPoster)
                this.name = el.selectFirst("p")?.text()
                this.episode = epNum
            }
        }.distinctBy { it.data }

        return newAnimeLoadResponse(title, fixed, TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodeLinks)
        }
    }

    // ===== load links =====
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeDoc = app.get(data).text

        // video_url is embedded in the Livewire snapshot, HTML-escaped:
        // &quot;video_url&quot;:&quot;https:\/\/video.vid3rb.com\/player\/<uuid>?token=...&amp;expires=...&quot;
        val videoUrl = Regex("&quot;video_url&quot;:&quot;(.*?)&quot;")
            .find(episodeDoc)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.replace("&amp;", "&")
            ?: return false

        val playerText = app.get(videoUrl, referer = data).text

        // The player declares `var video_sources = [];` (empty) before the real array,
        // so find the LAST non-empty `var video_sources = [...]` assignment.
        val json = Regex("video_sources\\s*=\\s*(\\[.*?\\]);", RegexOption.DOT_MATCHES_ALL)
            .findAll(playerText)
            .map { it.groupValues[1] }
            .lastOrNull { it.length > 2 }
            ?: return false

        val sources = runCatching {
            com.lagradost.cloudstream3.utils.AppUtils.parseJson<List<VideoSource>>(json)
        }.getOrNull() ?: return false

        var count = 0
        for (src in sources) {
            if (src.premium == true || src.src.isNullOrBlank()) continue
            val quality = src.res?.trim()?.toIntOrNull() ?: -1
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = src.label ?: "${src.res}p",
                    url = src.src,
                ) {
                    this.referer = mainUrl
                    this.quality = quality
                }
            )
            count++
        }
        return count > 0
    }
}

data class VideoSource(
    @JsonProperty("src") val src: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("res") val res: String? = null,
    @JsonProperty("premium") val premium: Boolean? = false,
)
