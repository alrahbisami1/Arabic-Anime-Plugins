package com.shadows.animedar

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimedarProvider : MainAPI() {
    override var mainUrl = "https://animedar.net/"
    override var name = "Animedar"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "" to "\u0623\u062d\u062f\u062b \u0627\u0644\u0623\u0646\u0645\u064a\u0627\u062a",
    )

    private fun Element.imgSrc(): String? {
        val el = selectFirst("img") ?: return null
        return el.attr("src").takeIf { it.startsWith("http") }
            ?: el.attr("data-src").takeIf { it.startsWith("http") }
            ?: el.attr("data-lazy-src").takeIf { it.startsWith("http") }
    }

    private fun tvTypeOf(type: String?): TvType = when {
        type == null -> TvType.Anime
        type.contains("\u0641\u064a\u0644\u0645", true) || type.contains("movie", true) -> TvType.AnimeMovie
        type.contains("OVA", true) || type.contains("ONA", true) || type.contains("Special", true) -> TvType.OVA
        else -> TvType.Anime
    }

    private fun Element.toResult(): AnimeSearchResponse? {
        val card = selectFirst("article.bs") ?: closest("article.bs") ?: this
        val link = card.selectFirst(".bsx a[href*='/anime-p/']")
            ?: card.selectFirst("a[href*='/anime-p/']")
            ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = link.attr("title").takeIf { it.isNotBlank() }
            ?: card.selectFirst(".tt h2, .tt")?.text()?.takeIf { it.isNotBlank() }
            ?: return null
        val typeStr = card.selectFirst(".typez")?.text()
        return newAnimeSearchResponse(title.trim(), href, tvTypeOf(typeStr)) {
            this.posterUrl = fixUrlNull(card.imgSrc())
        }
    }

    // ===== main page =====
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) mainUrl + request.data
        else "${mainUrl}${request.data}page/$page/"
        val doc = app.get(url).document
        val items = doc.select("article.bs").mapNotNull { it.toResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    // ===== search =====
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl?s=$query").document
        return doc.select("article.bs").mapNotNull { it.toResult() }
            .distinctBy { it.url }
    }

    // ===== load =====
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst(".infox h1.entry-title, h1.entry-title")?.text()?.trim()
            ?: throw ErrorLoadingException("No title")

        val poster = doc.selectFirst(".thumbook .thumb img, .thumb img, img.ts-post-image")?.run {
            attr("src").takeIf { it.startsWith("http") }
                ?: attr("data-src").takeIf { it.startsWith("http") }
                ?: attr("data-lazy-src").takeIf { it.startsWith("http") }
        }

        val plot: String? = doc.selectFirst(".entry-content[itemprop=description], div.entry-content")?.text()?.trim()

        val genres = doc.select(".genxed a").mapNotNull {
            it.text().trim().takeIf { t -> t.isNotBlank() }
        }

        val speText = doc.select(".info-content .spe span").eachText().joinToString(" ")
        val typeStr = Regex("""\u0627\u0644\u0646\u0648\u0639:\s*(\S+)""").find(speText)?.groupValues?.get(1)
        val year = Regex("""\u062a\u0645 \u0627\u0644\u0625\u0635\u062f\u0627\u0631:\s*(\d{4})""").find(speText)
            ?.groupValues?.get(1)?.toIntOrNull()
        val isCompleted = speText.contains("\u0645\u0643\u062a\u0645\u0644")

        val episodes = buildEpisodeList(url, doc)

        return newAnimeLoadResponse(title, url, tvTypeOf(typeStr)) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
            this.showStatus = if (isCompleted) ShowStatus.Completed else ShowStatus.Ongoing
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun buildEpisodeList(url: String, doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val epDivs = doc.select("#EpList1 .CSB")
        val serverBlocks = doc.select("#ServerList1 div.divv11")

        if (epDivs.isNotEmpty()) {
            epDivs.forEachIndexed { idx, el ->
                val num = Regex("""(\d+)""").find(el.text())?.groupValues?.get(1)?.toIntOrNull()
                    ?: (idx + 1)
                episodes += newEpisode("$url|$idx") {
                    name = "\u0627\u0644\u062d\u0644\u0642\u0629 $num"
                    episode = num
                }
            }
        } else if (serverBlocks.isNotEmpty()) {
            serverBlocks.forEachIndexed { idx, _ ->
                val num = idx + 1
                episodes += newEpisode("$url|$idx") {
                    name = "\u0627\u0644\u062d\u0644\u0642\u0629 $num"
                    episode = num
                }
            }
        }

        return episodes.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    private fun serverEmbedUrl(type: String, id: String): String? {
        val vid = id.trim()
        if (vid.isEmpty()) return null
        return when (type.trim().lowercase()) {
            "asnwish" -> "https://asnwish.com/e/$vid"
            "videa" -> "https://videa.hu/player?v=$vid"
            "mp4upload" -> "https://www.mp4upload.com/embed-$vid.html"
            "vidbem", "vidbom" -> "https://vidbem.com/embed-$vid.html"
            "vidbam" -> "https://vidbam.org/embed-$vid.html"
            "vedbom" -> "https://vedbom.com/embed-$vid.html"
            "vidbm" -> "https://vidbm.com/embed-$vid.html"
            "vidhd" -> "https://vidhd.net/embed-$vid.html"
            "vidshare" -> "https://vidshare.tv/embed-$vid.html"
            "vidshar" -> "https://vidshar.org/embed-$vid.html"
            "segavid" -> "https://segavid.com/embed-$vid.html"
            "sblanh" -> "https://sblanh.com/e/$vid"
            "highload" -> "https://highload.to/e/$vid"
            "upvideo" -> "https://upvideo.to/e/$vid"
            "upstream" -> "https://upstream.to/embed-$vid.html"
            "dailymotion" -> "https://www.dailymotion.com/embed/video/$vid"
            "solidfiles" -> "https://www.solidfiles.com/e/$vid"
            "vid4up" -> "https://cdn2.vid4up.xyz/embedvideo/$vid"
            "animemixat", "animeup" -> "https://www.anime4up.net/player/$vid"
            "uqload" -> "https://uqload.com/embed-$vid.html"
            "ninjastream" -> "https://ninjastream.to/watch/$vid"
            "userload" -> "https://userload.co/embed/$vid"
            "vedshare" -> "https://vedshare.com/embed-$vid.html"
            "myviid" -> "https://myviid.net/embed-$vid.html"
            "govid" -> "https://govid.me/embed-$vid.html"
            "playtube" -> "https://playtube.ws/embed-$vid.html"
            "dood", "doodstream" -> "https://dood.so/e/$vid"
            "mixdrop" -> "https://mixdrop.to/e/$vid"
            "ok" -> "https://www.ok.ru/videoembed/$vid"
            "fembed" -> "https://fembed.com/v/$vid"
            "holavid" -> "https://holavid.com/embed-$vid.html"
            "uptobox", "uptostream" -> "https://uptostream.com/iframe/$vid"
            "samaup" -> "https://samaup.cc/embed-$vid.html"
            "watchsb" -> "https://watchsb.com/e/$vid"
            "soraplay" -> "https://soraplay.xyz/embed/$vid"
            "vidyard" -> "https://play.vidyard.com/$vid"
            "youtube" -> "https://www.youtube.com/embed/$vid"
            "goved" -> "https://goved.org/embed-$vid.html"
            "4shared" -> "https://www.4shared.com/web/embed/file/${vid.take(10)}"
            "sendvid" -> "https://sendvid.com/embed/$vid"
            "streamhub" -> "https://streamhub.to/e/$vid"
            "clipwatching" -> "https://clipwatching.com/embed-$vid.html"
            "vidfast" -> "https://vidfast.co/embed-$vid.html"
            "mega" -> "https://mega.nz/embed/${vid.replace(":/mega.nz/embed#!", "")}"
            "openload" -> "https://openload.co/embed/$vid"
            "yourupload" -> "https://www.yourupload.com/embed/$vid"
            "yonaplay" -> "https://yonaplay.org/embed.php?id=$vid"
            "yuistream" -> "https://yuistream.xyz/v/$vid"
            "vanfem" -> "https://vanfem.com/v/$vid"
            "temp", "top4top" -> vid.takeIf { it.startsWith("http") }
            "drive" -> "https://drive.google.com/file/d/${vid.substringBefore("/p")}/preview"
            else -> null
        }
    }

    // ===== load links =====
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val pageUrl = parts.dropLast(1).joinToString("|")
        val idx = parts.lastOrNull()?.toIntOrNull() ?: 0

        val doc = app.get(pageUrl).document
        val servers = doc.select("#ServerList1 div.divv11")
            .getOrNull(idx)
            ?.select("ul.ul-server-position1 li")
            ?: return false

        var found = false
        servers.forEach { el ->
            val type = el.attr("type").ifBlank { el.attr("class") }
            val vid = el.attr("data")
            val qualityName = el.attr("quality-data").uppercase()
            val embedUrl = serverEmbedUrl(type, vid) ?: return@forEach

            val quality = when (qualityName) {
                "FHD" -> Qualities.P1080.value
                "HD" -> Qualities.P720.value
                "SD" -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }

            if (type.equals("temp", true) || type.equals("top4top", true)) {
                found = true
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "${name} ${el.text().trim()}",
                        url = embedUrl,
                        type = if (embedUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                        else ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = mainUrl
                        this.quality = quality
                    }
                )
                return@forEach
            }

            found = true
            safeLoadExtractor(embedUrl, pageUrl, subtitleCallback, callback)
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
