package com.dawwirli

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class DawwirliProvider : MainAPI() {
    override var mainUrl = "https://premium.dawwirli.watch"
    override var name = "Dawwirli"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf("home" to "الرئيسية")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun clean(n: String?): String {
        var s = (n ?: "").replace(Regex("\\[[^\\]]*\\]"), "")
            .replace(Regex("^\\s*[A-Z0-9][A-Z0-9-]{1,10}\\s*[-|:]\\s+"), "")
            .replace(Regex("\\s+-\\s*\\d{1,3}\\s*$"), "")
        repeat(3) {
            s = s.replace(Regex("\\s*\\((?:[A-Z]{2,3}|(?:19|20)\\d\\d)\\)\\s*$"), "")
                .replace(Regex("\\s*-\\s*(?:19|20)\\d\\d\\s*$"), "")
        }
        return s.trim()
    }

    private fun fixUrl2(u: String?): String? = when {
        u.isNullOrBlank() -> null
        u.startsWith("http") -> u
        u.startsWith("//") -> "https:$u"
        else -> mainUrl + (if (u.startsWith("/")) u else "/$u")
    }

    private suspend fun getJson(path: String): JSONObject? = try {
        val t = app.get(mainUrl + path, headers = mapOf("Accept" to "application/json")).text
        if (t.trimStart().startsWith("[")) JSONObject().put("items", JSONArray(t)) else JSONObject(t)
    } catch (e: Exception) {
        null
    }

    private fun JSONObject.toSearch(): SearchResponse? {
        val id = optString("id")
        if (id.isBlank()) return null
        val kind = optString("kind").ifBlank { optString("_kind") }
        val title = clean(optString("name")).ifBlank { return null }
        val poster = fixUrl2(optString("poster").ifBlank { optString("cover") })
        val data = "${if (kind == "series") "series" else "movie"}|$id|${optString("ext")}"
        return if (kind == "series") {
            newTvSeriesSearchResponse(title, data, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, data, TvType.Movie) { posterUrl = poster }
        }
    }

    private fun JSONArray?.toSearchList(): List<SearchResponse> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it)?.toSearch() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        val d = getJson("/api/ar/home") ?: return null
        val lists = ArrayList<HomePageList>()

        d.optJSONArray("hero").toSearchList().takeIf { it.isNotEmpty() }
            ?.let { lists.add(HomePageList("مميّز", it)) }

        val rows = d.optJSONArray("rows")
        if (rows != null) {
            for (i in 0 until rows.length()) {
                val r = rows.optJSONObject(i) ?: continue
                val items = r.optJSONArray("items").toSearchList()
                if (items.isNotEmpty()) lists.add(HomePageList(r.optString("title", "..."), items))
            }
        }
        if (lists.isEmpty()) return null
        return newHomePageResponse(lists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val d = getJson("/api/search?q=${enc(query)}") ?: return emptyList()
        return d.optJSONArray("items").toSearchList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val p = url.split("|")
        val kind = p.getOrNull(0) ?: return null
        val id = p.getOrNull(1) ?: return null

        if (kind == "series") {
            val d = getJson("/api/series_info?series_id=${enc(id)}") ?: return null
            val info = d.optJSONObject("info") ?: JSONObject()
            val epsObj = d.optJSONObject("episodes") ?: JSONObject()

            val episodes = ArrayList<Episode>()
            val seasonKeys = epsObj.keys().asSequence().filter { it != "0" }
                .sortedBy { it.toIntOrNull() ?: 0 }.toList()
            for (sk in seasonKeys) {
                val arr = epsObj.optJSONArray(sk) ?: continue
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val eid = e.optString("id")
                    if (eid.isBlank()) continue
                    val num = e.optString("episode_num").toIntOrNull()
                    val ext = e.optString("container_extension").ifBlank { "mkv" }
                    val rawTitle = e.optString("title").replace(Regex("^.*S\\d+E\\d+\\s*-?\\s*", RegexOption.IGNORE_CASE), "")
                    episodes.add(
                        newEpisode("series|$eid|$ext") {
                            this.name = clean(rawTitle).ifBlank { "الحلقة ${num ?: (i + 1)}" }
                            this.season = sk.toIntOrNull()
                            this.episode = num
                        }
                    )
                }
            }
            return newTvSeriesLoadResponse(clean(info.optString("name")), url, TvType.TvSeries, episodes) {
                posterUrl = fixUrl2(info.optString("cover"))
                backgroundPosterUrl = fixUrl2(Regex("https?://[^'\"\\]\\s,]+").find(info.optString("backdrop_path"))?.value)
                plot = info.optString("plot").ifBlank { null }
                year = info.optString("releaseDate").take(4).toIntOrNull()
                tags = info.optString("genre").split(",", "،", "/").map { it.trim() }.filter { it.isNotEmpty() }
            }
        }

        val d = getJson("/api/info?id=${enc(id)}") ?: return null
        val info = d.optJSONObject("info") ?: JSONObject()
        val md = d.optJSONObject("movie_data") ?: JSONObject()
        val ext = md.optString("container_extension").ifBlank { p.getOrNull(2).orEmpty().ifBlank { "mp4" } }
        val title = clean(md.optString("name").ifBlank { info.optString("name") })

        return newMovieLoadResponse(title, url, TvType.Movie, "movie|$id|$ext") {
            posterUrl = fixUrl2(info.optString("movie_image").ifBlank { info.optString("cover_big") })
            backgroundPosterUrl = fixUrl2(Regex("https?://[^'\"\\]\\s,]+").find(info.optString("backdrop_path"))?.value)
            plot = info.optString("plot").ifBlank { info.optString("description") }.ifBlank { null }
            year = info.optString("releasedate").take(4).toIntOrNull()
            tags = info.optString("genre").split(",", "،", "/").map { it.trim() }.filter { it.isNotEmpty() }
            duration = ((info.optString("duration_secs").toDoubleOrNull() ?: 0.0) / 60).toInt().takeIf { it > 0 }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val p = data.split("|")
        val kind = p.getOrNull(0) ?: return false
        val id = p.getOrNull(1) ?: return false
        val ext = p.getOrNull(2).orEmpty().ifBlank { "mp4" }

        val url = "$mainUrl/vod?kind=$kind&id=${enc(id)}&ext=${enc(ext)}"
        callback(
            newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
