package eu.kanade.tachiyomi.animeextension.es.shadowrangers

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
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
        val animes = document.select("article.item.tvshows").map { parseAnimeFromElement(it) }
        val hasNextPage = document.selectFirst("a.next.page-numbers") != null
        return AnimesPage(animes, hasNextPage)
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/capitulos/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("article.item.se.episodes").map { element ->
            SAnime.create().apply {
                val anchor = element.selectFirst("div.data h3 a")!!
                setUrlWithoutDomain(anchor.attr("href"))
                title = anchor.text().trim()
                thumbnail_url = element.selectFirst("div.poster img")?.attr("abs:src")
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
        val episodes = mutableListOf<SEpisode>()

        doc.select("#episodes ul.episodios li").forEach { li ->
            val anchor = li.selectFirst("div.episodiotitle a") ?: return@forEach
            val epNumText = li.selectFirst("div.numerando")?.text()?.trim() // "1 - 1"
            val epNum = epNumText?.split("-")?.getOrNull(1)?.trim()?.toFloatOrNull() ?: 0f
            val epName = anchor.text().trim()
            val dateUpload = li.selectFirst("span.date")?.text()
            episodes.add(
                SEpisode.create().apply {
                    setUrlWithoutDomain(anchor.attr("href"))
                    name = epName
                    episode_number = epNum
                },
            )
        }

        if (episodes.isEmpty()) {
            episodes.add(
                SEpisode.create().apply {
                    setUrlWithoutDomain(response.request.url.toString().removePrefix(baseUrl))
                    name = "Película"
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

        val serverItems = doc.select("ul.TPlayerNv li[data-post][data-nume], #playeroptionsul li[data-post]")

        if (serverItems.isNotEmpty()) {
            val postId = serverItems.first()!!.attr("data-post")
            val nonce = doc.select("script").mapNotNull { script ->
                Regex("""["']?nonce["']?\s*:\s*["']([a-f0-9]+)["']""")
                    .find(script.data())?.groupValues?.get(1)
            }.firstOrNull() ?: ""

            serverItems.forEach { item ->
                val nume = item.attr("data-nume")
                val type = item.attr("data-type").ifEmpty { "episode" }
                val serverLabel = item.selectFirst("span, .srvName")?.text()?.trim()
                    ?: "Server $nume"
                val embedUrl = fetchPlayerUrl(postId, nume, type, nonce)
                if (embedUrl.isNotEmpty()) {
                    videos.addAll(extractVideosFromEmbed(embedUrl, serverLabel))
                }
            }
        }

        // Fallback: look for iframes directly
        if (videos.isEmpty()) {
            doc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("abs:src")
                if (src.isNotEmpty()) {
                    videos.addAll(extractVideosFromEmbed(src, "Server"))
                }
            }
        }

        return videos
    }

    private fun fetchPlayerUrl(postId: String, nume: String, type: String, nonce: String): String {
        return runCatching {
            val bodyBuilder = FormBody.Builder()
                .add("action", "doo_player_ajax")
                .add("post", postId)
                .add("nume", nume)
                .add("type", type)
            if (nonce.isNotEmpty()) bodyBuilder.add("nonce", nonce)

            val json = client.newCall(
                POST("$baseUrl/wp-admin/admin-ajax.php", headers, bodyBuilder.build()),
            ).execute().body.string()

            // Response: {"embed_url":"https:\/\/...","type":"iframe"}
            Regex(""""embed_url"\s*:\s*"([^"]+)"""")
                .find(json)?.groupValues?.get(1)
                ?.replace("\\/", "/") ?: ""
        }.getOrDefault("")
    }

    private fun extractVideosFromEmbed(url: String, serverName: String): List<Video> {
        return runCatching {
            when {
                "vkvideo.ru" in url || "vk.com" in url ->
                    VkExtractor(client, headers).videosFromUrl(url, prefix = "$serverName - ")
                "voe.sx" in url || "voe-network" in url ->
                    VoeExtractor(client).videosFromUrl(url, prefix = "$serverName - ")
                else ->
                    emptyList()
            }
        }.getOrDefault(emptyList())
    }

    // ============================== Helpers ===============================

    private fun parseAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            val anchor = element.selectFirst("div.poster a")!!
            setUrlWithoutDomain(anchor.attr("href"))
            title = element.selectFirst("h3 a")?.text() ?: ""
            thumbnail_url = element.selectFirst("div.poster img")?.attr("abs:src")
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
