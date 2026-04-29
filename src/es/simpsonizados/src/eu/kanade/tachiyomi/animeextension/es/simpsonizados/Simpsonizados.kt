package eu.kanade.tachiyomi.animeextension.es.simpsonizados

import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay

class Simpsonizados : DooPlay(
    lang = "es",
    name = "Simpsonizados",
    baseUrl = "https://simpsonizados.me",
) {
    // La paginación de main page usa /temp en lugar de /episodes
    override val latestUpdatesPath = "temp"

    // Prefijo de temporada en español
    override val episodeSeasonPrefix = "Temporada"

    // Texto para películas (aunque este sitio es series, por si acaso)
    override val episodeMovieText = "Película"
}
