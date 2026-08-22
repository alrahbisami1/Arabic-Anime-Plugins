package com.shadows.animerco

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

class AnimercoProvider : MainAPI() {
    override var mainUrl = "https://eta.animerco.org/"
    override var name = "Animerco"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "" to "الرئيسية",
        "animes/" to "قائمة الأنميات",
        "movies/" to "قائمة الأفلام",
        "seasons/" to "قائمة المواسم",
        "episodes/" to "آخر الحلقات",
    )

    private data class AjaxConfig(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("security") val security: String? = null,
    )

    private data class PlayerResponse(
        @JsonProperty("embed_url") val embedUrl: String? = null,
        @JsonProperty("type") val type: String? = null,
    )

    private fun Element.imgSrc(): String? =
        selectFirst("[data-src]")?.attr("data-src")
            ?: selectFirst("img[src]")?.attr("src")

    private fun tvTypeOf(type: String?): TvType = when {
        type == null -> TvType.Anime
        type.contains("Movie", true) || type.contains("فيلم") -> TvType.AnimeMovie
        type.contains(Regex("OVA|ONA|Special")) -> TvType.OVA
        else -> TvType.Anime
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = selectFirst("a[href*='/animes/'], a[href*='/seasons/'], a[href*='/movies/'], a[href*='/episodes/']")
            ?.attr("href")?.takeIf { it.startsWith("http") } ?: return null
        val title = selectFirst(".info h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("a.image[title]")?.attr("title")?.trim()
            ?: return null
        val type = when {
            href.contains("/movies/") -> TvType.AnimeMovie
            else -> tvTypeOf(selectFirst(".anime-type")?.text())
        }
        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = fixUrlNull(imgSrc())
        }
    }

    private fun decode(vararg texts: String): String =
        texts.joinToString(" ") {
            runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
        }

    private fun extractEpNumber(text: String): Int? =
        Regex("""الحلقة\s*(\d+)""").find(decode(text))?.groupValues?.get(1)?.toIntOrNull()

    private fun collectEpisodes(doc: Document, into: MutableList<Episode>) {
        doc.select(".episodes-lists li[data-number]").forEach { li ->
            val link = li.selectFirst("a[href*='/episodes/']") ?: return@forEach
            val href = link.attr("href")
            if (href.isBlank() || into.any { it.data == href }) return@forEach
            val num = li.attr("data-number").toIntOrNull()
                ?: extractEpNumber(href) ?: return@forEach
            into += newEpisode(href) {
                this.name = li.selectFirst("a[title]")?.attr("title")?.trim()
                    ?: li.selectFirst(".title h3")?.text()?.trim()
                this.posterUrl = fixUrlNull(li.selectFirst("a.image[data-src]")?.attr("data-src"))
                this.episode = num
            }
        }
    }

    private suspend fun loadDetails(url: String, forcedType: TvType?): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst(".media-title h1")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("No title")

        val poster = doc.selectFirst(".widget-sidebar [data-src]")?.attr("data-src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = doc.selectFirst(".media-story .content p")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        val year = doc.selectFirst(".media-info a[href*='/release/']")?.text()?.trim()?.toIntOrNull()

        val type = forcedType ?: tvTypeOf(
            doc.selectFirst(".media-info li:contains(النوع) span")?.text()
                ?: doc.selectFirst(".anime-type")?.text()
        )

        val tags = doc.select(".genres a").map { it.text().trim() }.filter { it.isNotBlank() }

        val seasons = doc.select(".media-seasons a[href*='/seasons/']")
            .map { it.attr("href") }.distinct()

        val episodes = mutableListOf<Episode>()
        if (seasons.isEmpty()) {
            collectEpisodes(doc, episodes)
        } else {
            seasons.forEach { season ->
                runCatching { app.get(season).document }.getOrNull()
                    ?.let { collectEpisodes(it, episodes) }
            }
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
            this.year = year
            this.tags = tags.ifEmpty { null }
            addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode ?: Int.MAX_VALUE })
        }
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.contains("/episodes/")) {
            val doc = app.get(url).document
            val parent = doc.selectFirst(".page-controls a[href*='/animes/']")?.attr("href")
                ?: doc.selectFirst(".breadcrumb a[href*='/animes/']")?.attr("href")
            if (parent != null) return loadDetails(parent, null)

            val title = doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"
            val num = extractEpNumber(url) ?: 1
            return newAnimeLoadResponse(title, url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, listOf(newEpisode(url) {
                    this.name = "الحلقة $num"
                    this.episode = num
                }))
            }
        }

        if (url.contains("/movies/")) {
            val details = loadDetails(url, TvType.AnimeMovie)
            if (details is AnimeLoadResponse && (details.episodes[DubStatus.Subbed].isNullOrEmpty())) {
                return newAnimeLoadResponse(details.name, url, TvType.AnimeMovie) {
                    this.posterUrl = details.posterUrl
                    this.plot = details.plot
                    this.year = details.year
                    this.tags = details.tags
                    addEpisodes(DubStatus.Subbed, listOf(newEpisode(url) { this.name = "الفيلم" }))
                }
            }
            return details
        }

        return loadDetails(url, null)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.isBlank() && page > 1 -> "${mainUrl}page/$page/"
            request.data.isBlank() -> mainUrl
            page <= 1 -> fixUrl(request.data)
            else -> "${mainUrl}${request.data}page/$page/"
        }
        val doc = runCatching { app.get(url).document }.getOrNull()
        val items = doc?.select(".media-block")
            ?.mapNotNull { runCatching { it.toSearchResult() }.getOrNull() }
            .orEmpty().distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        app.get("$mainUrl/?s=$query").document
            .select(".media-block")
            .mapNotNull { runCatching { it.toSearchResult() }.getOrNull() }
            .distinctBy { it.url }

    private suspend fun resolveEmbed(embed: String, referer: String): String? {
        if (!embed.startsWith("http")) return null
        if (!embed.startsWith(mainUrl) || !embed.contains("/jwplayer/")) return embed
        return runCatching {
            val playerDoc = app.get(embed, referer = referer).document
            playerDoc.selectFirst("iframe[src]")?.attr("src")
                ?: playerDoc.selectFirst("iframe[data-src]")?.attr("data-src")
        }.getOrNull()?.takeIf { it.startsWith("http") } ?: embed
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val options = doc.select(".server-list .option").toList()
        if (options.isEmpty()) return false

        val scripts = doc.select("script").joinToString("\n") { it.data() }
        val config = Regex("""dtAjax\s*=\s*(\{.*?\})""")
            .find(scripts)?.groupValues?.get(1)
            ?.let { tryParseJson<AjaxConfig>(it) }
        val ajaxUrl = mainUrl.trimEnd('/') + (config?.url ?: "/wp-admin/admin-ajax.php")

        val seen = mutableSetOf<String>()
        var found = false

        suspend fun pushSource(url: String) {
            if (url.isBlank() || !seen.add(url)) return
            val handled = runCatching {
                loadExtractor(url, referer = data, subtitleCallback = subtitleCallback) {
                    callback(it)
                }
            }.getOrDefault(false)
            if (handled) {
                found = true
                return
            }
            val pageText = runCatching { app.get(url, referer = data).text.replace("\\/", "/") }
                .getOrDefault("")
            val direct = Regex("""(https?://[^"'<>\s]+?\.(?:m3u8|mp4)[^"'<>\s]*)""")
                .find(pageText)?.groupValues?.get(1) ?: return
            found = true
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = Regex("""https?://([^/]+)""").find(direct)?.groupValues?.get(1) ?: name,
                    url = direct,
                    type = if (direct.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = url
                    this.quality = -1
                }
            )
        }

        for (option in options) {
            val post = option.attr("data-post")
            val nume = option.attr("data-nume")
            val type = option.attr("data-type").ifBlank { "tv" }
            val nonce = option.attr("data-nonce").ifBlank { config?.security.orEmpty() }
            if (post.isBlank() || nume.isBlank() || nonce.isBlank()) continue

            val body = runCatching {
                app.post(
                    ajaxUrl,
                    headers = mapOf(
                        "Referer" to data,
                        "X-Requested-With" to "XMLHttpRequest",
                    ),
                    data = mapOf(
                        "action" to "player_ajax",
                        "security" to nonce,
                        "post" to post,
                        "nume" to nume,
                        "type" to type,
                    )
                ).text
            }.getOrNull() ?: continue

            val player = tryParseJson<PlayerResponse>(body) ?: continue
            val embed = player.embedUrl ?: continue

            if (player.type == "dtshcode") {
                Jsoup.parse(embed)
                    .select("iframe[src], iframe[data-src], source[src], video[src]")
                    .forEach { el ->
                        pushSource(el.attr("src"))
                        pushSource(el.attr("data-src"))
                    }
                Regex("""(https?://[^"'<>\s]+\.(?:m3u8|mp4)[^"'<>\s]*)""").findAll(embed).forEach {
                    pushSource(it.groupValues[1])
                }
            } else {
                resolveEmbed(embed, data)?.let { pushSource(it) }
            }
        }

        return found
    }
}
