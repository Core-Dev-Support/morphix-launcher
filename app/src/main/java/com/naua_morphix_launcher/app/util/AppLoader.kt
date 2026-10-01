package com.naua_morphix_launcher.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.naua_morphix_launcher.app.model.AppItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

import android.util.LruCache
import android.graphics.drawable.Drawable
import android.app.ActivityOptions
import android.graphics.Rect
import android.view.View

object AppLoader {
    // Кэш иконок для ускорения загрузки и скролла
    private val iconCache = LruCache<String, Drawable>(150)

    /**
     * Загрузка ВСЕХ приложений: Основное пространство + Второе пространство (Dual Apps / Work Profile)
     */
    suspend fun loadApps(context: Context): List<AppItem> = withContext(Dispatchers.IO) {
        val appList = mutableListOf<AppItem>()
        val packageManager = context.packageManager
        val myUser = Process.myUserHandle()
        val seenSignatures = mutableSetOf<String>()

        // 1. Сканирование через LauncherApps для всех пользовательских профилей (Второе пространство / Клоны)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            if (launcherApps != null) {
                val profiles = launcherApps.profiles
                for (profile in profiles) {
                    val isSecondSpace = (profile != myUser)
                    try {
                        val activities = launcherApps.getActivityList(null, profile)
                        for (activity in activities) {
                            val pkg = activity.applicationInfo.packageName
                            if (pkg == context.packageName) continue

                            val sig = "$pkg#$profile"
                            if (seenSignatures.contains(sig)) continue
                            seenSignatures.add(sig)

                            val label = activity.label?.toString() ?: pkg
                            
                            var icon = iconCache.get(sig)
                            if (icon == null) {
                                icon = activity.getBadgedIcon(context.resources.configuration.densityDpi)
                                if (icon != null) {
                                    iconCache.put(sig, icon)
                                }
                            }

                            appList.add(
                                AppItem(
                                    label = label,
                                    packageName = pkg,
                                    activityName = activity.name,
                                    icon = icon,
                                    userHandle = profile,
                                    isSecondSpace = isSecondSpace
                                )
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        // 2. Дополнительное сканирование через PackageManager только если LauncherApps не вернул приложений (fallback)
        if (appList.isEmpty()) {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PackageManager.MATCH_ALL
            } else {
                0
            }

            try {
                val resolveInfos = packageManager.queryIntentActivities(mainIntent, flags)
            for (info in resolveInfos) {
                val pkgName = info.activityInfo.packageName
                if (pkgName == context.packageName) continue

                val sig = "$pkgName#$myUser"
                if (seenSignatures.contains(sig)) continue
                seenSignatures.add(sig)

                val label = try {
                    info.loadLabel(packageManager)?.toString() ?: pkgName
                } catch (e: Exception) {
                    pkgName
                }

                var icon = iconCache.get(sig)
                if (icon == null) {
                    icon = try {
                        info.loadIcon(packageManager)
                    } catch (e: Exception) {
                        null
                    }
                    if (icon != null) {
                        iconCache.put(sig, icon)
                    }
                }

                appList.add(
                    AppItem(
                        label = label,
                        packageName = pkgName,
                        activityName = info.activityInfo.name,
                        icon = icon,
                        userHandle = myUser,
                        isSecondSpace = false
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

        // Сортировка по алфавиту
        val collator = Collator.getInstance(Locale.getDefault())
        appList.sortWith { a, b -> collator.compare(a.label, b.label) }

        appList
    }

    /**
     * Надежный запуск приложения в правильном пространстве (UserHandle) с аппаратной анимацией раскрытия
     */
    fun launchApp(context: Context, item: AppItem, sourceView: View? = null, smoothAnimations: Boolean = true): Boolean {
        return try {
            val animOptions = if (smoothAnimations && sourceView != null && sourceView.width > 0 && sourceView.height > 0) {
                ActivityOptions.makeClipRevealAnimation(sourceView, 0, 0, sourceView.width, sourceView.height).toBundle()
            } else null

            // Если приложение запущено во Втором пространстве или имеет свой UserHandle
            if (item.userHandle != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                if (launcherApps != null) {
                    val bounds = if (smoothAnimations && sourceView != null && sourceView.width > 0 && sourceView.height > 0) {
                        val loc = IntArray(2)
                        sourceView.getLocationOnScreen(loc)
                        Rect(loc[0], loc[1], loc[0] + sourceView.width, loc[1] + sourceView.height)
                    } else null
                    launcherApps.startMainActivity(
                        ComponentName(item.packageName, item.activityName),
                        item.userHandle,
                        bounds,
                        animOptions
                    )
                    return true
                }
            }

            val launchIntent = context.packageManager.getLaunchIntentForPackage(item.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(launchIntent, animOptions)
                return true
            }

            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(item.packageName, item.activityName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            context.startActivity(intent, animOptions)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
