package eu.kanade.tachiyomi.animeextension.es.simpsonizados

import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

class Simpsonizados : DooPlay(
    lang = "es",
    name = "Simpsonizados",
    baseUrl = "https://simpsonizados.me",
) {
    override val episodeSeasonPrefix = "Temporada"
    override val episodeMovieText = "Película"

    override val latestUpdatesPath = "cap"

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/temp/page/$page", headers)

    override fun popularAnimeSelector() = "div.content article > div.poster"

    override fun popularAnimeNextPageSelector() =
        "div.resppages > a > span.fa-chevron-right"

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
        val doc = getRealAnimeDoc(response.asJsoup())
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
