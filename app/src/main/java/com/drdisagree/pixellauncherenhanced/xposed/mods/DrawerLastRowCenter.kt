package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.drdisagree.pixellauncherenhanced.data.common.Constants.CENTER_DRAWER_LAST_ROW
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getExtraFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setExtraField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

class DrawerLastRowCenter(context: Context) : ModPack(context) {

    private var centerLastRow = false
    private var recyclerViewClass: Class<*>? = null
    private val recyclerViews = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    private val itemViewFields = HashMap<Class<*>, Field?>()
    private val shiftUpdater = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        view.updateRowShift()
    }

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            centerLastRow = getBoolean(CENTER_DRAWER_LAST_ROW, false)
        }

        when (key.firstOrNull()) {
            CENTER_DRAWER_LAST_ROW -> recyclerViews.toList().forEach { it.refreshRowShifts() }
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        recyclerViewClass = findClass(
            "com.android.launcher3.allapps.AllAppsRecyclerView",
            suppressError = true
        ) ?: return

        findClass("com.android.launcher3.allapps.BaseAllAppsAdapter", suppressError = true)
            .hookMethod("onBindViewHolder")
            .suppressError()
            .runAfter { param ->
                val view = param.args.getOrNull(0)?.itemView() ?: return@runAfter
                val position = param.args.getOrNull(1) as? Int ?: return@runAfter
                view.applyRowShift(rowShift(param.thisObject.getFieldSilently("mApps"), position))
            }

        // Item updates dispatched through DiffUtil don't rebind views that only moved
        findClass("androidx.recyclerview.widget.RecyclerView", suppressError = true)
            .hookMethod("onLayout")
            .suppressError()
            .runAfter { param ->
                val recyclerView = param.thisObject as? ViewGroup ?: return@runAfter
                if (recyclerViewClass?.isInstance(recyclerView) != true) return@runAfter

                recyclerViews.add(recyclerView)
                recyclerView.refreshRowShifts()
            }
    }

    private fun ViewGroup.refreshRowShifts() {
        val apps = getFieldSilently("mApps")
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val position = callMethodSilently("getChildAdapterPosition", child) as? Int ?: continue
            if (position >= 0) child.applyRowShift(rowShift(apps, position))
        }
    }

    /**
     * Returns how far the item at [position] should move, as a fraction of the grid width,
     * so that an incomplete last row of a block of app icons ends up centered.
     */
    private fun rowShift(apps: Any?, position: Int): Float {
        if (!centerLastRow || apps == null) return 0f
        if (!(apps.getFieldSilently("mSearchResults") as? Collection<*>).isNullOrEmpty()) return 0f

        val items = apps.getFieldSilently("mAdapterItems") as? List<*> ?: return 0f
        val columns = (apps.getFieldSilently("mNumAppsPerRowAllApps")
            ?: apps.getFieldSilently("mNumAppsPerRow")) as? Int ?: return 0f
        if (columns <= 1 || !items.getOrNull(position).isGridItem()) return 0f

        var start = position
        while (start > 0 && items[start - 1].isGridItem()) start--
        var end = position
        while (end < items.lastIndex && items[end + 1].isGridItem()) end++

        val remainder = (end - start + 1) % columns
        if (remainder == 0 || position <= end - remainder) return 0f

        return (columns - remainder) / (2f * columns)
    }

    private fun Any?.isGridItem(): Boolean = this?.getFieldSilently("itemInfo") != null

    private fun View.applyRowShift(fraction: Float) {
        if (fraction == 0f && getExtraFieldSilently(ROW_SHIFT) == null) return

        setExtraField(ROW_SHIFT, fraction)
        if (getExtraFieldSilently(ROW_SHIFT_LISTENER) == null) {
            setExtraField(ROW_SHIFT_LISTENER, true)
            addOnLayoutChangeListener(shiftUpdater)
        }
        updateRowShift()
    }

    private fun View.updateRowShift() {
        val fraction = getExtraFieldSilently(ROW_SHIFT) as? Float ?: return
        val parent = parent as? View ?: return
        val offset = fraction * (parent.width - parent.paddingLeft - parent.paddingRight)
        translationX = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) -offset else offset
    }

    // ViewHolder fields are obfuscated, itemView is the first view field of the base class
    private fun Any.itemView(): View? {
        val field = itemViewFields.getOrPut(javaClass) {
            generateSequence<Class<*>>(javaClass) { it.superclass }
                .toList()
                .asReversed()
                .flatMap { it.declaredFields.asList() }
                .firstOrNull { !Modifier.isStatic(it.modifiers) && View::class.java.isAssignableFrom(it.type) }
                ?.apply { isAccessible = true }
        }
        return field?.get(this) as? View
    }

    companion object {
        private const val ROW_SHIFT = "pleDrawerRowShift"
        private const val ROW_SHIFT_LISTENER = "pleDrawerRowShiftListener"
    }
}
