package eu.kanade.tachiyomi.animeextension.es.simpsonizados

import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request

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
}
