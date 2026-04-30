package eu.kanade.tachiyomi.animeextension.es.simpsonizados

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
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
    override val latestUpdatesPath = "cap"

    // ======== Popular ========
    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/temp/page/$page", headers)

    override fun popularAnimeSelector() = "div.content article > div.poster"

    override fun popularAnimeNextPageSelector() =
        "div.resppages > a > span.fa-chevron-right"

    // ======== Anime Details ========
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        thumbnail_url = document.selectFirst("div.poster img")?.getImageUrl()
        title = document.selectFirst("div.data > h1, h1.title")?.text() ?: ""
        description = document.selectFirst("div.wp-content p, div#info p")?.text()
        genre = document.select("div.sgeneros a").eachText().joinToString()
    }

    // ======== Episodes ========
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

    override fun getSeasonEpisodes(season: Element): List<SEpisode> {
        return season.select("ul.episodios > li").mapNotNull { li ->
            runCatching {
                val numerando = li.selectFirst("div.numerando")!!.text().trim()
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

    // ======== Video Links ========
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()

        // Obtener post ID y servidores del HTML
        val servers = doc.select("ul#playeroptionsul > li")

        return servers.flatMap { server ->
            val postId = server.attr("data-post")
            val nume = server.attr("data-nume")
            val type = server.attr("data-type")
            val label = server.selectFirst("span.server")?.text() ?: "Video"

            runCatching {
                // Llamada a admin-ajax.php
                val body = FormBody.Builder()
                    .add("action", "doo_player_ajax")
                    .add("post", postId)
                    .add("nume", nume)
                    .add("type", type)
                    .build()

                val ajaxResponse = client.newCall(
                    POST("$baseUrl/wp-admin/admin-ajax.php", headers, body),
                ).execute()

                val embedUrl = ajaxResponse.body.string()
                    .let { json ->
                        json.substringAfter("\"embed_url\":\"")
                            .substringBefore("\"")
                            .replace("\\/", "/")
                    }

                when {
                    "videok.pro" in embedUrl -> getVideokVideos(embedUrl, label)
                    else -> emptyList()
                }
            }.getOrElse { emptyList() }
        }
    }

    private fun getVideokVideos(embedUrl: String, label: String): List<Video> {
        val fileCode = embedUrl.substringAfterLast("/").removeSuffix(".html").substringAfterLast("-")

        val postHeaders = headers.newBuilder()
            .add("Referer", embedUrl)
            .add("Origin", "https://videok.pro")
            .build()

        val postBody = FormBody.Builder()
            .add("op", "embed")
            .add("file_code", fileCode)
            .add("auto", "1")
            .add("referer", baseUrl)
            .build()

        val html = runCatching {
            client.newCall(POST("https://videok.pro/dl", postHeaders, postBody))
                .execute().body.string()
        }.getOrElse {
            return emptyList()
        }

        val masterUrl = html
            .substringAfter("sources: [{src: \"")
            .substringBefore("\"")
            .takeIf { it.contains("m3u8") }

        if (masterUrl == null) return emptyList()

        val masterHeaders = headers.newBuilder()
            .add("Referer", "https://videok.pro/")
            .build()

        val masterPlaylist = client.newCall(GET(masterUrl, masterHeaders)).execute().body.string()

        val videos = mutableListOf<Video>()
        val lines = masterPlaylist.lines()

        lines.forEachIndexed { i, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
            if (line.contains("I-FRAME")) return@forEachIndexed

            val resolution = Regex("RESOLUTION=(\\d+x\\d+)").find(line)?.groupValues?.get(1) ?: ""
            val height = resolution.substringAfter("x").toIntOrNull() ?: 0
            val quality = when {
                height >= 1080 -> "1080p"
                height >= 720 -> "720p"
                height >= 480 -> "480p"
                height > 0 -> "360p"
                else -> "Video"
            }

            val videoUrl = lines.getOrNull(i + 1)
                ?.takeIf { it.isNotBlank() && !it.startsWith("#") }
                ?.let { if (it.startsWith("http")) it else "${masterUrl.substringBeforeLast("/")}/$it" }
                ?: return@forEachIndexed

            videos.add(Video(videoUrl, "$label - $quality", videoUrl, masterHeaders))
        }
        return videos.ifEmpty { listOf(Video(masterUrl, label, masterUrl, masterHeaders)) }
    }
}
