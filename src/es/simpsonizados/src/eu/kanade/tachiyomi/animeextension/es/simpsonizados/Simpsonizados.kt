package eu.kanade.tachiyomi.animeextension.es.simpsonizados

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Simpsonizados : DooPlay(
    lang = "es",
    name = "Simpsonizados",
    baseUrl = "https://simpsonizados.me",
) {
    override val episodeSeasonPrefix = "Temporada"
    override val episodeMovieText = "Película"

    // La página de temporada ya tiene los episodios directamente,
    // no necesita redirigir a ningún lado
    override val animeMenuSelector = "div.pag_episodes div.item a[href] i.fa-bars"

    override fun getRealAnimeDoc(document: org.jsoup.nodes.Document) = document

    override val latestUpdatesPath = "cap"

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/temp/page/$page", headers)

    override fun popularAnimeSelector() = "div.content article > div.poster"

    override fun popularAnimeNextPageSelector() =
        "div.resppages > a > span.fa-chevron-right"

    override fun animeDetailsParse(document: org.jsoup.nodes.Document): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(document.location())
            thumbnail_url = document.selectFirst("div.poster img")?.getImageUrl()
            title = document.selectFirst("div.data > h1, h1.title")?.text() ?: ""
            description = document.selectFirst("div.wp-content p, div#info p")?.text()
            genre = document.select("div.sgeneros a").eachText().joinToString()
        }
    }

    // La estructura del sitio es:
    // div con número de season > ul > li > a (episodio)
    override val seasonListSelector = "div#seasons > div"

    override fun getSeasonEpisodes(season: Element): List<SEpisode> {
        return season.select("ul.episodios > li").mapNotNull { li ->
            runCatching {
                val numerando = li.selectFirst("div.numerando")!!.text().trim()
                // "35 - 1" -> seasonNum=35, epNum=1
                val (seasonNum, epNum) = numerando.split(" - ").map { it.trim() }
                val a = li.selectFirst("div.episodiotitle > a")!!
                SEpisode.create().apply {
                    name = "$episodeSeasonPrefix $seasonNum x $epNum - ${a.text()}"
                    episode_number = epNum.toFloatOrNull() ?: 0F
                    date_upload = li.selectFirst("span.date")?.text()?.toDate() ?: 0L
                    setUrlWithoutDomain(a.attr("href"))
                }
            }.getOrNull()
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val seasons = doc.select(seasonListSelector)
        return if (seasons.isEmpty()) {
            listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(doc.location())
                    episode_number = 1F
                    name = episodeMovieText
                },
            )
        } else {
            seasons.flatMap(::getSeasonEpisodes).reversed()
        }
    }
}
