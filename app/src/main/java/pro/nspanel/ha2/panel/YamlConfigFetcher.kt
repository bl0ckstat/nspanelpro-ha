package pro.nspanel.ha2.panel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class YamlConfigFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {

    suspend fun download(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code}")
                }
                resp.body?.string().orEmpty()
            }
        }
    }
}
