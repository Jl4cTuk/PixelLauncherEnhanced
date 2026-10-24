package com.drdisagree.pixellauncherenhanced.xposed.mods

import com.drdisagree.pixellauncherenhanced.xposed.utils.DebugRecorder
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_BLOCK_LIST
import com.drdisagree.pixellauncherenhanced.data.common.Constants.AUTO_HIDE_HOME_APPS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_APPS_FROM_APP_DRAWER
import com.drdisagree.pixellauncherenhanced.data.common.Constants.NO_DRAWER_MODE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SEARCH_HIDDEN_APPS
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hasMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setExtraField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.Arrays
import java.util.Collections
import java.util.WeakHashMap
import java.util.function.Predicate

class HideApps(context: Context) : ModPack(context) {

    private var searchHiddenApps: Boolean = false
    private var autoHideHomeApps: Boolean = false
    private var noDrawerMode: Boolean = false
    private var bgDataModelRef: WeakReference<Any>? = null

    @Volatile
    private var homeApps: Set<Pair<ComponentName, UserHandle?>> = emptySet()

    private val autoHideActive: Boolean
        get() = autoHideHomeApps && !noDrawerMode && !REVEAL_HIDDEN_APPS

    companion object {
        private const val ORIGINAL_ITEM_FILTER = "pleOriginalItemFilter"
        private const val ITEM_TYPE_APPLICATION = 0
        private const val CONTAINER_DESKTOP = -100
        private const val CONTAINER_HOTSEAT = -101
        private val NO_FILTER = Any()
        private var appBlockList: Set<String> = mutableSetOf()

        fun isHidden(packageName: String?): Boolean =
            !(packageName.isNullOrEmpty() || SHOULD_UNHIDE_ALL_APPS) && appBlockList.contains(packageName)

        fun isBlocked(packageName: String?): Boolean =
            !packageName.isNullOrEmpty() && appBlockList.contains(packageName)

        var HIDE_APPS_ENABLED = false
        var REVEAL_HIDDEN_APPS = false
        val SHOULD_UNHIDE_ALL_APPS: Boolean
            get() = !HIDE_APPS_ENABLED || REVEAL_HIDDEN_APPS
        private var activityAllAppsContainerViewInstance: Any? = null
        private var hotseatPredictionControllerInstance: Any? = null
        private var hybridHotseatOrganizerClassInstance: Any? = null
        private var predictionRowViewInstance: Any? = null
        private val hookedModelUpdateTaskClasses =
            Collections.synchronizedSet(mutableSetOf<String>())
        private val appsLists = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
        private val mainHandler = Handler(Looper.getMainLooper())
        private val refreshAppsLists = Runnable {
            appsLists.toList().forEach { it.callMethodSilently("onAppsUpdated") }
        }

        private fun scheduleAppsListsRefresh() {
            mainHandler.removeCallbacks(refreshAppsLists)
            mainHandler.postDelayed(refreshAppsLists, 100)
        }

        fun updateLauncherIcons(context: Context) {
            scheduleAppsListsRefresh()
            activityAllAppsContainerViewInstance.callMethod("onAppsUpdated")
            hotseatPredictionControllerInstance.callMethodSilently("fillGapsWithPrediction", true)
            hybridHotseatOrganizerClassInstance.callMethodSilently("fillGapsWithPrediction", true)
            predictionRowViewInstance.callMethod("applyPredictionApps")
            reloadLauncher(context)
        }
    }

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            appBlockList = getStringSet(APP_BLOCK_LIST, emptySet())!!
            searchHiddenApps = getBoolean(SEARCH_HIDDEN_APPS, false)
            HIDE_APPS_ENABLED = getBoolean(HIDE_APPS_FROM_APP_DRAWER, false)
            autoHideHomeApps = getBoolean(AUTO_HIDE_HOME_APPS, false)
            noDrawerMode = getBoolean(NO_DRAWER_MODE, false)
        }

        when (key.firstOrNull()) {
            HIDE_APPS_FROM_APP_DRAWER -> {
                REVEAL_HIDDEN_APPS = false
                updateLauncherIcons(mContext)
            }

            APP_BLOCK_LIST -> updateLauncherIcons(mContext)

            AUTO_HIDE_HOME_APPS -> {
                updateHomeApps()
                scheduleAppsListsRefresh()
            }
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val activityAllAppsContainerViewClass =
            findClass("com.android.launcher3.allapps.ActivityAllAppsContainerView")
        val hotseatPredictionControllerClass =
            findClass("com.android.launcher3.hybridhotseat.HotseatPredictionController")
        val hybridHotseatOrganizerClass = findClass(
            "com.android.launcher3.util.HybridHotseatOrganizer",
            suppressError = true
        )
        val predictionRowViewClass =
            findClass("com.android.launcher3.appprediction.PredictionRowView")
        val defaultAppSearchAlgorithmClass = findClass(
            "com.android.launcher3.allapps.DefaultAppSearchAlgorithm",
            "com.android.launcher3.allapps.search.DefaultAppSearchAlgorithm"
        )
        val quickstepLauncherClass =
            findClass("com.android.launcher3.uioverrides.QuickstepLauncher")
        val alphabeticalAppsListClass =
            findClass("com.android.launcher3.allapps.AlphabeticalAppsList")
        val allAppsStoreClass = findClass("com.android.launcher3.allapps.AllAppsStore")
        val appInfoClass = findClass("com.android.launcher3.model.data.AppInfo")
        val allAppsListClass = findClass("com.android.launcher3.model.AllAppsList")
        val launcherModelClass = findClass("com.android.launcher3.LauncherModel")
        val bgDataModelClass =
            findClass("com.android.launcher3.model.BgDataModel", suppressError = true)

        activityAllAppsContainerViewClass
            .hookConstructor()
            .runAfter { param -> activityAllAppsContainerViewInstance = param.thisObject }

        bgDataModelClass
            .hookConstructor()
            .runAfter { param -> bgDataModelRef = WeakReference(param.thisObject) }

        bgDataModelClass
            .hookMethod("addItem", "addItems", "removeItem", "removeItems", "updateItems", "clear")
            .suppressError()
            .runAfter { param ->
                if (bgDataModelRef?.get() !== param.thisObject) {
                    bgDataModelRef = WeakReference(param.thisObject)
                }
                if (updateHomeApps() && autoHideActive) scheduleAppsListsRefresh()
            }

        findClass("com.android.launcher3.model.LoaderTask", suppressError = true)
            .hookMethod("run")
            .suppressError()
            .runAfter { param ->
                param.thisObject.getFieldSilently("mBgDataModel")?.let {
                    bgDataModelRef = WeakReference(it)
                }
                if (updateHomeApps() && autoHideActive) scheduleAppsListsRefresh()
            }

        DebugRecorder.registerHealth("Hotseat predictions captured") {
            if (!HIDE_APPS_ENABLED) null else hotseatPredictionControllerInstance != null
        }

        hotseatPredictionControllerClass
            .hookConstructor()
            .capability("Hotseat predictions")
            .runAfter { param -> hotseatPredictionControllerInstance = param.thisObject }

        hybridHotseatOrganizerClass
            .hookConstructor()
            .capability("Hotseat organizer")
            .runAfter { param -> hybridHotseatOrganizerClassInstance = param.thisObject }

        predictionRowViewClass
            .hookConstructor()
            .runAfter { param -> predictionRowViewInstance = param.thisObject }

        quickstepLauncherClass
            .hookMethod("onCreate")
            .capability("Hotseat predictions,Hotseat organizer")
            .runAfter { param ->
                if (hotseatPredictionControllerInstance == null) {
                    hotseatPredictionControllerInstance =
                        param.thisObject.getField("mHotseatPredictionController")
                }
                if (hybridHotseatOrganizerClassInstance == null) {
                    hybridHotseatOrganizerClassInstance =
                        hotseatPredictionControllerInstance.getFieldSilently("mHotseatOrganizer")
                }
            }

        allAppsStoreClass
            .hookMethod("setApps")
            .suppressError()
            .runAfter { param ->
                val apps = param.args[0]

                if (apps != null) {
                    param.thisObject.setExtraField("mAppsBackup", apps)
                }
            }

        @Suppress("UNCHECKED_CAST")
        Arrays::class.java
            .hookMethod("binarySearch")
            .parameters(
                Array<Any?>::class.java,
                Any::class.java,
                Comparator::class.java,
            )
            .runBefore { param ->
                val mApps = param.args[0] as? Array<*> ?: return@runBefore

                if (!mApps
                        .javaClass
                        .componentType
                        ?.simpleName
                        .equals(appInfoClass!!.simpleName, ignoreCase = true)
                ) return@runBefore

                val appInfo = param.args[1]
                val comparator = param.args[2] as Comparator<Any?>
                val componentName = appInfo.getComponentName()

                val binarySearch = binarySearch(mApps, appInfo, comparator)

                if (binarySearch < 0 || (!searchHiddenApps && componentName.matchesBlocklist())) {
                    param.result = -1
                }
            }

        predictionRowViewClass
            .hookMethod("applyPredictionApps")
            .runBefore { param ->
                val mPredictedApps =
                    (param.thisObject.getField("mPredictedApps") as ArrayList<*>).toMutableList()

                val iterator = mPredictedApps.iterator()
                iterator.removeMatches()

                param.thisObject.setField("mPredictedApps", ArrayList(mPredictedApps))
            }

        if (hotseatPredictionControllerClass.hasMethod("fillGapsWithPrediction")) {
            hotseatPredictionControllerClass
                .hookMethod("fillGapsWithPrediction")
                .parameters(Boolean::class.java)
                .runBefore { param ->
                    val mPredictedItems =
                        (param.thisObject.getField("mPredictedItems") as List<*>).toMutableList()

                    val iterator = mPredictedItems.iterator()
                    iterator.removeMatches()
                }
        } else {
            hybridHotseatOrganizerClass
                .hookMethod("fillGapsWithPrediction")
                .parameters(Boolean::class.java)
                .runBefore { param ->
                    val mPredictedItems =
                        (param.thisObject.getField("predictedItems") as List<*>).toMutableList()

                    val iterator = mPredictedItems.iterator()
                    iterator.removeMatches()
                }
        }

        try {
            defaultAppSearchAlgorithmClass
                .hookMethod("getTitleMatchResult")
                .throwError()
                .runBefore { param ->
                    if (searchHiddenApps) return@runBefore

                    val index = if (param.args[0] is Context) 1 else 0
                    val apps = (param.args[index] as List<*>).toMutableList()

                    val iterator = apps.iterator()
                    iterator.removeMatches()

                    param.args[index] = ArrayList(apps)
                }
        } catch (_: Throwable) {
            // Method seems to be unused on newer versions of pixel launcher
            // But still hook it just in case

            fun removeAppResult(param: XC_MethodHook.MethodHookParam) {
                if (searchHiddenApps) return

                val appsIndex = param.args.indexOfFirst {
                    it::class.java.simpleName == allAppsListClass!!.simpleName
                }

                if (appsIndex < 0) return

                val apps = param.args[appsIndex]
                val data = apps.getField("data") as ArrayList<*>

                val iterator = data.iterator()
                iterator.removeMatches()

                apps.setField("data", ArrayList(data))
                param.args[appsIndex] = apps
            }

            val methodName = (defaultAppSearchAlgorithmClass!!.declaredMethods.toList()
                .union(defaultAppSearchAlgorithmClass.methods.toList()))
                .firstOrNull { method ->
                    Modifier.isStatic(method.modifiers) &&
                            method.parameterTypes.any { it == String::class.java } &&
                            method.parameterTypes.any { it.simpleName == allAppsListClass!!.simpleName } &&
                            method.parameterCount >= 2
                }?.name

            if (methodName != null) {
                defaultAppSearchAlgorithmClass
                    .hookMethod(methodName)
                    .runBefore { param ->
                        removeAppResult(param)
                    }
            } else {
                val baseModelUpdateTaskClass = findClass(
                    "com.android.launcher3.model.BaseModelUpdateTask",
                    suppressError = Build.VERSION.SDK_INT >= 36
                )

                launcherModelClass
                    .hookMethod("enqueueModelUpdateTask")
                    .runBefore { param ->
                        val modelUpdateTask = param.args[0]
                        val taskClass = modelUpdateTask::class.java

                        if (baseModelUpdateTaskClass != null &&
                            taskClass.simpleName != baseModelUpdateTaskClass.simpleName
                        ) return@runBefore

                        if (!hookedModelUpdateTaskClasses.add(taskClass.name)) return@runBefore

                        taskClass
                            .hookMethod("execute")
                            .runBefore { param2 ->
                                removeAppResult(param2)
                            }
                    }
            }
        }

        @Suppress("UNCHECKED_CAST")
        alphabeticalAppsListClass
            .hookMethod("onAppsUpdated")
            .runBefore { param ->
                val list = param.thisObject
                if (list.getFieldSilently("mAllAppsStore") == null) return@runBefore
                appsLists.add(list)

                val hideBlocked = !SHOULD_UNHIDE_ALL_APPS && appBlockList.isNotEmpty()
                val hideHome = autoHideActive && homeApps.isNotEmpty()
                if (!hideBlocked && !hideHome) return@runBefore

                val original = list.getFieldSilently("mItemFilter") as? Predicate<Any?>
                val hiddenFilter = Predicate<Any?> {
                    !it.getComponentName().matchesBlocklist() && !(hideHome && it.isOnHomeScreen())
                }

                param.setObjectExtra(ORIGINAL_ITEM_FILTER, original ?: NO_FILTER)
                list.setField("mItemFilter", original?.and(hiddenFilter) ?: hiddenFilter)
            }
            .runAfter { param ->
                val original = param.getObjectExtra(ORIGINAL_ITEM_FILTER)
                if (original != null) {
                    param.thisObject.setField("mItemFilter", original.takeIf { it !== NO_FILTER })
                }

                val mAdapterItems = param.thisObject.getField("mAdapterItems") as ArrayList<*>
                val hideHome = autoHideActive && homeApps.isNotEmpty() &&
                        (param.thisObject.getFieldSilently("mSearchResults") as? Collection<*>).isNullOrEmpty()
                val visibleItems = mAdapterItems.filterNot {
                    val itemInfo = it.getFieldSilently("itemInfo")
                    itemInfo.getComponentName().matchesBlocklist() || (hideHome && itemInfo.isOnHomeScreen())
                }

                if (visibleItems.size != mAdapterItems.size) {
                    param.thisObject.setField("mAdapterItems", ArrayList(visibleItems))
                }
            }
    }

    private fun updateHomeApps(): Boolean {
        val dataModel = bgDataModelRef?.get() ?: return false
        val items = runCatching {
            synchronized(dataModel) {
                (dataModel.getFieldSilently("itemsIdMap") as? Iterable<*>)?.toList()
            }
        }.getOrNull() ?: return false

        val updated = items
            .filter { it.isHomeScreenApp() }
            .mapNotNullTo(HashSet()) { item ->
                runCatching { item.getComponentName() to item.userHandle() }.getOrNull()
            }

        if (updated == homeApps) return false
        homeApps = updated
        return true
    }

    private fun Any?.isHomeScreenApp(): Boolean {
        if (this == null || getFieldSilently("itemType") != ITEM_TYPE_APPLICATION) return false
        val container = getFieldSilently("container") as? Int ?: return false
        return container >= 0 || container == CONTAINER_DESKTOP || container == CONTAINER_HOTSEAT
    }

    private fun Any?.isOnHomeScreen(): Boolean {
        if (this == null) return false
        return homeApps.contains(getComponentName() to userHandle())
    }

    private fun Any?.userHandle(): UserHandle? = getFieldSilently("user") as? UserHandle

    private fun MutableIterator<Any?>.removeMatches() {
        while (hasNext()) {
            val itemInfo = next()
            val componentName = itemInfo.getComponentName()

            if (componentName.matchesBlocklist()) {
                remove()
            }
        }
    }

    private fun Any?.getComponentName(): ComponentName {
        if (this == null) return ComponentName("", "")

        return getFieldSilently("componentName") as? ComponentName
            ?: getFieldSilently("mComponentName") as? ComponentName
            ?: callMethod("getTargetComponent") as ComponentName
    }

    private fun ComponentName?.matchesBlocklist(): Boolean {
        return this?.packageName.matchesBlocklist()
    }

    private fun String?.matchesBlocklist(): Boolean {
        return !(isNullOrEmpty() || SHOULD_UNHIDE_ALL_APPS) && appBlockList.contains(this)
    }

    fun <T> binarySearch(
        array: Array<out T>,
        key: T,
        comparator: Comparator<in T>
    ): Int {
        var low = 0
        var high = array.size - 1

        while (low <= high) {
            val mid = (low + high) ushr 1
            val midVal = array[mid]
            val cmp = comparator.compare(midVal, key)

            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return mid
            }
        }

        return -(low + 1)
    }
}