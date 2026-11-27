package app.tvflick.core.backup

enum class BackupInterval(val days: Int, val label: String) {
    Daily(1, "1 day"), Weekly(7, "1 week"), Monthly(30, "30 days")
}

fun autoBackupDue(enabled: Boolean, lastSuccess: Long?, interval: BackupInterval, now: Long): Boolean =
    enabled && (lastSuccess == null || now >= lastSuccess && now - lastSuccess >= interval.days * 86_400_000L)
