package com.limelight.dialogs

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import com.limelight.R
import com.limelight.nvstream.http.ComputerDetails

class AddressSelectionDialog(
    context: Context,
    private val computerDetails: ComputerDetails,
    private val listener: OnAddressSelectedListener?
) {

    fun interface OnAddressSelectedListener {
        fun onAddressSelected(address: ComputerDetails.AddressTuple)
    }

    private val dialog: AlertDialog
    private val hostActivity = context as? Activity
    private val adapter: AddressListAdapter
    private val addressList: ListView

    init {
        val builder = AlertDialog.Builder(context, R.style.AppDialogStyle)
        val dialogView = LayoutInflater.from(context).inflate(R.layout.address_selection_dialog, null)

        val computerNameView = dialogView.findViewById<TextView>(R.id.computer_name)
        computerNameView.text = computerDetails.name

        addressList = dialogView.findViewById(R.id.address_list)
        adapter = AddressListAdapter(context, computerDetails.availableAddresses)
        addressList.adapter = adapter
        if (adapter.count > 0) {
            addressList.setSelection(0)
        }

        setupControllerSupport()

        addressList.setOnItemClickListener { _, _, position, _ ->
            val address = adapter.getItem(position) as ComputerDetails.AddressTuple
            listener?.onAddressSelected(address)
            dismiss()
        }

        builder.setView(dialogView)
        dialog = builder.create()
    }

    fun show() {
        if (hostActivity?.isFinishing == true || hostActivity?.isDestroyed == true) {
            return
        }

        try {
            dialog.show()
        } catch (e: IllegalArgumentException) {
            // Window token 或视图状态异常时安全忽略
        } catch (e: RuntimeException) {
            // 兜底：显示失败不应导致调用方崩溃
        }
    }

    fun dismiss() {
        if (hostActivity?.isFinishing == true || hostActivity?.isDestroyed == true) {
            return
        }

        try {
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        } catch (e: IllegalArgumentException) {
            // DecorView 已与 WindowManager 解绑，安全忽略
        } catch (e: RuntimeException) {
            // 兜底：dismiss 失败不应让 Activity 生命周期崩溃
        }
    }

    private fun setupControllerSupport() {
        addressList.isFocusable = true
        addressList.isFocusableInTouchMode = true
        addressList.isClickable = true

        addressList.requestFocus()

        addressList.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    return@setOnKeyListener handleListViewKeyEvent(keyCode)
                }
            }
            false
        }
    }

    private fun handleListViewKeyEvent(keyCode: Int): Boolean {
        val itemCount = adapter.count
        if (itemCount == 0) return false

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            val selectedPosition = addressList.selectedItemPosition
            if (selectedPosition in 0 until itemCount) {
                val address = adapter.getItem(selectedPosition) as ComputerDetails.AddressTuple
                listener?.onAddressSelected(address)
                dismiss()
            }
            return true
        }
        return false
    }

    private inner class AddressListAdapter(
        private val context: Context,
        private val addresses: List<ComputerDetails.AddressTuple>
    ) : BaseAdapter() {

        override fun getCount(): Int = addresses.size

        override fun getItem(position: Int): Any = addresses[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder: ViewHolder
            val view: View

            if (convertView == null) {
                view = LayoutInflater.from(context).inflate(R.layout.address_list_item, parent, false)
                holder = ViewHolder()
                holder.addressText = view.findViewById(R.id.address_text)
                holder.addressType = view.findViewById(R.id.address_type)
                view.tag = holder
            } else {
                view = convertView
                holder = view.tag as ViewHolder
            }

            val address = addresses[position]

            holder.addressText.text = address.toString()

            val addressType = computerDetails.getAddressTypeDescription(address)
            holder.addressType.text = addressType

            val isFocused = position == addressList.selectedItemPosition
            view.isSelected = isFocused

            view.setOnClickListener {
                listener?.onAddressSelected(address)
                dismiss()
            }

            return view
        }

        private inner class ViewHolder {
            var addressIcon: ImageView? = null
            lateinit var addressText: TextView
            lateinit var addressType: TextView
        }
    }
}
