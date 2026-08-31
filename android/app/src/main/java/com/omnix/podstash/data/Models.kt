package com.omnix.podstash.data

data class Show(
    val id: String,
    val name: String,
    val author: String = "",
    val artwork: String = "",
    val feedUrl: String = "",
    val episodeCount: Int = 0,
    val country: String = "",
    val rank: Int = 0,
    val subscribed: Boolean = false,
    val lastSeenGuid: String = "",
)

data class Episode(
    val index: Int,
    val title: String,
    val audioUrl: String,
    val published: String = "",
    val duration: String = "",
    val guid: String = "",
    val size: Long = 0,
    val localPath: String = "",
    val downloaded: Boolean = false,
    val partial: Boolean = false,
)

data class UpdateInfo(
    val version: String,
    val versionCode: Int,
    val notes: String,
    val apkUrl: String,
    val htmlUrl: String,
)

enum class DlStatus { queued, running, paused, done, skipped, error }

data class QueueItem(
    val key: String,
    val show: Show,
    val episode: Episode,
    val status: DlStatus = DlStatus.queued,
    val error: String = "",
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
)

data class LastPlayed(
    val guid: String,
    val show: Show,
    val episode: Episode,
    val position: Long = 0,
)
