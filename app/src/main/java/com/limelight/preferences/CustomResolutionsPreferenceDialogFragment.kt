package com.limelight.preferences

import android.content.Context
import android.content.DialogInterface
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceDialogFragmentCompat
import com.limelight.R
import com.limelight.ui.UiDialogKeyHandler
import com.limelight.utils.AppDialogStyler
import kotlin.math.roundToInt

class CustomResolutionsPreferenceDialogFragment : PreferenceDialogFragmentCompat() {
    private data class InputControls(
        val root: View,
        val widthField: EditText,
        val heightField: EditText,
        val addButton: Button
    )

    private var editingField: EditText? = null
    private var inputMethodManager: InputMethodManager? = null
    private var dialogBackCallback: OnBackPressedCallback? = null
    private var platformBackCallback: OnBackInvokedCallback? = null

    private fun getPref(): CustomResolutionsPreference =
        preference as CustomResolutionsPreference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.AppDialogStyle)
    }

    override fun onCreateDialogView(context: Context): View {
        val pref = getPref()
        val configuration = context.resources.configuration
        val layoutSpec = customResolutionDialogLayoutSpec(
            isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            screenHeightDp = configuration.screenHeightDp
        )
        val body = createMainLayout(context, layoutSpec)
        val controls = createInputControls(context, pref)
        val list = createListView(context, pref)
        val inputPane = createInputPane(context, controls.root)

        addContent(body, list, inputPane, layoutSpec)
        configureControllerNavigation(context, pref, list, controls, layoutSpec)
        body.post { controls.widthField.requestFocus() }
        return body
    }

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)
        getPref().loadStoredResolutions()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawableResource(R.drawable.app_dialog_bg_cute)
        }
        tintDialogButtons()
    }

    override fun onDestroyView() {
        clearBackCallbacks()
        leaveEditing()
        getPref().adapter.setOnDeleteFocusRestoreListener(null)
        super.onDestroyView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        clearBackCallbacks()
        super.onDismiss(dialog)
    }

    private fun createMainLayout(
        context: Context,
        spec: CustomResolutionDialogLayoutSpec
    ): LinearLayout {
        val metrics = context.resources.displayMetrics
        val dialogWidth = minOf(
            (metrics.widthPixels * spec.widthFraction).roundToInt(),
            dpToPx(context, spec.maxWidthDp)
        )
        val dialogHeight = minOf(
            (metrics.heightPixels * spec.heightFraction).roundToInt(),
            dpToPx(context, spec.maxHeightDp)
        )
        return LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dialogWidth, dialogHeight).also {
                it.gravity = Gravity.CENTER
            }
            orientation = if (spec.useTwoPane) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            val padding = dpToPx(context, if (spec.useTwoPane) 12 else 16)
            setPadding(padding, padding, padding, padding)
        }
    }

    private fun createListView(context: Context, pref: CustomResolutionsPreference): ListView {
        return ListView(context).apply {
            id = View.generateViewId()
            adapter = pref.adapter
            dividerHeight = dpToPx(context, 1)
            divider = ColorDrawable(ContextCompat.getColor(context, R.color.app_dialog_outline))
            setBackgroundColor(Color.TRANSPARENT)
            cacheColorHint = Color.TRANSPARENT
            itemsCanFocus = true
            isFocusable = true
            isFocusableInTouchMode = true
            ContextCompat.getDrawable(context, R.drawable.app_dialog_list_item_bg)?.let {
                selector = it
            }
        }
    }

    private fun createInputControls(
        context: Context,
        pref: CustomResolutionsPreference
    ): InputControls {
        val inputRow = LayoutInflater.from(context).inflate(R.layout.custom_resolutions_form, null)
        val widthField = inputRow.findViewById<EditText>(R.id.custom_resolution_width_field)
        val heightField = inputRow.findViewById<EditText>(R.id.custom_resolution_height_field)
        val addButton = inputRow.findViewById<Button>(R.id.add_resolution_button)

        addButton.setOnClickListener { pref.onSubmitResolution(widthField, heightField) }
        return InputControls(inputRow, widthField, heightField, addButton)
    }

    private fun createInputPane(context: Context, inputRow: View): ScrollView {
        return ScrollView(context).apply {
            id = View.generateViewId()
            isFillViewport = true
            addView(
                inputRow,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun addContent(
        body: LinearLayout,
        list: ListView,
        inputPane: ScrollView,
        spec: CustomResolutionDialogLayoutSpec
    ) {
        if (spec.useTwoPane) {
            body.addView(
                list,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.85f).apply {
                    marginEnd = dpToPx(body.context, 12)
                }
            )
            body.addView(
                inputPane,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f)
            )
        } else {
            body.addView(
                list,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
            body.addView(
                inputPane,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(body.context, 12)
                }
            )
        }
    }

    private fun configureControllerNavigation(
        context: Context,
        pref: CustomResolutionsPreference,
        list: ListView,
        controls: InputControls,
        spec: CustomResolutionDialogLayoutSpec
    ) {
        inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

        fun focusListItem(index: Int = list.selectedItemPosition): Boolean {
            if (pref.adapter.count == 0) return list.requestFocus()
            val targetIndex = index.takeIf { it in 0 until pref.adapter.count } ?: 0
            list.setSelection(targetIndex)
            list.post {
                val childIndex = targetIndex - list.firstVisiblePosition
                list.getChildAt(childIndex)?.requestFocus() ?: list.requestFocus()
            }
            return true
        }

        configureField(controls.widthField) { direction ->
            when (direction) {
                View.FOCUS_DOWN -> controls.heightField.requestFocus()
                View.FOCUS_LEFT -> spec.useTwoPane && focusListItem()
                View.FOCUS_UP -> !spec.useTwoPane && focusListItem(pref.adapter.count - 1)
                else -> false
            }
        }
        configureField(controls.heightField) { direction ->
            when (direction) {
                View.FOCUS_UP -> controls.widthField.requestFocus()
                View.FOCUS_DOWN -> controls.addButton.requestFocus()
                View.FOCUS_LEFT -> spec.useTwoPane && focusListItem()
                else -> false
            }
        }
        controls.addButton.setOnKeyListener { _, keyCode, event ->
            when {
                keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                    if (event.action == KeyEvent.ACTION_UP) controls.heightField.requestFocus()
                    true
                }
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT && spec.useTwoPane -> {
                    if (event.action == KeyEvent.ACTION_UP) focusListItem()
                    true
                }
                else -> handleActionKey(event, keyCode) { controls.addButton.performClick() }
            }
        }

        list.nextFocusRightId = controls.widthField.id
        list.setOnItemClickListener { _, view, _, _ ->
            (view as? ViewGroup)?.getChildAt(1)?.requestFocus()
        }
        list.setOnKeyListener { _, keyCode, event ->
            when {
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_UP) controls.widthField.requestFocus()
                    true
                }
                else -> handleActionKey(event, keyCode) {
                    val selected = list.selectedItemPosition
                        .takeIf { it in 0 until pref.adapter.count }
                        ?: 0
                    focusListItem(selected)
                    list.post {
                        val child = list.getChildAt(selected - list.firstVisiblePosition)
                        (child as? ViewGroup)?.getChildAt(1)?.requestFocus()
                    }
                }
            }
        }

        pref.adapter.setOnDeleteFocusRestoreListener { targetIndex ->
            list.post {
                if (pref.adapter.count == 0) {
                    controls.widthField.requestFocus()
                } else {
                    focusListItem(targetIndex)
                }
            }
        }

        controls.widthField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                controls.heightField.requestFocus()
                enterEditing(controls.heightField)
                true
            } else {
                false
            }
        }
        controls.heightField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                pref.onSubmitResolution(controls.widthField, controls.heightField)
                true
            } else {
                false
            }
        }
    }

    private fun configureField(field: EditText, navigate: (Int) -> Boolean) {
        field.isFocusableInTouchMode = true
        field.showSoftInputOnFocus = false
        field.setOnClickListener { enterEditing(field) }
        field.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && editingField === field) leaveEditing()
        }
        field.setOnKeyListener { _, keyCode, event ->
            if (editingField === field) {
                if (isDismissKey(keyCode)) {
                    if (event.action == KeyEvent.ACTION_UP) leaveEditing()
                    true
                } else {
                    false
                }
            } else {
                val direction = keyDirection(keyCode)
                if (direction != null) {
                    if (event.action == KeyEvent.ACTION_UP) navigate(direction)
                    true
                } else {
                    handleActionKey(event, keyCode) { enterEditing(field) }
                }
            }
        }
    }

    private fun handleActionKey(event: KeyEvent, keyCode: Int, onConfirm: () -> Unit): Boolean {
        return UiDialogKeyHandler.handle(
            action = event.action,
            keyCode = keyCode,
            onDismiss = ::handleDismissRequest,
            onConfirm = onConfirm
        )
    }

    private fun enterEditing(field: EditText) {
        editingField = field
        isCancelable = false
        field.showSoftInputOnFocus = true
        field.requestFocus()
        field.setSelection(field.text.length)
        field.post { inputMethodManager?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun leaveEditing() {
        val field = editingField ?: return
        editingField = null
        isCancelable = true
        field.showSoftInputOnFocus = false
        inputMethodManager?.hideSoftInputFromWindow(field.windowToken, 0)
    }

    private fun handleDismissRequest() {
        if (editingField != null) {
            leaveEditing()
        } else {
            dialog?.cancel()
        }
    }

    private fun tintDialogButtons() {
        val alert = dialog as? AlertDialog ?: return
        AppDialogStyler.tintTitle(alert, requireContext())
        AppDialogStyler.installDismissKeys(
            alert,
            onDismiss = ::handleDismissRequest,
            dismissOnBack = true
        )
        if (dialogBackCallback == null) {
            dialogBackCallback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = handleDismissRequest()
            }.also { callback ->
                alert.onBackPressedDispatcher.addCallback(alert, callback)
            }
        }
        registerPlatformBackCallback()
        val accentColor = ContextCompat.getColor(requireContext(), R.color.app_dialog_accent_color)
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
            .forEach { buttonId ->
                alert.getButton(buttonId)?.setTextColor(accentColor)
            }
    }

    private fun registerPlatformBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || platformBackCallback != null) {
            return
        }
        val callback = OnBackInvokedCallback(::handleDismissRequest)
        requireActivity().onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback
        )
        platformBackCallback = callback
    }

    private fun unregisterPlatformBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val callback = platformBackCallback ?: return
        activity?.onBackInvokedDispatcher?.unregisterOnBackInvokedCallback(callback)
        platformBackCallback = null
    }

    private fun clearBackCallbacks() {
        unregisterPlatformBackCallback()
        dialogBackCallback?.remove()
        dialogBackCallback = null
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        val settingsActivity = requireActivity() as StreamSettings
        settingsActivity.reloadSettings()
    }

    companion object {
        fun newInstance(key: String): CustomResolutionsPreferenceDialogFragment {
            return CustomResolutionsPreferenceDialogFragment().apply {
                arguments = Bundle(1).apply { putString(ARG_KEY, key) }
            }
        }

        private fun keyDirection(keyCode: Int): Int? {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                else -> null
            }
        }

        private fun isDismissKey(keyCode: Int): Boolean {
            return keyCode == KeyEvent.KEYCODE_BACK ||
                keyCode == KeyEvent.KEYCODE_ESCAPE ||
                keyCode == KeyEvent.KEYCODE_BUTTON_B
        }

        private fun dpToPx(context: Context, value: Int): Int {
            val density = context.resources.displayMetrics.density
            return (value * density + 0.5f).toInt()
        }
    }
}
