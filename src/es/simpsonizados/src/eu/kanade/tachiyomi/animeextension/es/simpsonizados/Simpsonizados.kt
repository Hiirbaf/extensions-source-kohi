package eu.kanade.tachiyomi.animeextension.es.simpsonizados

import eu.kanade.tachiyomi.multisrc.dooplay.Dooplay

class Simpsonizados : Dooplay(
    "Simpsonizados",
    "https://simpsonizados.me",
    "es"
) {
    override val usesDecoding = true

    // Listado de temporadas
    override fun getMainPageUrl(page: Int) = if (page > 1) {
        "$baseUrl/temp/page/$page/"
    } else {
        "$baseUrl/temp"
    }

    // Selector CSS para cada temporada en la lista
    override val mainPageSelector = "div.items > article"

    // Selector CSS para episodios de cada temporada
    override val episodeListSelector = "ul.episodios > li"

    // Selector CSS para el enlace de episodio dentro del <li>
    override val episodeUrlSelector = "div.episodiotitle > a"

    // Nombre del episodio
    override val episodeNameSelector = "div.episodiotitle > a"

    // Fecha (opcional)
    override val episodeDateSelector = "span.date"

    // Los videos se obtienen vía la API de Dooplay
    override val playerApi = "https://simpsonizados.me/wp-json/dooplayer/v2/"
}
