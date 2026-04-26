package eu.kanade.tachiyomi.animeextension.es.misitio

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
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

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ==================== POPULAR ====================

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) baseUrl else "$baseUrl/page/$page/"
        return GET(url, headers)
    }

    override fun popularAnimeSelector() = "article.post, div.elementor-post"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("a[href]")!!
        anime.setUrlWithoutDomain(link.attr("href"))
        anime.title = element.selectFirst("h2, h3, .entry-title, .elementor-post__title")
            ?.text()?.trim() ?: link.attr("title")
        anime.thumbnail_url = element.selectFirst("img")?.let {
            it.attr("data-lazy-src").ifEmpty { it.attr("src") }
        }
        return anime
    }

    override fun popularAnimeNextPageSelector() = "a.next.page-numbers, .elementor-pagination a.next"

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
        "Acción",
        "Comedia",
        "Drama",
        "Terror",
        "Ciencia ficción",
    )
    private val categorySlugs = arrayOf(
        "",
        "accion",
        "comedia",
        "drama",
        "terror",
        "ciencia-ficcion",
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

        anime.thumbnail_url = document.selectFirst(
            "meta[property=og:image]",
        )?.attr("content")
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
    // En este tipo de sitio cada post ES la película (un solo episodio)

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

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        // Busca todos los iframes embebidos en el post
        val iframes = document.select(
            ".entry-content iframe, .elementor-widget-video iframe, " +
                ".elementor-text-editor iframe, article iframe",
        )

        iframes.forEachIndexed { index, iframe ->
            val src = iframe.attr("src").ifEmpty { iframe.attr("data-lazy-src") }
            if (src.isNotEmpty()) {
                val serverName = detectServer(src, index + 1)
                videos.add(Video(src, serverName, src))
            }
        }

        // Fallback: busca iframes en el HTML completo (a veces Elementor los pone en data-*)
        if (videos.isEmpty()) {
            val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            iframeRegex.findAll(document.html()).forEachIndexed { index, match ->
                val src = match.groupValues[1]
                if (src.startsWith("http") && !src.contains(baseUrl.removePrefix("https://"))) {
                    val serverName = detectServer(src, index + 1)
                    videos.add(Video(src, serverName, src))
                }
            }
        }

        return videos.ifEmpty {
            listOf(Video("", "Sin video encontrado", ""))
        }
    }

    private fun detectServer(url: String, index: Int): String {
        return when {
            "streamtape" in url -> "Streamtape"
            "doodstream" in url || "dood." in url -> "Doodstream"
            "streamwish" in url -> "StreamWish"
            "filemoon" in url -> "FileMoon"
            "voe.sx" in url -> "VOE"
            "upstream" in url -> "Upstream"
            "mixdrop" in url -> "MixDrop"
            "mp4upload" in url -> "Mp4Upload"
            "okru" in url || "ok.ru" in url -> "OK.ru"
            "dailymotion" in url -> "Dailymotion"
            "youtube" in url -> "YouTube"
            else -> "Servidor $index"
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
            entries = arrayOf(
                "Streamtape",
                "Doodstream",
                "StreamWish",
                "FileMoon",
                "VOE",
                "Upstream",
                "MixDrop",
                "Primero disponible",
            )
            entryValues = arrayOf(
                "Streamtape",
                "Doodstream",
                "StreamWish",
                "FileMoon",
                "VOE",
                "Upstream",
                "MixDrop",
                "Primero disponible",
            )
            setDefaultValue("Primero disponible")
            summary = "%s"
        }.also(screen::addPreference)
    }

    override fun List<Video>.sort(): List<Video> {
        val preferred = preferences.getString(PREF_SERVER_KEY, "Primero disponible") ?: return this
        if (preferred == "Primero disponible") return this
        return sortedWith(compareByDescending { it.quality.contains(preferred, ignoreCase = true) })
    }

    companion object {
        private const val PREF_SERVER_KEY = "preferred_server"
    }
}
