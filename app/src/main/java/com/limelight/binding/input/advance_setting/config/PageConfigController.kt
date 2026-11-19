package com.limelight.binding.input.advance_setting.config

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import com.limelight.utils.AppSwitchStyle

import com.limelight.Game // 确保导入 Game 类
import com.limelight.R
import com.limelight.binding.input.advance_setting.ControllerManager
import com.limelight.binding.input.advance_setting.CrownConfigChoiceFactory
import com.limelight.binding.input.advance_setting.CrownConfigChoice
import com.limelight.binding.input.advance_setting.CrownThumbnailView
import com.limelight.binding.input.advance_setting.share.CrownProfileSchema
import com.limelight.binding.input.advance_setting.share.CrownProfileShareManager
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout

import java.util.ArrayList

class PageConfigController(private val controllerManager: ControllerManager, private val context: Context) {

    private var pageConfig: SuperPageLayout
    var currentConfigId: Long = 0L
    private var selectedProfileName: TextView
    private var selectedProfileSummary: TextView
    private var selectedProfileThumbnail: CrownThumbnailView
    private var enhancedTouchLayout: LinearLayout

    private var configIds: MutableList<Long> = ArrayList()
    private var configNames: MutableList<String> = ArrayList()

    init {
        pageConfig = LayoutInflater.from(context).inflate(R.layout.page_config, null) as SuperPageLayout
        enhancedTouchLayout = pageConfig.findViewById(R.id.enhanced_touch_layout)
        selectedProfileName = pageConfig.findViewById(R.id.config_selected_profile_name)
        selectedProfileSummary = pageConfig.findViewById(R.id.config_selected_profile_summary)
        selectedProfileThumbnail = pageConfig.findViewById(R.id.config_selected_profile_thumbnail)
        (pageConfig.findViewById<View>(R.id.crown_page_title_text) as TextView)
            .setText(R.string.layout_page_super_menu_text_899a4)

        pageConfig.findViewById<View>(R.id.config_profile_picker).setOnClickListener { openConfigPicker() }
        bindConfigSegments()

        //新增布局按钮
        pageConfig.findViewById<View>(R.id.add_config_button).setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                val pageWindow = LayoutInflater.from(context).inflate(R.layout.page_window, null) as SuperPageLayout
                val title = pageWindow.findViewById<TextView>(R.id.window_title)
                title.text = getCrownDialogTitle(R.string.crown_profile_name_title)
                val editText = pageWindow.findViewById<EditText>(R.id.window_edittext)
                //窗口确认按钮
                pageWindow.findViewById<View>(R.id.window_confirm).setOnClickListener(object : View.OnClickListener {
                    override fun onClick(v: View) {
                        val configName = editText.text.toString()
                        if (!configName.matches("^.{1,10}$".toRegex())) {
                            Toast.makeText(context, context.getString(R.string.crown_profile_name_error), Toast.LENGTH_SHORT).show()
                            return
                        }
                        val contentValues = ContentValues()
                        contentValues.put(COLUMN_LONG_CONFIG_ID, System.currentTimeMillis())
                        contentValues.put(COLUMN_STRING_CONFIG_NAME, configName)
                        contentValues.put(COLUMN_BOOLEAN_TOUCH_ENABLE, true.toString())
                        contentValues.put(COLUMN_BOOLEAN_TOUCH_MODE, true.toString())
                        contentValues.put(COLUMN_INT_TOUCH_SENSE, 100)
                        //保存到数据库中
                        controllerManager.superConfigDatabaseHelper!!.insertConfig(contentValues)
                        returnPrePage(pageWindow.lastPage!!)
                        loadAllConfigToSpinner()
                        loadCurrentConfig()
                    }
                })
                //窗口取消按钮
                pageWindow.findViewById<View>(R.id.window_cancel).setOnClickListener(object : View.OnClickListener {
                    override fun onClick(v: View) {
                        returnPrePage(pageWindow.lastPage!!)
                    }
                })
                controllerManager.superPagesController!!.openNewPage(pageWindow)
            }
        })
        //重命名布局按钮
        pageConfig.findViewById<View>(R.id.rename_config_button).setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                openRenameDialog()
            }
        })
        //删除布局按钮
        pageConfig.findViewById<View>(R.id.delete_config_button).setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                val pageWindow = LayoutInflater.from(context).inflate(R.layout.page_window, null) as SuperPageLayout
                val title = pageWindow.findViewById<TextView>(R.id.window_title)
                val titleString = context.getString(R.string.crown_delete_prefix) + configNames[configIds.indexOf(currentConfigId)]
                title.text = getCrownDialogTitle(titleString)
                pageWindow.findViewById<View>(R.id.window_edittext).visibility = View.GONE
                //窗口确认按钮
                pageWindow.findViewById<View>(R.id.window_confirm).setOnClickListener(object : View.OnClickListener {
                    override fun onClick(v: View) {
                        if (currentConfigId == 0L) {
                            returnPrePage(pageWindow.lastPage!!)
                            return
                        }
                        controllerManager.superConfigDatabaseHelper!!.deleteConfig(currentConfigId)
                        val editor = PreferenceManager.getDefaultSharedPreferences(context).edit()
                        editor.putLong(CURRENT_CONFIG_KEY, 0L)
                        editor.apply()
                        loadAllConfigToSpinner()
                        loadCurrentConfig()
                        returnPrePage(pageWindow.lastPage!!)
                    }
                })
                //窗口取消按钮
                pageWindow.findViewById<View>(R.id.window_cancel).setOnClickListener(object : View.OnClickListener {
                    override fun onClick(v: View) {
                        returnPrePage(pageWindow.lastPage!!)
                    }
                })
                controllerManager.superPagesController!!.openNewPage(pageWindow)
            }
        })

        // 退出王冠配置按钮
        pageConfig.findViewById<View>(R.id.exit_crown_config_button).setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                // 一次性模式（NO_MENU_LOCKED）退出后回到游戏菜单，否则保持王冠模式
                if ((context as Game).getCurrentBackKeyMenuMode() === Game.BackKeyMenuMode.NO_MENU_LOCKED) {
                    (context as Game).setcurrentBackKeyMenu(Game.BackKeyMenuMode.GAME_MENU)
                } else {
                    (context as Game).setcurrentBackKeyMenu(Game.BackKeyMenuMode.CROWN_MODE)
                }

                // 关闭当前的高级设置页面，相当于按返回键
                controllerManager.superPagesController!!.returnOperation()
            }
        })
    }

    private fun getCrownDialogTitle(titleResId: Int): String {
        return "♛ " + context.getString(titleResId)
    }

    private fun getCrownDialogTitle(title: String): String {
        return "♛ " + title
    }

    fun initConfig() {
        loadAllConfigToSpinner()
        loadCurrentConfig()
    }

    private fun loadAllConfigToSpinner() {
        configIds = controllerManager.superConfigDatabaseHelper!!.queryAllConfigIds()
        //判断是否有default布局
        if (!configIds.contains(0L)) {
            val contentValues = ContentValues()
            contentValues.put(COLUMN_LONG_CONFIG_ID, 0L)
            contentValues.put(COLUMN_STRING_CONFIG_NAME, "default")
            //保存到数据库中
            controllerManager.superConfigDatabaseHelper!!.insertConfig(contentValues)
            configIds = controllerManager.superConfigDatabaseHelper!!.queryAllConfigIds()
        }
        configNames.clear()
        for (configId in configIds) {
            val name = controllerManager.superConfigDatabaseHelper!!.queryConfigAttribute(configId, COLUMN_STRING_CONFIG_NAME, "default") as String
            configNames.add(name)
        }
    }

    private fun bindConfigSegments() {
        pageConfig.findViewById<View>(R.id.crown_config_segment_layout)
            .setOnClickListener { showConfigSection(CONFIG_SECTION_LAYOUT) }
        pageConfig.findViewById<View>(R.id.crown_config_segment_touch)
            .setOnClickListener { showConfigSection(CONFIG_SECTION_TOUCH) }
        pageConfig.findViewById<View>(R.id.crown_config_segment_feedback)
            .setOnClickListener { showConfigSection(CONFIG_SECTION_FEEDBACK) }
        showConfigSection(CONFIG_SECTION_TOUCH)
    }

    private fun showConfigSection(section: Int) {
        val layoutTab = pageConfig.findViewById<TextView>(R.id.crown_config_segment_layout)
        val touchTab = pageConfig.findViewById<TextView>(R.id.crown_config_segment_touch)
        val feedbackTab = pageConfig.findViewById<TextView>(R.id.crown_config_segment_feedback)
        val primaryText = androidx.core.content.ContextCompat.getColor(context, R.color.crown_text_primary)
        val secondaryText = androidx.core.content.ContextCompat.getColor(context, R.color.crown_text_secondary)
        layoutTab.setBackgroundResource(if (section == CONFIG_SECTION_LAYOUT)
            R.drawable.crown_segment_selected_bg else R.drawable.crown_segment_unselected_bg)
        layoutTab.setTextColor(if (section == CONFIG_SECTION_LAYOUT) primaryText else secondaryText)
        touchTab.setBackgroundResource(if (section == CONFIG_SECTION_TOUCH)
            R.drawable.crown_segment_selected_bg else R.drawable.crown_segment_unselected_bg)
        touchTab.setTextColor(if (section == CONFIG_SECTION_TOUCH) primaryText else secondaryText)
        feedbackTab.setBackgroundResource(if (section == CONFIG_SECTION_FEEDBACK)
            R.drawable.crown_segment_selected_bg else R.drawable.crown_segment_unselected_bg)
        feedbackTab.setTextColor(if (section == CONFIG_SECTION_FEEDBACK) primaryText else secondaryText)

        val showTouch = section == CONFIG_SECTION_TOUCH
        val showFeedback = section == CONFIG_SECTION_FEEDBACK
        pageConfig.findViewById<View>(R.id.crown_config_touch_title)
            .setVisibility(if (showTouch) View.VISIBLE else View.GONE)
        pageConfig.findViewById<View>(R.id.crown_config_touch_section)
            .setVisibility(if (showTouch) View.VISIBLE else View.GONE)
        pageConfig.findViewById<View>(R.id.crown_config_layout_section)
            .setVisibility(if (section == CONFIG_SECTION_LAYOUT) View.VISIBLE else View.GONE)
        pageConfig.findViewById<View>(R.id.crown_config_feedback_title)
            .setVisibility(if (showFeedback) View.VISIBLE else View.GONE)
        pageConfig.findViewById<View>(R.id.crown_config_feedback_section)
            .setVisibility(if (showFeedback) View.VISIBLE else View.GONE)
    }

    private fun openConfigPicker() {
        if (configIds.isEmpty()) return
        if (context !is Game) return

        // Keep the currently active profile at the top, show its real layout
        // thumbnail, and let the shared Crown picker handle touch and controller
        // focus consistently with the in-stream direct-switch action.
        val orderedIds = ArrayList<Long>()
        if (configIds.contains(currentConfigId)) orderedIds.add(currentConfigId)
        for (id in configIds) {
            if (!orderedIds.contains(id)) orderedIds.add(id)
        }
        val choices = CrownConfigChoiceFactory.create(
            controllerManager.superConfigDatabaseHelper!!, orderedIds, currentConfigId)
        (context as Game).showCrownConfigPicker(choices, { choice ->
            if (!choice.isSelectable || choice.configId == currentConfigId) {
                return@showCrownConfigPicker
            }
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putLong(CURRENT_CONFIG_KEY, choice.configId)
                .apply()
            initConfig()
        }, { })
    }

    fun switchDirectlyToConfig(targetId: Long) {
        // A deleted binding disables only this action. Never select by name or fall back.
        if (currentConfigId == targetId
            || !controllerManager.superConfigDatabaseHelper!!.queryAllConfigIds().contains(targetId)) {
            return
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putLong(CURRENT_CONFIG_KEY, targetId).apply()
        initConfig()
    }

    /** Returns the effective touch state of the currently selected Crown profile. */
    fun isTouchEnabled(): Boolean {
        return java.lang.Boolean.parseBoolean(controllerManager.superConfigDatabaseHelper!!
            .queryConfigAttribute(currentConfigId, COLUMN_BOOLEAN_TOUCH_ENABLE, true.toString()) as String?)
    }

    /** Toggles the current profile and applies the result immediately. */
    fun toggleTouchEnabled(): Boolean {
        val enabled = !isTouchEnabled()
        val contentValues = ContentValues()
        contentValues.put(COLUMN_BOOLEAN_TOUCH_ENABLE, enabled.toString())
        controllerManager.superConfigDatabaseHelper!!.updateConfig(currentConfigId, contentValues)
        // Refresh both the settings switch and the runtime state from the saved profile value.
        loadMouseEnable()
        return enabled
    }

    fun isVibrationEnabled(): Boolean {
        return isBooleanConfigEnabled(COLUMN_BOOLEAN_GAME_VIBRATOR)
            || isBooleanConfigEnabled(COLUMN_BOOLEAN_BUTTON_VIBRATOR)
    }

    fun setVibrationEnabled(enabled: Boolean) {
        val configId = PreferenceManager.getDefaultSharedPreferences(context)
            .getLong(CURRENT_CONFIG_KEY, currentConfigId)
        val values = ContentValues()
        values.put(COLUMN_BOOLEAN_GAME_VIBRATOR, enabled.toString())
        values.put(COLUMN_BOOLEAN_BUTTON_VIBRATOR, enabled.toString())
        controllerManager.superConfigDatabaseHelper!!.updateConfig(configId, values)
        controllerManager.elementController!!.setGameVibrator(enabled)
        controllerManager.elementController!!.setButtonVibrator(enabled)
    }

    private fun isBooleanConfigEnabled(column: String): Boolean {
        val configId = PreferenceManager.getDefaultSharedPreferences(context)
            .getLong(CURRENT_CONFIG_KEY, currentConfigId)
        return java.lang.Boolean.parseBoolean(controllerManager.superConfigDatabaseHelper!!
            .queryConfigAttribute(configId, column, false.toString()) as String?)
    }

    val directSwitchTargetIds: List<Long>
        get() {
            val targets = ArrayList<Long>()
            for (id in controllerManager.superConfigDatabaseHelper!!.queryAllConfigIds()) {
                if (id >= 0L && id != currentConfigId) targets.add(id)
            }
            return targets
        }

    private fun loadCurrentConfig() {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        currentConfigId = sharedPreferences.getLong(CURRENT_CONFIG_KEY, 0L)
        loadMouseEnable()
        loadMouseMode()
        val applyInputSettings = (context as Game).shouldApplyCrownInputProfile(currentConfigId)
        loadMouseSense(applyInputSettings)
        loadMouseWheelSpeed(applyInputSettings)
        loadGameVibrator()
        loadButtonVibrator()
        loadEnhancedTouch()
        controllerManager.elementController!!.loadAllElement(currentConfigId)
        val headerSubtitle = pageConfig.findViewById<TextView>(R.id.crown_page_subtitle)
        headerSubtitle.text = getCurrentConfigName()
        headerSubtitle.visibility = View.VISIBLE
        selectedProfileName.text = getCurrentConfigName()
        selectedProfileSummary.text = context.getString(
            R.string.crown_menu_profile_summary,
            getCurrentConfigName(),
            getCurrentElementCount())
        if (selectedProfileThumbnail != null) {
            var preview: CrownProfileShareManager.ProfilePreview? = null
            try {
                preview = CrownProfileShareManager.parsePreview(
                    controllerManager.superConfigDatabaseHelper!!.exportConfig(currentConfigId), null)
            } catch (ignored: RuntimeException) {
                // Keep the empty preview state for legacy profiles without layout data.
            }
            selectedProfileThumbnail.bind(preview)
        }
        if (currentConfigId == 0L) {
            pageConfig.findViewById<View>(R.id.rename_config_button).setVisibility(View.GONE)
            pageConfig.findViewById<View>(R.id.delete_config_button).setVisibility(View.GONE)
        } else {
            pageConfig.findViewById<View>(R.id.rename_config_button).setVisibility(View.VISIBLE)
            pageConfig.findViewById<View>(R.id.delete_config_button).setVisibility(View.VISIBLE)
        }
    }

    private fun loadMouseEnable() {
        //mouse enable
        val mouseEnableSwitch = pageConfig.findViewById<SwitchCompat>(R.id.mouse_enable_switch)
        AppSwitchStyle.apply(mouseEnableSwitch)
        val mouseEnable = isTouchEnabled()
        //设置switch
        mouseEnableSwitch.setOnCheckedChangeListener(null)
        mouseEnableSwitch.isChecked = mouseEnable
        //做实际的设置
        controllerManager.touchController!!.enableTouch(mouseEnable)
        //设置listener
        mouseEnableSwitch.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
                val contentValues = ContentValues()
                contentValues.put(COLUMN_BOOLEAN_TOUCH_ENABLE, isChecked.toString())
                //保存到数据库中
                controllerManager.superConfigDatabaseHelper!!.updateConfig(currentConfigId, contentValues)
                //做实际的设置
                controllerManager.touchController!!.enableTouch(isChecked)
            }
        })
    }

    private fun loadMouseMode() {
        //mouse mode
        val mouseModeSwitch = pageConfig.findViewById<SwitchCompat>(R.id.trackpad_enable_switch)
        AppSwitchStyle.apply(mouseModeSwitch)
        val mouseMode = java.lang.Boolean.parseBoolean(controllerManager.superConfigDatabaseHelper!!
            .queryConfigAttribute(currentConfigId, COLUMN_BOOLEAN_TOUCH_MODE, true.toString()) as String?)
        if (mouseMode) {
            enhancedTouchLayout.setVisibility(View.GONE) // 如果是触控板模式，隐藏
        } else {
            enhancedTouchLayout.setVisibility(View.VISIBLE) // 否则，显示
        }
        mouseModeSwitch.setOnCheckedChangeListener(null)
        mouseModeSwitch.isChecked = mouseMode
        controllerManager.touchController!!.setTouchMode(mouseMode)
        mouseModeSwitch.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
                if (isChecked) {
                    // 如果开启了触控板模式，隐藏“多点触控模式”布局
                    enhancedTouchLayout.setVisibility(View.GONE)
                } else {
                    // 如果关闭了触控板模式，显示“多点触控模式”布局
                    enhancedTouchLayout.setVisibility(View.VISIBLE)
                }
                val contentValues = ContentValues()
                contentValues.put(COLUMN_BOOLEAN_TOUCH_MODE, isChecked.toString())
                //保存到数据库中
                controllerManager.superConfigDatabaseHelper!!.updateConfig(currentConfigId, contentValues)
                //做实际的设置
                controllerManager.touchController!!.setTouchMode(isChecked)
            }
        })
    }

    private fun loadMouseSense(applyInputSettings: Boolean) {
        bindProfileParameter(R.id.mouse_sense_number_seekbar, R.id.apply_touchpad_sensitivity_switch,
            COLUMN_INT_TOUCH_SENSE, COLUMN_APPLY_TOUCHPAD_SENSITIVITY, 100, 1000,
            applyInputSettings) { value -> controllerManager.touchController!!.adjustTouchSense(value) }
    }

    private fun loadMouseWheelSpeed(applyInputSettings: Boolean) {
        bindProfileParameter(R.id.mouse_wheel_speed_number_seekbar, R.id.apply_mouse_wheel_speed_switch,
            COLUMN_INT_MOUSE_WHEEL_SPEED, COLUMN_APPLY_MOUSE_WHEEL_SPEED, 20, 100,
            applyInputSettings) { value -> (context as Game).updateCrownWheelSpeed(value) }
    }

    private fun interface ParameterApplier {
        fun apply(value: Int)
    }

    private fun bindProfileParameter(sliderId: Int, switchId: Int, valueColumn: String, flagColumn: String,
                                     defaultValue: Int, maximum: Int, applyInputSettings: Boolean,
                                     applier: ParameterApplier) {
        val slider = pageConfig.findViewById<NumberSeekbar>(sliderId)
        val value = Math.max(1, Math.min(maximum, (controllerManager.superConfigDatabaseHelper!!
            .queryConfigAttribute(currentConfigId, valueColumn, defaultValue.toLong()) as Long).toInt()))
        slider.setValueWithNoCallBack(value)
        bindApplySwitch(switchId, flagColumn, slider)
        if (applyInputSettings && shouldApply(flagColumn)) applier.apply(value)
        slider.setOnNumberSeekbarChangeListener(object : NumberSeekbar.OnNumberSeekbarChangeListener {
            private var trackingTouch = false

            fun saveAndApply(progress: Int) {
                val bounded = Math.max(1, Math.min(maximum, progress))
                val values = ContentValues()
                values.put(valueColumn, bounded)
                controllerManager.superConfigDatabaseHelper!!.updateConfig(currentConfigId, values)
                if (shouldApply(flagColumn)) applier.apply(bounded)
            }

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && !trackingTouch) saveAndApply(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                trackingTouch = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                trackingTouch = false
                saveAndApply(slider.value)
            }
        })
    }

    private fun shouldApply(column: String): Boolean {
        val value = controllerManager.superConfigDatabaseHelper!!
            .queryConfigAttribute(currentConfigId, column, 0L)
        return java.lang.Long.valueOf(1L) == value
    }

    private fun bindApplySwitch(viewId: Int, column: String, seekbar: NumberSeekbar) {
        val toggle = pageConfig.findViewById<SwitchCompat>(viewId)
        AppSwitchStyle.apply(toggle)
        toggle.setOnCheckedChangeListener(null)
        toggle.isChecked = shouldApply(column)
        seekbar.setControlsEnabled(toggle.isChecked)
        toggle.setOnCheckedChangeListener { button, checked ->
            val values = ContentValues()
            values.put(column, if (checked) 1 else 0)
            controllerManager.superConfigDatabaseHelper!!.updateConfig(currentConfigId, values)
            seekbar.setControlsEnabled(checked)
        }
    }

    private fun loadGameVibrator() {
        //mouse mode
        val gameVibratorSwitch = pageConfig.findViewById<SwitchCompat>(R.id.game_vibrator_enable_switch)
        AppSwitchStyle.apply(gameVibratorSwitch)
        val gameVibrator = java.lang.Boolean.parseBoolean(controllerManager.superConfigDatabaseHelper!!
            .queryConfigAttribute(currentConfigId, COLUMN_BOOLEAN_GAME_VIBRATOR, false.toString()) as String?)
        gameVibratorSwitch.setOnCheckedChangeListener(null)
        gameVibratorSwitch.isChecked = gameVibrator
        controllerManager.elementController!!.setGameVibrator(gameVibrator)
        gameVibratorSwitch.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
                val contentValues = ContentValues()
                contentValues.put(COLUMN_BOOLEAN_GAME_VIBRATOR, isChecked.toString())
                //保存到数据库中
                controllerManager.superConfigDatabaseHelper!!.updateConfig(currentConfigId, contentValues)
                //做实际的设置
                controllerManager.elementController!!.setGameVibrator(isChecked)
            }
        })
    }

    private fun loadButtonVibrator() {
        //mouse mode
        val buttonVibratorSwitch = pageConfig.findViewById<SwitchCompat>(R.id.button_vibrator_enable_switch)
        AppSwitchStyle.apply(buttonVibratorSwitch)
        val buttonVibrator = java.lang.Boolean.parseBoolean(controllerManager.superConfigDatabaseHelper!!
            .queryConfigAttribute(currentConfigId, COLUMN_BOOLEAN_BUTTON_VIBRATOR, false.toString()) as String?)
        buttonVibratorSwitch.setOnCheckedChangeListener(null)
        buttonVibratorSwitch.isChecked = buttonVibrator
        controllerManager.elementController!!.setButtonVibrator(buttonVibrator)
        buttonVibratorSwitch.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
                val contentValues = ContentValues()
                contentValues.put(COLUMN_BOOLEAN_BUTTON_VIBRATOR, isChecked.toString())
                //保存到数据库中
                controllerManager.superConfigDatabaseHelper!!.updateConfig(currentConfigId, contentValues)
                //做实际的设置
                controllerManager.elementController!!.setButtonVibrator(isChecked)
            }
        })
    }

    private fun loadEnhancedTouch() {
        val enhancedTouchSwitch = pageConfig.findViewById<SwitchCompat>(R.id.enhanced_touch_switch)
        AppSwitchStyle.apply(enhancedTouchSwitch)

        // 从数据库读取值，提供一个默认值 'false'
        val dbValue = controllerManager.superConfigDatabaseHelper!!.queryConfigAttribute(
            currentConfigId,
            COLUMN_BOOLEAN_ENHANCED_TOUCH,
            false.toString()
        ) as String?
        val isEnabled = java.lang.Boolean.parseBoolean(dbValue)
        // 更新 UI，但不触发监听器
        enhancedTouchSwitch.setOnCheckedChangeListener(null)
        enhancedTouchSwitch.isChecked = isEnabled
        controllerManager.touchController!!.setEnhancedTouch(isEnabled)
        // 重新设置监听器，用于用户手动操作
        enhancedTouchSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            val contentValues = ContentValues()
            // 使用我们新定义的常量来保存新的状态
            contentValues.put(PageConfigController.COLUMN_BOOLEAN_ENHANCED_TOUCH, isChecked.toString())
            // 更新到数据库
            controllerManager.superConfigDatabaseHelper!!.updateConfig(currentConfigId, contentValues)
            controllerManager.touchController!!.setEnhancedTouch(isChecked)
        }
    }

    /** Returns the profile name used by the Crown panel header. */
    fun getCurrentConfigName(): String {
        val configId = PreferenceManager.getDefaultSharedPreferences(context)
            .getLong(CURRENT_CONFIG_KEY, currentConfigId)
        return controllerManager.superConfigDatabaseHelper!!
            .queryConfigAttribute(configId, COLUMN_STRING_CONFIG_NAME, "default") as String
    }

    /** Returns the number of controls in the profile used by the Crown panel header. */
    fun getCurrentElementCount(): Int {
        val configId = PreferenceManager.getDefaultSharedPreferences(context)
            .getLong(CURRENT_CONFIG_KEY, currentConfigId)
        return controllerManager.superConfigDatabaseHelper!!
            .queryAllElementIds(configId).size
    }

    /**
     * 打开重命名当前配置的对话框
     */
    fun openRenameDialog() {
        val pageWindow = LayoutInflater.from(context).inflate(R.layout.page_window, null) as SuperPageLayout
        val title = pageWindow.findViewById<TextView>(R.id.window_title)
        title.text = getCrownDialogTitle(R.string.crown_profile_name_title)
        val editText = pageWindow.findViewById<EditText>(R.id.window_edittext)

        // 获取当前选中的配置名称
        val currentName = getCurrentConfigName()
        editText.setText(currentName)

        //窗口确认按钮
        pageWindow.findViewById<View>(R.id.window_confirm).setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                if (currentConfigId == 0L) {
                    returnPrePage(pageWindow.lastPage!!)
                    return
                }
                val configNewName = editText.text.toString()
                if (!configNewName.matches("^.{1,10}$".toRegex())) {
                    Toast.makeText(context, context.getString(R.string.crown_profile_name_error), Toast.LENGTH_SHORT).show()
                    return
                }
                val contentValues = ContentValues()
                contentValues.put(COLUMN_STRING_CONFIG_NAME, configNewName)
                //保存到数据库中
                controllerManager.superConfigDatabaseHelper!!.updateConfig(currentConfigId, contentValues)
                returnPrePage(pageWindow.lastPage!!)
                loadAllConfigToSpinner()
                loadCurrentConfig()
            }
        })
        //窗口取消按钮
        pageWindow.findViewById<View>(R.id.window_cancel).setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                returnPrePage(pageWindow.lastPage!!)
            }
        })
        controllerManager.superPagesController!!.openNewPage(pageWindow)
    }

    fun open() {
        controllerManager.superPagesController!!.openNewPage(pageConfig)
        pageConfig.post {
            val scrollView = pageConfig.findViewById<View>(R.id.crown_config_scroll)
            if (scrollView != null) {
                scrollView.scrollTo(0, 0)
            }
        }
        pageConfig.setPageReturnListener(object : SuperPageLayout.ReturnListener {
            override fun returnCallBack() {
                controllerManager.superPagesController!!.openNewPage(controllerManager.superPagesController!!.pageNull)
            }
        })
    }

    fun returnPrePage(prePage: SuperPageLayout) {
        controllerManager.superPagesController!!.openNewPage(prePage)
    }

    companion object {
        private const val CURRENT_CONFIG_KEY = "current_config_id"
        const val COLUMN_STRING_CONFIG_NAME = CrownProfileSchema.CONFIG_NAME_KEY
        const val COLUMN_BOOLEAN_TOUCH_ENABLE = "touch_enable"
        const val COLUMN_BOOLEAN_TOUCH_MODE = "touch_mode"
        private const val COLUMN_INT_TOUCH_SENSE = "touch_sense"
        const val COLUMN_BOOLEAN_GAME_VIBRATOR = "game_vibrator"
        const val COLUMN_BOOLEAN_BUTTON_VIBRATOR = "button_vibrator"
        const val COLUMN_LONG_CONFIG_ID = "config_id"
        private const val COLUMN_INT_MOUSE_WHEEL_SPEED = "mouse_wheel_speed"
        const val COLUMN_APPLY_TOUCHPAD_SENSITIVITY = "apply_touchpad_sensitivity"
        const val COLUMN_APPLY_MOUSE_WHEEL_SPEED = "apply_mouse_wheel_speed"
        const val COLUMN_BOOLEAN_ENHANCED_TOUCH = "enhanced_touch"
        const val COLUMN_STRING_QUICK_ACTION_IDS = "quick_action_ids"

        private const val CONFIG_SECTION_LAYOUT = 0
        private const val CONFIG_SECTION_TOUCH = 1
        private const val CONFIG_SECTION_FEEDBACK = 2
    }
}
