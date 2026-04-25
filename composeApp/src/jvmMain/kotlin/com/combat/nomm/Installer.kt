package com.combat.nomm

import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import net.sf.sevenzipjbinding.*
import net.sf.sevenzipjbinding.util.ByteArrayStream
import java.io.FileOutputStream

object Installer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = ConcurrentHashMap<String, Mutex>()

    val bepinexStatus: StateFlow<TaskState?>
        field = MutableStateFlow<TaskState?>(null)

    val installStatuses: StateFlow<Map<String, TaskState>>
        field = MutableStateFlow<Map<String, TaskState>>(emptyMap())

    fun installMod(modId: String, url: String, dir: File, isBepInEx: Boolean = false, onSuccess: () -> Unit) {

        updateState(modId, TaskState(TaskState.Phase.DOWNLOADING, 0f, null, false), isBepInEx)

        scope.launch {
            val mutex = locks.getOrPut(modId) { Mutex() }
            mutex.withLock {
                val job = coroutineContext[Job]
                try {
                    val bytes = downloadWithRetry(job, url, modId, isBepInEx)
                    updateState(modId, TaskState(TaskState.Phase.EXTRACTING, null, null, false), isBepInEx)

                    withContext(Dispatchers.IO) {
                        if (!dir.exists()) dir.mkdirs()
                        extract(bytes, url, dir, isBepInEx)
                    }
                    onSuccess()
                } catch (e: Exception) {
                    e.printStackTrace()
                    if (e is CancellationException) throw e
                    if (!isBepInEx && !dir.deleteRecursively()) {
                        println("NOMM: failed to clean up directory ${dir.absolutePath}")
                    }
                    val (title, detail) = buildErrorNotification(e, url, modId)
                    ErrorNotifications.push(title, detail)
                    updateState(modId, TaskState(TaskState.Phase.EXTRACTING, null, e.localizedMessage), isBepInEx)
                } finally {
                    clearStatus(modId, isBepInEx)
                }
            }
        }
    }

    private suspend fun downloadWithRetry(
        parentJob: Job?,
        url: String,
        modId: String,
        isBepInEx: Boolean,
        attempts: Int = 3,
    ): ByteArray {
        var lastErr: Exception? = null
        repeat(attempts) { i ->
            try {
                val response = NetworkClient.client.get(url) {
                    onDownload { sent, total ->
                        val p = if ((total ?: 0L) > 0) sent.toFloat() / total!! else null
                        updateState(
                            modId,
                            TaskState(TaskState.Phase.DOWNLOADING, p, null, true) { parentJob?.cancel() },
                            isBepInEx
                        )
                    }
                }
                val status = response.status
                if (!status.isSuccess()) {
                    throw DownloadHttpException(status.value, status.description, url)
                }
                val bytes = response.readRawBytes()
                if (bytes.isEmpty()) {
                    throw DownloadHttpException(status.value, "Empty response body (0 bytes)", url)
                }
                return bytes
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastErr = e
                if (i < attempts - 1) delay(1000L * (i + 1))
            }
        }
        throw lastErr ?: Exception("Failed to download: $url")
    }
    
    private fun extract(bytes: ByteArray, url: String, target: File, noOverwrite: Boolean) {
        runCatching {
            ByteArrayStream(bytes, false).use { inStream ->

                val archive = try {
                    SevenZip.openInArchive(null, inStream)
                } catch (_: SevenZipException) {
                    null
                }

                if (archive != null) {
                    archive.use { arc ->
                        arc.simpleInterface.archiveItems.forEach { item ->
                            val file = File(target, item.path)
                            if (item.isFolder) {
                                file.mkdirs()
                            } else {
                                if (!noOverwrite || !file.exists()) {
                                    file.parentFile?.mkdirs()
                                    FileOutputStream(file).use { out ->
                                        item.extractSlow { data ->
                                            out.write(data)
                                            data.size
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val file = File(target, url.substringAfterLast("/"))
                    if (!noOverwrite || !file.exists()) {
                        file.parentFile?.mkdirs()
                        file.writeBytes(bytes)
                    }
                }
            }
        }.onFailure {
            if (!noOverwrite) target.deleteRecursively()
            throw it
        }
    }


    private fun updateState(id: String, state: TaskState, isBep: Boolean) {
        if (isBep) {
            bepinexStatus.value = state
        } else {
            installStatuses.update { it + (id to state) }
        }
    }

    private fun clearStatus(id: String, isBep: Boolean) {
        if (isBep) {
            bepinexStatus.value = null
        } else {
            installStatuses.update { it - id }
        }

    }

    private fun buildErrorNotification(e: Exception, url: String, modId: String): Pair<String, String> {
        val host = runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
        return when {
            e is DownloadHttpException && e.code == 403 ->
                "Download failed: Access denied (HTTP 403)" to
                "Mod: $modId\nURL: $url\n\nThe server rejected the request. Your IP may be rate-limited or the file requires authentication."
            e is DownloadHttpException && e.code == 404 ->
                "Download failed: File not found (HTTP 404)" to
                "Mod: $modId\nURL: $url\n\nThe download link is broken or the release was deleted. Try checking for an updated version."
            e is DownloadHttpException && e.code == 200 ->
                "Download failed: Empty response" to
                "Mod: $modId\nURL: $url\n\nThe server returned an empty file. The release asset may have been uploaded incorrectly."
            e is DownloadHttpException && e.code in 500..599 ->
                "Download failed: Server error (HTTP ${e.code})" to
                "Mod: $modId\nURL: $url\n\nThe download server is temporarily unavailable (${e.description}). Please try again later."
            e is DownloadHttpException ->
                "Download failed: HTTP ${e.code}" to
                "Mod: $modId\nURL: $url\n\n${e.description}"
            e is UnknownHostException ->
                "Download failed: DNS resolution error" to
                "Mod: $modId\nCould not resolve host: $host\n\nCheck your internet connection or DNS settings. If using a proxy, ensure it is correctly configured."
            e is ConnectException ->
                "Download failed: Connection refused" to
                "Mod: $modId\nHost: $host\n\nCould not connect to the server. It may be down, or the connection is blocked by a firewall."
            e is SocketTimeoutException || e is HttpRequestTimeoutException ->
                "Download failed: Connection timed out" to
                "Mod: $modId\nURL: $url\n\nThe server did not respond within the timeout period (60 s). Your connection may be too slow or the server is overloaded."
            e is SevenZipException ->
                "Installation failed: Archive extraction error" to
                "Mod: $modId\nURL: $url\n\nThe downloaded file could not be extracted (${e.localizedMessage}). The file may be corrupt — try reinstalling."
            e is IOException ->
                "Installation failed: Disk I/O error" to
                "Mod: $modId\n${e.javaClass.simpleName}: ${e.localizedMessage}\n\nCheck available disk space and write permissions for the game folder."
            else ->
                "Installation failed" to
                "Mod: $modId\nURL: $url\n${e.javaClass.simpleName}: ${e.localizedMessage}"
        }
    }
}

data class TaskState(
    val phase: Phase,
    val progress: Float? = 0f,
    val error: String? = null,
    val isCancellable: Boolean = true,
    private val onCancel: (() -> Unit)? = null,
) {
    enum class Phase { DOWNLOADING, EXTRACTING }

    fun cancel() {
        if (isCancellable) onCancel?.invoke()
    }
}

private class DownloadHttpException(val code: Int, val description: String, val url: String)
    : Exception("HTTP $code $description")