package eu.kanade.tachiyomi.animeextension.es.misitio

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class MiSitio : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "MiSitio"

    // ⚠️ Reemplaza con tu dominio cuando lo tengas
    override val baseUrl = "https://javenspanish.com/"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }

    // ==================== POPULAR ====================

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) baseUrl else "$baseUrl/page/$page/"
        return GET(url, headers)
    }

    override fun popularAnimeSelector() = "article.cpg-card"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("a[href]")!!
        anime.setUrlWithoutDomain(link.attr("href"))
        anime.title = element.selectFirst("h3.cpg-title, h2, h3, .entry-title")
            ?.text()?.trim() ?: link.attr("title")
        anime.thumbnail_url = element.selectFirst("picture img")?.attr("src")
            ?: element.selectFirst("noscript img")?.attr("src")
            ?: element.selectFirst("img")?.attr("data-src")
            ?: element.selectFirst("img")?.attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector() = "a.next.page-numbers, .cpg-pagination a.next, nav.navigation a.next"

    // ==================== LATEST ====================

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // ==================== SEARCH ====================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val categoryFilter = filters.filterIsInstance<CategoryFilter>().firstOrNull()

        return when {
            query.isNotEmpty() -> {
                val pageParam = if (page > 1) "&paged=$page" else ""
                GET("$baseUrl/?s=$query$pageParam", headers)
            }
            categoryFilter != null && categoryFilter.state != 0 -> {
                val pageParam = if (page > 1) "/page/$page/" else "/"
                GET("$baseUrl/category/${categorySlugs[categoryFilter.state]}$pageParam", headers)
            }
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // ==================== FILTERS ====================

    // ⚠️ Reemplaza con las categorías reales de tu sitio
    private val categoryNames = arrayOf(
        "Todas",
        "Anal",
        "Casadas",
        "Colegialas",
        "Cornudos",
        "Cuñada",
        "Desconocidos",
        "Familia",
        "Hermanastros",
        "Interracial",
        "Jefes",
        "Live-Action",
        "Madrastra",
        "MILF",
        "Novios",
        "Orgias",
        "Otros",
        "Padrastro",
        "Publico",
        "Suegros",
        "Tio",
        "Trabajo",
        "Trios",
        "Vecinos",
    )
    private val categorySlugs = arrayOf(
        "",
        "anal",
        "casadas",
        "colegialas",
        "cornudos",
        "cuñada",
        "desconocidos",
        "familia",
        "hermanastros",
        "interracial",
        "hefes",
        "Live-Action",
        "madrastra",
        "MILF",
        "novios",
        "orgias",
        "otros",
        "padrastro",
        "publico",
        "suegros",
        "tio",
        "trabajo",
        "trios",
        "vecinos",
    )

    private class CategoryFilter(names: Array<String>) : AnimeFilter.Select<String>(
        "Categoría",
        names,
    )

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Los filtros se ignoran si hay búsqueda por texto"),
        CategoryFilter(categoryNames),
    )

    // ==================== DETAILS ====================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()

        anime.title = document.selectFirst("h1.entry-title, h1.elementor-heading-title")
            ?.text()?.trim() ?: ""

        anime.thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".entry-content img, .elementor-widget-image img")
                ?.let { it.attr("data-lazy-src").ifEmpty { it.attr("src") } }

        anime.description = document.selectFirst(
            ".entry-content p, .elementor-text-editor p",
        )?.text()?.trim()

        anime.genre = document.select("a[rel=tag], .elementor-post-info__terms-list a")
            .joinToString(", ") { it.text() }

        anime.status = SAnime.COMPLETED

        return anime
    }

    // ==================== EPISODES ====================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episode = SEpisode.create()

        episode.name = "Película"
        episode.episode_number = 1f
        episode.setUrlWithoutDomain(response.request.url.toString())

        val dateText = document.selectFirst(
            "time.entry-date, time[datetime], .elementor-post-info__item--type-date",
        )?.attr("datetime")

        if (!dateText.isNullOrEmpty()) {
            runCatching {
                episode.date_upload = SimpleDateFormat(
                    "yyyy-MM-dd",
                    Locale.getDefault(),
                ).parse(dateText.take(10))?.time ?: 0L
            }
        }

        return listOf(episode)
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ==================== VIDEO ====================

    private fun refererHeaders(referer: String): Headers = headers.newBuilder()
        .set("Referer", referer)
        .build()

    // VD — VidHideVip: desempaqueta eval/packed y extrae m3u8
    private fun videosFromVidHide(embedUrl: String, label: String): List<Video> =
        runCatching {
            val doc = client.newCall(GET(embedUrl, refererHeaders(baseUrl))).execute().asJsoup()
            val script = doc.selectFirst("script:containsData(m3u8)")?.data()
                ?: return emptyList()
            val unpacked = if (script.contains("eval(function(p,a,c")) {
                JsUnpacker.unpackAndCombine(script) ?: script
            } else {
                script
            }
            val m3u8 = Regex("""https[^"'\s]*\.m3u8[^"'\s]*""").find(unpacked)
                ?.value ?: return emptyList()
            listOf(Video(m3u8, label, m3u8, refererHeaders(embedUrl)))
        }.getOrElse { emptyList() }

    // NT — player.subespanolvip.com: extrae m3u8 por regex
    private fun videosFromSubespanol(embedUrl: String, label: String): List<Video> =
        runCatching {
            val doc = client.newCall(GET(embedUrl, refererHeaders(baseUrl))).execute().asJsoup()
            val script = doc.selectFirst("script:containsData(m3u8)")?.data() ?: doc.html()
            val unpacked = if (script.contains("eval(function(p,a,c")) {
                JsUnpacker.unpackAndCombine(script) ?: script
            } else {
                script
            }
            val m3u8 = Regex("""https[^"'\s]*\.m3u8[^"'\s]*""").find(unpacked)
                ?.value ?: return emptyList()
            listOf(Video(m3u8, label, m3u8, refererHeaders(embedUrl)))
        }.getOrElse { emptyList() }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        document.select("div.elementor-tab-content").forEachIndexed { index, tab ->
            val iframe = tab.selectFirst("iframe") ?: return@forEachIndexed
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-lazy-src") }
            if (src.isEmpty()) return@forEachIndexed

            val tabNumber = tab.attr("data-tab")
            val tabLabel = document.selectFirst(
                "div.elementor-tab-title[data-tab=$tabNumber]:not(.elementor-tab-mobile-title)",
            )?.text()?.trim() ?: "Servidor ${index + 1}"

            val extracted: List<Video> = when {
                // VIP — m3u8 directo en el src del iframe
                src.contains(".m3u8") -> {
                    listOf(Video(src, tabLabel, src, refererHeaders(baseUrl)))
                }

                // VD — VidHideVip
                "vidhidevip" in src || "vidhide" in src -> {
                    videosFromVidHide(src, tabLabel)
                }

                // FM — FileMoon
                "filemoon" in src -> {
                    filemoonExtractor.videosFromUrl(src, prefix = "$tabLabel - ", headers = refererHeaders(baseUrl))
                }

                // NT — player.subespanolvip.com
                "subespanolvip" in src -> {
                    videosFromSubespanol(src, tabLabel)
                }

                // Fallback — StreamWish y dominios desconocidos
                else -> {
                    streamWishExtractor.videosFromUrl(src, videoNameGen = { "$tabLabel - $it" })
                }
            }

            videos.addAll(extracted)
        }

        return videos.ifEmpty {
            listOf(Video("", "Sin video encontrado", ""))
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ==================== PREFERENCES ====================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Servidor preferido"
            entries = arrayOf("VIP", "VD", "FM", "NT", "Primero disponible")
            entryValues = arrayOf("VIP", "VD", "FM", "NT", "Primero disponible")
            setDefaultValue("Primero disponible")
            summary = "%s"
        }.also(screen::addPreference)
    }

    override fun List<Video>.sort(): List<Video> {
        val preferred = preferences.getString(PREF_SERVER_KEY, "Primero disponible") ?: return this
        if (preferred == "Primero disponible") return this
        return sortedWith(compareByDescending { it.quality.startsWith(preferred, ignoreCase = true) })
    }

    companion object {
        private const val PREF_SERVER_KEY = "preferred_server"
    }
}
