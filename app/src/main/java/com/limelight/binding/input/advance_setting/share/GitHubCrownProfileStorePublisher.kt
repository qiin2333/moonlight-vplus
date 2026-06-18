package com.limelight.binding.input.advance_setting.share

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object GitHubCrownProfileStorePublisher {
    const val STORE_OWNER = "qiin2333"
    const val STORE_REPO = "crown-profiles"
    const val STORE_BRANCH = "main"
    private const val API_ROOT = "https://api.github.com"
    private const val API_VERSION = "2022-11-28"
    private const val INDEX_PATH = "index/v1.json"
    private const val HTTP_UNPROCESSABLE_ENTITY = 422

    data class PublishRequest(
        val profileName: String,
        val summary: String,
        val author: String,
        val game: String,
        val tags: List<String>,
        val bundleJson: String
    )

    data class PublishResult(
        val pullRequestUrl: String,
        val branchName: String,
        val profilePath: String
    )

    class GitHubCrownStoreException(
        message: String,
        val authorizationFailure: Boolean = false
    ) : IOException(message)

    @Throws(IOException::class)
    fun publish(accessToken: String, request: PublishRequest): PublishResult {
        if (accessToken.isBlank()) {
            throw GitHubCrownStoreException("GitHub authorization is required", authorizationFailure = true)
        }

        val userLogin = fetchLogin(accessToken)
        val publishOwner = ensurePublishRepository(accessToken, userLogin)
        syncFork(accessToken, publishOwner)

        val baseSha = getRefSha(accessToken, publishOwner, STORE_BRANCH)
        val branchName = "crown-profile/${slug(request.profileName)}-${System.currentTimeMillis()}"
        createBranch(accessToken, publishOwner, branchName, baseSha)

        val profilePath = buildProfilePath(request)
        val baseIndex = getContent(accessToken, publishOwner, STORE_REPO, INDEX_PATH, STORE_BRANCH)
        val updatedAt = formatIso8601(System.currentTimeMillis())
        val updatedIndex = appendProfileToIndex(baseIndex.text, request, profilePath, updatedAt)

        putContent(
            accessToken = accessToken,
            owner = publishOwner,
            path = profilePath,
            branch = branchName,
            content = request.bundleJson,
            sha = null,
            message = "Add ${request.profileName} Crown profile"
        )

        val forkIndex = getContent(accessToken, publishOwner, STORE_REPO, INDEX_PATH, branchName)
        putContent(
            accessToken = accessToken,
            owner = publishOwner,
            path = INDEX_PATH,
            branch = branchName,
            content = updatedIndex,
            sha = forkIndex.sha,
            message = "Update Crown profile index"
        )

        val pullRequestUrl = createPullRequest(accessToken, publishOwner, branchName, request)
        return PublishResult(
            pullRequestUrl = pullRequestUrl,
            branchName = branchName,
            profilePath = profilePath
        )
    }

    fun buildProfilePath(request: PublishRequest): String {
        val gamePath = slug(request.game.ifBlank { "general" })
        val profileName = slug(request.profileName)
        val bundleId = runCatching {
            JSONObject(request.bundleJson).optString("bundleId", "")
        }.getOrDefault("")
        val suffix = bundleId.substringAfterLast('.', "").takeIf { it.length >= 6 }
            ?: request.bundleJson.hashCode().toUInt().toString(16)
        return "profiles/$gamePath/$profileName-$suffix${CrownProfileShareManager.FILE_EXTENSION}"
    }

    fun appendProfileToIndex(
        indexText: String,
        request: PublishRequest,
        profilePath: String,
        updatedAt: String
    ): String {
        val root = JSONObject(indexText)
        if (root.optString("kind") != CrownProfileShareManager.INDEX_KIND ||
            root.optInt("schemaVersion", -1) != CrownProfileShareManager.SCHEMA_VERSION) {
            throw GitHubCrownStoreException("Crown store index has an unsupported schema")
        }

        val bundle = JSONObject(request.bundleJson)
        val bundleId = bundle.optString("bundleId", "").trim()
        val layoutBasis = bundle.optJSONObject("layoutBasis")
        val url = "../$profilePath"
        val profiles = root.optJSONArray("profiles") ?: JSONArray().also { root.put("profiles", it) }
        for (index in 0 until profiles.length()) {
            val existing = profiles.optJSONObject(index) ?: continue
            if (existing.optString("url") == url) {
                throw GitHubCrownStoreException("This Crown profile path is already published")
            }
            if (bundleId.isNotBlank() && existing.optString("bundleId") == bundleId) {
                throw GitHubCrownStoreException("This Crown profile bundle is already published")
            }
        }

        val tags = JSONArray()
        request.tags
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { tags.put(it) }

        profiles.put(
            JSONObject()
                .put("bundleId", bundleId)
                .put("name", request.profileName.trim().ifBlank { "Crown Profile" })
                .put("summary", request.summary.trim())
                .put("author", request.author.trim())
                .put("game", request.game.trim())
                .put("tags", tags)
                .put("updatedAt", updatedAt)
                .put("url", url)
                .apply {
                    layoutBasis?.let { put("layoutBasis", it) }
                }
        )
        root.put("generatedAt", updatedAt)
        return root.toString(2)
    }

    @Throws(IOException::class)
    private fun fetchLogin(accessToken: String): String {
        val response = request(accessToken, "GET", "$API_ROOT/user")
        ensureSuccess(response, "Unable to read GitHub user")
        return JSONObject(response.body).optString("login").takeIf { it.isNotBlank() }
            ?: throw GitHubCrownStoreException("GitHub user login is unavailable")
    }

    @Throws(IOException::class)
    private fun ensurePublishRepository(accessToken: String, login: String): String {
        if (login == STORE_OWNER) {
            return STORE_OWNER
        }

        val existing = request(accessToken, "GET", repoUrl(login))
        if (existing.code == HttpURLConnection.HTTP_OK) {
            val repo = JSONObject(existing.body)
            if (!repo.optBoolean("fork", false)) {
                throw GitHubCrownStoreException("GitHub account already has a non-fork $STORE_REPO repository")
            }
            return login
        }

        val created = request(accessToken, "POST", "${repoUrl(STORE_OWNER)}/forks", "{}")
        if (created.code !in 200..299 && created.code != HttpURLConnection.HTTP_ACCEPTED &&
            created.code != HTTP_UNPROCESSABLE_ENTITY) {
            throw apiException(created, "Unable to create Crown Store fork")
        }

        repeat(10) {
            val fork = request(accessToken, "GET", repoUrl(login))
            if (fork.code == HttpURLConnection.HTTP_OK && JSONObject(fork.body).optBoolean("fork", false)) {
                return login
            }
            Thread.sleep(1000L)
        }
        throw GitHubCrownStoreException("GitHub fork is not ready yet")
    }

    @Throws(IOException::class)
    private fun syncFork(accessToken: String, owner: String) {
        if (owner == STORE_OWNER) {
            return
        }

        val response = request(
            accessToken,
            "POST",
            "${repoUrl(owner)}/merge-upstream",
            JSONObject().put("branch", STORE_BRANCH).toString()
        )
        if (response.code in 200..299 ||
            response.code == HttpURLConnection.HTTP_CONFLICT ||
            response.code == HTTP_UNPROCESSABLE_ENTITY ||
            response.code == HttpURLConnection.HTTP_NOT_FOUND) {
            return
        }
        throw apiException(response, "Unable to sync Crown Store fork")
    }

    @Throws(IOException::class)
    private fun getRefSha(accessToken: String, owner: String, branch: String): String {
        val response = request(accessToken, "GET", "${repoUrl(owner)}/git/ref/heads/${urlEncode(branch)}")
        ensureSuccess(response, "Unable to read GitHub branch")
        return JSONObject(response.body)
            .getJSONObject("object")
            .getString("sha")
    }

    @Throws(IOException::class)
    private fun createBranch(accessToken: String, owner: String, branch: String, sha: String) {
        val body = JSONObject()
            .put("ref", "refs/heads/$branch")
            .put("sha", sha)
            .toString()
        val response = request(accessToken, "POST", "${repoUrl(owner)}/git/refs", body)
        ensureSuccess(response, "Unable to create GitHub publish branch")
    }

    @Throws(IOException::class)
    private fun getContent(accessToken: String, owner: String, repo: String, path: String, ref: String): GitHubContent {
        val response = request(
            accessToken,
            "GET",
            "$API_ROOT/repos/$owner/$repo/contents/$path?ref=${urlEncode(ref)}"
        )
        ensureSuccess(response, "Unable to read $path")
        val json = JSONObject(response.body)
        val encoded = json.getString("content")
        val decoded = Base64.getMimeDecoder().decode(encoded).toString(Charsets.UTF_8)
        return GitHubContent(
            sha = json.getString("sha"),
            text = decoded
        )
    }

    @Throws(IOException::class)
    private fun putContent(
        accessToken: String,
        owner: String,
        path: String,
        branch: String,
        content: String,
        sha: String?,
        message: String
    ) {
        val body = JSONObject()
            .put("message", message)
            .put("branch", branch)
            .put("content", Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8)))
        sha?.let { body.put("sha", it) }

        val response = request(accessToken, "PUT", "${repoUrl(owner)}/contents/$path", body.toString())
        ensureSuccess(response, "Unable to write $path")
    }

    @Throws(IOException::class)
    private fun createPullRequest(
        accessToken: String,
        forkOwner: String,
        branchName: String,
        request: PublishRequest
    ): String {
        val body = JSONObject()
            .put("title", "Add ${request.profileName.trim().ifBlank { "Crown Profile" }}")
            .put("head", "$forkOwner:$branchName")
            .put("base", STORE_BRANCH)
            .put(
                "body",
                buildString {
                    append("Submitted from Moonlight V+.\n\n")
                    if (request.game.isNotBlank()) append("- Game: ${request.game}\n")
                    if (request.author.isNotBlank()) append("- Author: ${request.author}\n")
                    if (request.summary.isNotBlank()) append("- Summary: ${request.summary}\n")
                    if (request.tags.isNotEmpty()) append("- Tags: ${request.tags.joinToString(", ")}\n")
                }
            )
            .toString()

        val response = request(accessToken, "POST", "${repoUrl(STORE_OWNER)}/pulls", body)
        ensureSuccess(response, "Unable to open Crown Store pull request")
        return JSONObject(response.body).getString("html_url")
    }

    @Throws(IOException::class)
    private fun request(accessToken: String, method: String, urlString: String, body: String? = null): HttpResponse {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10000
        connection.readTimeout = 20000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.setRequestProperty("User-Agent", "Moonlight-VPlus-Crown-Store")
        connection.setRequestProperty("X-GitHub-Api-Version", API_VERSION)
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return HttpResponse(code, responseBody)
    }

    @Throws(IOException::class)
    private fun ensureSuccess(response: HttpResponse, fallback: String) {
        if (response.code in 200..299) return
        throw apiException(response, fallback)
    }

    private fun apiException(response: HttpResponse, fallback: String): GitHubCrownStoreException {
        val message = if (response.body.isBlank()) {
            fallback
        } else {
            runCatching {
                JSONObject(response.body).optString("message").takeIf { it.isNotBlank() }
            }.getOrNull() ?: fallback
        }
        return GitHubCrownStoreException(
            "$message (${response.code})",
            authorizationFailure = response.code == HttpURLConnection.HTTP_UNAUTHORIZED ||
                    response.code == HttpURLConnection.HTTP_FORBIDDEN
        )
    }

    private fun repoUrl(owner: String): String =
        "$API_ROOT/repos/$owner/$STORE_REPO"

    private fun slug(value: String): String {
        return value
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.', '_')
            .ifBlank { "crown-profile" }
    }

    private fun formatIso8601(timestampMs: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(timestampMs))
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private data class HttpResponse(val code: Int, val body: String)
    private data class GitHubContent(val sha: String, val text: String)
}
