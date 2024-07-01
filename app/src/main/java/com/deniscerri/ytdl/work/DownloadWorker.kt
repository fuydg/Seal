package com.deniscerri.ytdl.work

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Toast
import androidx.navigation.NavDeepLinkBuilder
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.afollestad.materialdialogs.utils.MDUtil.getStringArray
import com.deniscerri.ytdl.App
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.DBManager
import com.deniscerri.ytdl.database.models.HistoryItem
import com.deniscerri.ytdl.database.models.LogItem
import com.deniscerri.ytdl.database.repository.DownloadRepository
import com.deniscerri.ytdl.database.repository.LogRepository
import com.deniscerri.ytdl.database.repository.ResultRepository
import com.deniscerri.ytdl.util.Extensions.getMediaDuration
import com.deniscerri.ytdl.util.Extensions.toStringDuration
import com.deniscerri.ytdl.util.FileUtil
import com.deniscerri.ytdl.util.InfoUtil
import com.deniscerri.ytdl.util.NotificationUtil
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.util.Locale
import kotlin.random.Random


class DownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    @SuppressLint("RestrictedApi")
    override suspend fun doWork(): Result {
        if (isStopped) return Result.success()

        val notificationUtil = NotificationUtil(App.instance)
        val infoUtil = InfoUtil(context)
        val dbManager = DBManager.getInstance(context)
        val dao = dbManager.downloadDao
        val historyDao = dbManager.historyDao
        val resultDao = dbManager.resultDao
        val logRepo = LogRepository(dbManager.logDao)
        val resultRepo = ResultRepository(dbManager.resultDao, context)
        val handler = Handler(Looper.getMainLooper())
        val alarmScheduler = AlarmScheduler(context)
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val time = System.currentTimeMillis() + 6000
        val queuedItems = dao.getQueuedScheduledDownloadsUntil(time)
        val currentWork = WorkManager.getInstance(context).getWorkInfosByTag("download").await()
        if (currentWork.count{it.state == WorkInfo.State.RUNNING} > 1) return Result.success()

        // this is needed for observe sources call, so it wont create result items
        //val createResultItem = inputData.getBoolean("createResultItem", true)

        val confTmp = Configuration(context.resources.configuration)
        val currLang = sharedPreferences.getString("app_language", "")!!.ifEmpty { Locale.getDefault().language }.split("-")
        confTmp.setLocale(if (currLang.size == 1) Locale(currLang[0]) else Locale(currLang[0], currLang[1]))
        val metrics = DisplayMetrics()
        val resources = Resources(context.assets, metrics, confTmp)

        val pendingIntent = NavDeepLinkBuilder(context)
            .setGraph(R.navigation.nav_graph)
            .setDestination(R.id.downloadQueueMainFragment)
            .createPendingIntent()

        val workNotif = notificationUtil.createDefaultWorkerNotification()
        val foregroundInfo = ForegroundInfo(1000000000, workNotif)
        setForegroundAsync(foregroundInfo)

        queuedItems.collect { items ->
            if (isStopped) return@collect
            runningYTDLInstances.clear()
            val activeDownloads = dao.getActiveDownloadsList()
            activeDownloads.forEach {
                runningYTDLInstances.add(it.id)
            }

            val running = ArrayList(runningYTDLInstances)
            val useScheduler = sharedPreferences.getBoolean("use_scheduler", false)
            if (items.isEmpty() && running.isEmpty()) WorkManager.getInstance(context).cancelWorkById(this@DownloadWorker.id)

            if (useScheduler){
                if (items.none{it.downloadStartTime > 0L} && running.isEmpty() && !alarmScheduler.isDuringTheScheduledTime()) {
                    WorkManager.getInstance(context).cancelWorkById(this@DownloadWorker.id)
                }
            }
            val concurrentDownloads = sharedPreferences.getInt("concurrent_downloads", 1) - running.size
            val eligibleDownloads = items.take(if (concurrentDownloads < 0) 0 else concurrentDownloads).filter {  it.id !in running }

            eligibleDownloads.forEach{downloadItem ->
                val notification = notificationUtil.createDownloadServiceNotification(pendingIntent, downloadItem.title.ifEmpty { downloadItem.url }, downloadItem.id.toInt())
                notificationUtil.notify(downloadItem.id.toInt(), notification)

                CoroutineScope(Dispatchers.IO).launch {
                    val noCache = !sharedPreferences.getBoolean("cache_downloads", true) && File(FileUtil.formatPath(downloadItem.downloadPath)).canWrite()

                    val request = infoUtil.buildYoutubeDLRequest(downloadItem)
                    downloadItem.status = DownloadRepository.Status.Active.toString()
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(1500)
                        //update item if its incomplete
                        resultRepo.updateDownloadItem(downloadItem)?.apply {
                            val status = dao.checkStatus(this.id)
                            if (status == DownloadRepository.Status.Active){
                                dao.updateWithoutUpsert(this)
                            }
                        }
                    }

                    val cacheDir = FileUtil.getCachePath(context)
                    val tempFileDir = File(cacheDir, downloadItem.id.toString())
                    tempFileDir.delete()
                    tempFileDir.mkdirs()

                    val downloadLocation = downloadItem.downloadPath
                    val keepCache = sharedPreferences.getBoolean("keep_cache", false)
                    val logDownloads = sharedPreferences.getBoolean("log_downloads", false) && !downloadItem.incognito


                    val commandString = infoUtil.parseYTDLRequestString(request)
                    val logString = StringBuilder("\n ${commandString}\n\n")
                    val logItem = LogItem(
                        0,
                        downloadItem.title.ifBlank { downloadItem.url },
                        "Downloading:\n" +
                                "Title: ${downloadItem.title}\n" +
                                "URL: ${downloadItem.url}\n" +
                                "Type: ${downloadItem.type}\n" +
                                "Command: $logString",
                        downloadItem.format,
                        downloadItem.type,
                        System.currentTimeMillis(),
                    )


                    runBlocking {
                        if (logDownloads) logItem.id = logRepo.insert(logItem)
                        downloadItem.logID = logItem.id
                        dao.update(downloadItem)
                    }

                    val eventBus = EventBus.getDefault()

                    runCatching {
                        YoutubeDL.getInstance().destroyProcessById(downloadItem.id.toString())
                        YoutubeDL.getInstance().execute(request, downloadItem.id.toString()){ progress, _, line ->
                            eventBus.post(WorkerProgress(progress.toInt(), line, downloadItem.id))
                            val title: String = downloadItem.title.ifEmpty { downloadItem.url }
                            notificationUtil.updateDownloadNotification(
                                downloadItem.id.toInt(),
                                line, progress.toInt(), 0, title,
                                NotificationUtil.DOWNLOAD_SERVICE_CHANNEL_ID
                            )
                            CoroutineScope(Dispatchers.IO).launch {
                                if (logDownloads) {
                                    logRepo.update(line, logItem.id)
                                    logString.append("$line\n")
                                }
                            }
                        }
                    }.onSuccess {
                        resultRepo.updateDownloadItem(downloadItem)
                        val wasQuickDownloaded = resultDao.getCountInt() == 0
                        runBlocking {
                            var finalPaths : MutableList<String>?

                            if (noCache){
                                eventBus.post(WorkerProgress(100, "Scanning Files", downloadItem.id))
                                val outputSequence = it.out.split("\n")
                                finalPaths =
                                    outputSequence.asSequence()
                                    .filter { it.startsWith("'/storage") }
                                        .map { it.removeSuffix("\n") }
                                        .map { it.removeSurrounding("'", "'") }
                                    .toMutableList()

                                finalPaths.addAll(
                                            outputSequence.asSequence()
                                                .filter { it.startsWith("[SplitChapters]") && it.contains("Destination: ") }
                                                .map { it.split("Destination: ")[1] }
                                                .map { it.removeSuffix("\n") }
                                                .toList()
                                        )

                                finalPaths.sortBy { File(it).lastModified() }
                                finalPaths = finalPaths.distinct().toMutableList()
                                FileUtil.scanMedia(finalPaths, context)
                                if (finalPaths.isEmpty()){
                                    finalPaths = mutableListOf(context.getString(R.string.unfound_file))
                                }
                            }else{
                                //move file from internal to set download directory
                                eventBus.post(WorkerProgress(100, "Moving file to ${FileUtil.formatPath(downloadLocation)}", downloadItem.id))
                                try {
                                    finalPaths = withContext(Dispatchers.IO){
                                        FileUtil.moveFile(tempFileDir.absoluteFile,context, downloadLocation, keepCache){ p ->
                                            eventBus.post(WorkerProgress(p, "Moving file to ${FileUtil.formatPath(downloadLocation)}", downloadItem.id))
                                        }
                                    }.filter { !it.matches("\\.(description)|(txt)\$".toRegex()) }.toMutableList()

                                    if (finalPaths.isNotEmpty()){
                                        eventBus.post(WorkerProgress(100, "Moved file to ${FileUtil.formatPath(downloadLocation)}", downloadItem.id))
                                    }else{
                                        finalPaths = mutableListOf(context.getString(R.string.unfound_file))
                                    }
                                }catch (e: Exception){
                                    finalPaths = mutableListOf(context.getString(R.string.unfound_file))
                                    e.printStackTrace()
                                    handler.postDelayed({
                                        Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                                    }, 1000)
                                }
                            }


                            val nonMediaExtensions = mutableListOf<String>().apply {
                                addAll(context.getStringArray(R.array.thumbnail_containers_values))
                                addAll(context.getStringArray(R.array.sub_formats_values).filter { it.isNotBlank() })
                                add("description")
                                add("txt")
                            }
                            finalPaths = finalPaths?.filter { path -> !nonMediaExtensions.any { path.endsWith(it) } }?.toMutableList()
                            FileUtil.deleteConfigFiles(request)

                            //put download in history
                            if (!downloadItem.incognito) {
                                if (request.hasOption("--download-archive") && finalPaths == listOf(context.getString(R.string.unfound_file))) {
                                    handler.postDelayed({
                                        Toast.makeText(context, resources.getString(R.string.download_already_exists), Toast.LENGTH_LONG).show()
                                    }, 100)
                                }else{
                                    val unixTime = System.currentTimeMillis() / 1000
                                    finalPaths?.apply {
                                        this.first().apply {
                                            val file = File(this)
                                            var duration = downloadItem.duration
                                            val d = file.getMediaDuration(context)
                                            if (d > 0) duration = d.toStringDuration(Locale.US)

                                            downloadItem.format.filesize = file.length()
                                            downloadItem.format.container = file.extension
                                            downloadItem.duration = duration
                                        }

                                        val historyItem = HistoryItem(0,
                                            downloadItem.url,
                                            downloadItem.title,
                                            downloadItem.author,
                                            downloadItem.duration,
                                            downloadItem.thumb,
                                            downloadItem.type,
                                            unixTime,
                                            this,
                                            downloadItem.website,
                                            downloadItem.format,
                                            downloadItem.id,
                                            commandString)
                                        historyDao.insert(historyItem)
                                    }

                                }
                            }

                            notificationUtil.cancelDownloadNotification(downloadItem.id.toInt())
                            notificationUtil.createDownloadFinished(
                                downloadItem.id, downloadItem.title, downloadItem.type,  if (finalPaths?.first().equals(context.getString(R.string.unfound_file))) null else finalPaths, resources
                            )

//                            if (wasQuickDownloaded && createResultItem){
//                                runCatching {
//                                    eventBus.post(WorkerProgress(100, "Creating Result Items", downloadItem.id))
//                                    runBlocking {
//                                        infoUtil.getFromYTDL(downloadItem.url).forEach { res ->
//                                            if (res != null) {
//                                                resultDao.insert(res)
//                                            }
//                                        }
//                                    }
//                                }
//                            }

                            dao.delete(downloadItem.id)

                            if (logDownloads){
                                logRepo.update(it.out, logItem.id)
                            }
                        }

                    }.onFailure {
                        FileUtil.deleteConfigFiles(request)
                        withContext(Dispatchers.Main){
                            notificationUtil.cancelDownloadNotification(downloadItem.id.toInt())
                        }
                        if (this@DownloadWorker.isStopped) return@onFailure
                        if (it is YoutubeDL.CanceledException) return@onFailure

                        if(it.message != null){
                            if (logDownloads){
                                logRepo.update(it.message!!, logItem.id)
                            }else{
                                logString.append("${it.message}\n")
                                logItem.content = logString.toString()
                                val logID = logRepo.insert(logItem)
                                downloadItem.logID = logID
                            }
                        }

                        tempFileDir.delete()
                        handler.postDelayed({
                            Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
                        }, 1000)

                        Log.e(TAG, context.getString(R.string.failed_download), it)
                        notificationUtil.cancelDownloadNotification(downloadItem.id.toInt())

                        downloadItem.status = DownloadRepository.Status.Error.toString()
                        runBlocking {
                            dao.update(downloadItem)
                        }

                        notificationUtil.createDownloadErrored(
                            downloadItem.id,
                            downloadItem.title.ifEmpty { downloadItem.url },
                            it.message,
                            downloadItem.logID,
                            resources
                        )

                        eventBus.post(WorkerProgress(100, it.toString(), downloadItem.id))
                    }
                }
            }

            if (eligibleDownloads.isNotEmpty()){
                eligibleDownloads.forEach { it.status = DownloadRepository.Status.Active.toString() }
                dao.updateMultiple(eligibleDownloads)
            }
        }


        return Result.success()
    }



    companion object {
        val runningYTDLInstances: MutableList<Long> = mutableListOf()
        const val TAG = "DownloadWorker"
    }

    class WorkerProgress(
        val progress: Int,
        val output: String,
        val downloadItemID: Long
    )

}