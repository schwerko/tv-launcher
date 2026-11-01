package com.azura.tvlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent

object AppRepository {

    /** Every launchable app on the box, TV (leanback) entries preferred, sorted by name. */
    fun load(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val self = context.packageName
        val result = LinkedHashMap<String, AppInfo>()

        val probes = listOf(
            Intent.CATEGORY_LEANBACK_LAUNCHER,
            Intent.CATEGORY_LAUNCHER,
        )
        for (category in probes) {
            val probe = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(probe, 0)) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName
                if (pkg == self || result.containsKey(pkg)) continue

                val launch = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(category)
                    component = ComponentName(pkg, ai.name)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                }
                val banner = if (category == Intent.CATEGORY_LEANBACK_LAUNCHER) {
                    ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)
                } else null

                result[pkg] = AppInfo(
                    packageName = pkg,
                    label = ri.loadLabel(pm)?.toString() ?: pkg,
                    icon = ri.loadIcon(pm),
                    banner = banner,
                    launchIntent = launch,
                )
            }
        }
        return result.values.sortedBy { it.label.lowercase() }
    }
}
