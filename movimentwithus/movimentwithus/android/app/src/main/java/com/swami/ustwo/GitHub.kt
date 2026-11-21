package com.swami.ustwo

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class Post(val path: String, val by: String, val caption: String, val ts: Long) {
    fun toJson(): JSONObject = JSONObject()
        .put("path", path)
        .put("by", by)
        .put("caption", caption)
        .put("ts", ts)

    companion object {
        fun from(o: JSONObject) = Post(
            path = o.getString("path"),
            by = o.optString("by", "?"),
            caption = o.optString("caption", ""),
            ts = o.optLong("ts", 0L)
        )
    }
}

class GitHubException(val code: Int, message: String) : IOException(message)

/**
 * The private GitHub repo is the whole backend:
 *   photos/YYYY/MM/<millis>_<name>.jpg   – every photo ever sent
 *   feed.json                            – newest-first list of the last 200 posts
 * Same layout is used by the iOS Scriptable script, so both phones read/write the same data.
 */
class GitHub(private val cfg: Config) {

    private val repoUrl = "https://api.github.com/repos/${cfg.owner}/${cfg.repo}"

    private fun open(url: String, method: String, accept: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.useCaches = false
        conn.setRequestProperty("Authorization", "Bearer ${cfg.token}")
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("User-Agent", "UsTwo-Android")
        return conn
    }

    private fun HttpURLConnection.body(): ByteArray {
        try {
            val code = responseCode
            if (code !in 200..299) {
                val err = errorStream?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty()
                throw GitHubException(code, "GitHub $code: ${err.take(200)}")
            }
            return inputStream.use { it.readBytes() }
        } finally {
            disconnect()
        }
    }

    /** Throws if the repo doesn't exist or the token can't see it. */
    fun checkAccess() {
        val obj = JSONObject(open(repoUrl, "GET", JSON).body().toString(Charsets.UTF_8))
        if (!obj.optBoolean("private", false)) {
            throw GitHubException(400, "That repo is PUBLIC — anyone could see your photos. Make it private first.")
        }
    }

    /** Newest-first feed plus the sha needed to update it (null if feed.json doesn't exist yet). */
    fun readFeed(): Pair<List<Post>, String?> {
        return try {
            val raw = open("$repoUrl/contents/$FEED?t=${System.currentTimeMillis()}", "GET", JSON).body()
            val obj = JSONObject(raw.toString(Charsets.UTF_8))
            val b64 = obj.getString("content").replace(Regex("\\s"), "")
            val arr = JSONArray(String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8))
            val posts = (0 until arr.length()).map { Post.from(arr.getJSONObject(it)) }
            posts to obj.getString("sha")
        } catch (e: GitHubException) {
            if (e.code == 404) emptyList<Post>() to null else throw e
        }
    }

    fun download(path: String): ByteArray =
        open("$repoUrl/contents/$path", "GET", RAW).body()

    private fun put(path: String, payload: JSONObject) {
        val conn = open("$repoUrl/contents/$path", "PUT", JSON)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        conn.body()
    }

    /** Uploads a JPEG and puts it at the top of feed.json. */
    fun post(jpeg: ByteArray, caption: String): Post {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = now }
        val slug = cfg.name.lowercase(Locale.US).filter { it in 'a'..'z' || it in '0'..'9' }.take(20).ifEmpty { "me" }
        val path = String.format(
            Locale.US, "photos/%04d/%02d/%d_%s.jpg",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, now, slug
        )
        put(
            path,
            JSONObject()
                .put("message", "${cfg.name}: photo")
                .put("content", Base64.encodeToString(jpeg, Base64.NO_WRAP))
        )

        val post = Post(path, cfg.name, caption, now)
        // feed.json is updated with optimistic locking (sha). Retry if both of you post at the same moment.
        for (attempt in 0 until 3) {
            val (feed, sha) = readFeed()
            val next = JSONArray().put(post.toJson())
            feed.asSequence().filter { it.path != path }.take(MAX_FEED - 1).forEach { next.put(it.toJson()) }
            val payload = JSONObject()
                .put("message", "${cfg.name} posted")
                .put("content", Base64.encodeToString(next.toString(2).toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            if (sha != null) payload.put("sha", sha)
            try {
                put(FEED, payload)
                return post
            } catch (e: GitHubException) {
                if ((e.code != 409 && e.code != 422) || attempt == 2) throw e
            }
        }
        return post
    }

    companion object {
        const val FEED = "feed.json"
        const val MAX_FEED = 200
        private const val JSON = "application/vnd.github+json"
        private const val RAW = "application/vnd.github.raw"
    }
}

fun friendlyError(t: Throwable): String = when (t) {
    is GitHubException -> when (t.code) {
        400 -> t.message ?: "Bad request"
        401 -> "GitHub rejected the token (expired or wrong). Make a new one and sign in again."
        403 -> "The token doesn't have permission. It needs Contents: Read and write on this repo."
        404 -> "Repo not found. Check owner/repo, and that the token was given access to it."
        409, 422 -> "You both posted at the same moment — try again."
        else -> t.message ?: "GitHub error ${t.code}"
    }
    is IOException -> "No connection to GitHub. Check your internet."
    else -> t.message ?: t.toString()
}
