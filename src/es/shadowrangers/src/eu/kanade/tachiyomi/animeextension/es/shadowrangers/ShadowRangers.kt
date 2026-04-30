package eu.kanade.tachiyomi.animeextension.es.shadowrangers

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class ShadowRangers : AnimeHttpSource() {

    override val name = "Shadow-Rangers"
    override val baseUrl = "https://shadowrangers.live"
    override val lang = "es"
    override val supportsLatest = true

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/series/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("article.TPost.B").map { parseAnimeFromElement(it) }
        val hasNextPage = document.selectFirst("a.next.page-numbers") != null
        return AnimesPage(animes, hasNextPage)
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/capitulos/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("article.TPost.B").map { element ->
            SAnime.create().apply {
                val anchor = element.selectFirst("a")!!
                // Each item here is an episode; point to the series URL
                setUrlWithoutDomain(anchor.attr("href"))
                title = element.selectFirst(".Title")?.text() ?: ""
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }
        val hasNextPage = document.selectFirst("a.next.page-numbers") != null
        return AnimesPage(animes, hasNextPage)
    }

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()

        return when {
            genreFilter?.state != 0 -> {
                val genre = genreFilter!!.toUriPart()
                GET("$baseUrl/genero/$genre/page/$page/", headers)
            }
            typeFilter?.state != 0 -> {
                val type = typeFilter!!.toUriPart()
                GET("$baseUrl/$type/page/$page/", headers)
            }
            query.isNotBlank() -> GET("$baseUrl/?s=$query&page=$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    // ============================== Details ===============================

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst("h1.Title")?.text() ?: ""
            thumbnail_url = doc.selectFirst(".TPostBg, img.Bg, img[itemprop=image]")?.attr("abs:src")
            description = doc.selectFirst("div.Description p, div[itemprop=description]")?.text()
            genre = doc.select("p.genres a, .genres a").joinToString { it.text() }
            status = when (doc.selectFirst(".Status")?.text()?.lowercase()) {
                "en emisión", "en emision" -> SAnime.ONGOING
                "finalizado" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        // DooPlay theme: episodes are listed in ul.episodios or div#episodios
        val episodes = mutableListOf<SEpisode>()

        // Try season/episode list structure
        doc.select("ul.episodios li, #episodios li").forEach { li ->
            val anchor = li.selectFirst("a") ?: return@forEach
            val epNum = li.selectFirst(".NumEp")?.text()?.trim()
            val name = li.selectFirst(".Title")?.text()?.trim() ?: epNum ?: ""
            episodes.add(
                SEpisode.create().apply {
                    setUrlWithoutDomain(anchor.attr("href"))
                    episode_number = epNum?.toFloatOrNull() ?: 0f
                    episode_name = name
                    date_upload = 0L
                },
            )
        }

        // If it's a movie/single-page, create one episode pointing to itself
        if (episodes.isEmpty()) {
            episodes.add(
                SEpisode.create().apply {
                    setUrlWithoutDomain(response.request.url.toString().removePrefix(baseUrl))
                    episode_name = "Película"
                    episode_number = 1f
                },
            )
        }

        return episodes.reversed()
    }

    // ============================== Video Links ===========================

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val videos = mutableListOf<Video>()

        // DooPlay stores player sources in <li data-src="..."> or Option elements
        val serverItems = doc.select("ul.TPlayerNv li, #playeroptionsul li")
        serverItems.forEach { item ->
            val embedUrl = item.attr("data-src").trim()
                .ifEmpty { item.attr("data-post").trim() }
            if (embedUrl.isNotEmpty()) {
                videos.addAll(extractVideosFromEmbed(embedUrl, doc))
            }
        }

        // Fallback: look for iframes directly
        if (videos.isEmpty()) {
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("abs:src")
                if (src.isNotEmpty()) {
                    videos.addAll(extractVideosFromEmbed(src, doc))
                }
            }
        }

        return videos.ifEmpty { emptyList() }
    }

    private fun extractVideosFromEmbed(url: String, doc: Document): List<Video> {
        return runCatching {
            when {
                "dood" in url || "doodstream" in url ->
                    DoodExtractor(client).videoFromUrl(url)?.let { listOf(it) } ?: emptyList()

                "mp4upload" in url ->
                    Mp4uploadExtractor(client).videosFromUrl(url, headers)

                "streamwish" in url || "wishembed" in url ->
                    StreamWishExtractor(client, headers).videosFromUrl(url)

                else ->
                    UniversalExtractor(client).videosFromUrl(url, headers)
            }
        }.getOrDefault(emptyList())
    }

    // ============================== Helpers ===============================

    private fun parseAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            val anchor = element.selectFirst("a")!!
            setUrlWithoutDomain(anchor.attr("href"))
            title = element.selectFirst(".Title, h2.Title")?.text() ?: ""
            thumbnail_url = element.selectFirst("img")?.attr("abs:src")
                ?: element.selectFirst("img")?.attr("data-src")
        }
    }

    // ============================== Filters ===============================

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Los filtros se ignoran al buscar por texto"),
        GenreFilter(),
        TypeFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Género",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Kamen Rider", "kamen-rider"),
            Pair("Super Sentai", "super-sentai"),
            Pair("Power Rangers", "power-rangers"),
            Pair("Ultraman", "ultraman"),
            Pair("Metal Hero", "metal-hero"),
            Pair("Garo", "garo"),
            Pair("Tokusatsu", "tokusatsu"),
            Pair("Especial", "especial"),
            Pair("Películas", "peliculas"),
        ),
    )

    private class TypeFilter : UriPartFilter(
        "Tipo",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Series", "series"),
            Pair("Películas", "peliculas"),
        ),
    )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
