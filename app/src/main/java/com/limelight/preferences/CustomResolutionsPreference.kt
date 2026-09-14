package com.limelight.preferences

import android.content.Context
import android.util.AttributeSet
import androidx.preference.DialogPreference

/**
 * 自定义分辨率偏好设置类
 *
 * 仅作为设置页入口;点击后由 StreamSettings 弹出 Compose 版
 * [CustomResolutionsDialog],分辨率数据的读写由 [CustomResolutionsStore] 完成。
 */
class CustomResolutionsPreference(
        context: Context,
        attrs: AttributeSet
) : DialogPreference(context, attrs) {

    override fun onSetInitialValue(defaultValue: Any?) {
        // No persisted value needed; resolutions are stored in a separate SharedPreferences file
    }
}
