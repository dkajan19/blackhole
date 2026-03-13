package com.dkajan.blackhole

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val _downloadStatus = MutableLiveData<String>()
    val downloadStatus: LiveData<String> = _downloadStatus

    private val _downloadProgress = MutableLiveData<Int>()
    val downloadProgress: LiveData<Int> = _downloadProgress

    private val _isDownloading = MutableLiveData<Boolean>()
    val isDownloading: LiveData<Boolean> = _isDownloading

    private val _downloadInfo = MutableLiveData<String>()
    val downloadInfo: LiveData<String> = _downloadInfo

    private val _isButtonEnabled = MutableLiveData(true)
    val isButtonEnabled: LiveData<Boolean> = _isButtonEnabled

    private val _downloadedVideoUri = MutableLiveData<Uri?>()
    val downloadedVideoUri: LiveData<Uri?> = _downloadedVideoUri

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val cobaltApiUrl = "https://api.cobalt.blackcat.sweeux.org/"
    //BACKUP "https://cblt.fariz.dev/"
    //DIFFERENT INSTANCES AT https://cobalt.directory/
    private val gson = Gson()

    fun downloadVideo(videoUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _downloadStatus.postValue("Fetching URL...")
            _downloadProgress.postValue(0)
            _downloadInfo.postValue("")
            _isButtonEnabled.postValue(false)

            try {
                if (videoUrl.contains("youtube.com") && !videoUrl.contains("/shorts/")) {
                    _downloadStatus.postValue("Only YouTube Shorts are supported!")
                    _isButtonEnabled.postValue(true)
                    return@launch
                }

                val jsonBody = JsonObject().apply {
                    addProperty("url", videoUrl)
                    addProperty("videoQuality", "max")
                }.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(cobaltApiUrl)
                    .addHeader("Accept", "application/json")
                    .post(jsonBody)
                    .build()

                val response = client.newCall(request).execute()

                //Log.d("RESPONSE",response.toString())
                if (!response.isSuccessful) {
                    _downloadStatus.postValue("Failed to fetch URL (" + response.code + ").")
                    _isDownloading.postValue(false)
                    _isButtonEnabled.postValue(true)
                    return@launch
                }

                val responseBody = response.body?.string()
                val cobaltResponse = gson.fromJson(responseBody, JsonObject::class.java)
                val downloadUrl = cobaltResponse.get("url")?.asString
                val filename = cobaltResponse.get("filename")?.asString ?: "video.mp4"

                if (downloadUrl != null) {
                    _isDownloading.postValue(true)
                    downloadFile(downloadUrl, filename)
                } else {
                    _downloadStatus.postValue("Could not get download link.")
                    _isDownloading.postValue(false)
                    _isButtonEnabled.postValue(true)
                }

            } catch (e: Exception) {
                Log.e("DownloadViewModel", "Error while downloading: ${e.message}")
                _downloadStatus.postValue("Something went wrong. Please try again.")
                _isDownloading.postValue(false)
                _isButtonEnabled.postValue(true)
            }
        }
    }

    private suspend fun downloadFile(url: String, filename: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _downloadStatus.postValue("Downloading...")
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) throw IOException("Unexpected response code: ${response.code}")

                val body = response.body ?: throw IOException("Empty response body.")
                val totalBytes = body.contentLength()

                val context = getApplication<Application>().applicationContext
                val videoCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }

                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Video.Media.MIME_TYPE, body.contentType()?.toString() ?: "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + File.separator + "BlackHole")
                    }
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(videoCollection, contentValues)
                    ?: throw IOException("Could not create file.")

                resolver.openOutputStream(uri)?.use { outputStream ->
                    body.byteStream().use { inputStream ->
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        var downloadedBytes = 0L

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            if (totalBytes > 0) {
                                val progress = (downloadedBytes * 100 / totalBytes).toInt()
                                _downloadProgress.postValue(progress)
                            } else {
                                _downloadProgress.postValue(-1)
                            }

                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead

                                if (totalBytes > 0) {
                                    val progress = (downloadedBytes * 100 / totalBytes).toInt()
                                    _downloadProgress.postValue(progress)

                                    val downloadedMB = downloadedBytes.toDouble() / (1024 * 1024)
                                    val totalMB = totalBytes.toDouble() / (1024 * 1024)
                                    _downloadInfo.postValue(String.format("%.2f MB / %.2f MB", downloadedMB, totalMB))
                                } else {
                                    val downloadedMB = downloadedBytes.toDouble() / (1024 * 1024)
                                    _downloadProgress.postValue(-1)
                                    _downloadInfo.postValue(String.format("%.2f MB / ?", downloadedMB))
                                }
                            }

                        }

                    }
                }

                _downloadedVideoUri.postValue(uri)
                _downloadStatus.postValue("Download complete!")

            } catch (e: Exception) {
                Log.e("DownloadViewModel", "Error while saving file: ${e.message}")
                _downloadStatus.postValue("Could not save the video.")
            } finally {
                _isDownloading.postValue(false)
                _isButtonEnabled.postValue(true)
            }
        }
    }

    suspend fun resolveRedirect(url: String): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .head()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    val location = response.header("Location")
                    if (location != null) {
                        return@withContext resolveRedirect(location)
                    }
                }
                return@withContext response.request.url.toString()
            }
        } catch (e: Exception) {
            Log.e("DownloadViewModel", "Error resolving redirect: ${e.message}")
            return@withContext url
        }
    }

}
