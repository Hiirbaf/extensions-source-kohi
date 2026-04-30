package eu.kanade.tachiyomi.animeextension.es.shadowrangers

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class ShadowRangersFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(ShadowRangers())
}
