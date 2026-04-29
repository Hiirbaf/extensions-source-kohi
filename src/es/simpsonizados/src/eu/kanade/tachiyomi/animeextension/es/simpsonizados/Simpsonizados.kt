package eu.kanade.tachiyomi.animeextension.es.simpsonizados

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Simpsonizados : DooPlay(
    lang = "es",
    name = "Simpsonizados",
    baseUrl = "https://simpsonizados.me",
) {
    // ======== Latest = episodios recientes ========
    override val latestUpdatesPath = "cap"

    // ======== Popular = lista de temporadas ========
    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/temp/page/$page", headers)

    override fun popularAnimeNextPageSelector() =
        "div.resppages > a > span.fa-chevron-right"

    // El selector por defecto "article.w_item_a > a" puede no coincidir
    override fun popularAnimeSelector() = "div.items > article div.poster > a"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            val img = element.selectFirst("img")!!
            title = img.attr("alt")
            thumbnail_url = img.getImageUrl()
        }
    }

    // ======== Episodios ========
    // Cada entrada en /temp/ ya ES una temporada, no una serie con
    // varias temporadas anidadas. seasonListSelector no aplica aquí.
    // Sobreescribimos episodeListParse para tratar la página entera
    // como una sola temporada.
    override val seasonListSelector = "div.se-c"

    override val episodeSeasonPrefix = "Temporada"
    override val episodeMovieText = "Película"
}
