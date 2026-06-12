package ru.foric27.cluster.update

internal object AppUpdateReleaseParsing {

    private val commitLineRegex = Regex("""\*\*Commit:\*\*\s*\[([0-9a-fA-F]{7,40})]""")
    private val apkShaRegex = Regex("""-([0-9a-fA-F]{7,40})\.apk$""")

    fun parseBuildShaFromApkName(fileName: String): String? {
        return apkShaRegex.find(fileName)?.groupValues?.getOrNull(1)?.let(AppUpdateVersionPolicy::normalizeBuildSha)
    }

    fun parseBuildShaFromReleaseNotes(body: String): String? {
        return commitLineRegex.find(body)?.groupValues?.getOrNull(1)?.let(AppUpdateVersionPolicy::normalizeBuildSha)
    }
}
