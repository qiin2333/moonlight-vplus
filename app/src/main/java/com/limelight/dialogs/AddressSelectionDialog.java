package com.limelight.dialogs;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;

import java.util.List;

public class AddressSelectionDialog {
    
    public interface OnAddressSelectedListener {
        void onAddressSelected(ComputerDetails.AddressTuple address);
    }
    
    private AlertDialog dialog;
    private ComputerDetails computerDetails;
    private OnAddressSelectedListener listener;
    private ComputerDetails.AddressTuple selectedAddress;
    private AddressListAdapter adapter;
    private ListView addressList;
    
    public AddressSelectionDialog(Context context, ComputerDetails computerDetails, OnAddressSelectedListener listener) {
        this.computerDetails = computerDetails;
        this.listener = listener;
        this.selectedAddress = computerDetails.activeAddress; // 默认选择当前活跃地址
        
        createDialog(context);
    }
    
    private void createDialog(Context context) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context, R.style.AppDialogStyle);
        View dialogView = LayoutInflater.from(context).inflate(R.layout.address_selection_dialog, null);
        
        // 设置主机名称
        TextView computerNameView = dialogView.findViewById(R.id.computer_name);
        computerNameView.setText(computerDetails.name);
        
        // 设置地址列表
        addressList = dialogView.findViewById(R.id.address_list);
        adapter = new AddressListAdapter(context, computerDetails.getAvailableAddresses());
        addressList.setAdapter(adapter);
        // 默认选中第一项，避免首次按确认只进入选择态
        if (adapter.getCount() > 0) {
            addressList.setSelection(0);
        }
        
        // 设置控制器支持
        setupControllerSupport();

        // 列表项点击：直接连接
        addressList.setOnItemClickListener((parent, view, position, id) -> {
            ComputerDetails.AddressTuple address = (ComputerDetails.AddressTuple) adapter.getItem(position);
            if (listener != null) {
                listener.onAddressSelected(address);
            }
            dialog.dismiss();
        });
        
        
        builder.setView(dialogView);
        dialog = builder.create();
    }
    
    public void show() {
        if (dialog != null) {
            dialog.show();
        }
    }
    
    public void dismiss() {
        if (dialog != null) {
            dialog.dismiss();
        }
    }
    
    /**
     * 设置控制器支持
     */
    private void setupControllerSupport() {
        // 设置ListView的焦点支持
        addressList.setFocusable(true);
        addressList.setFocusableInTouchMode(true);
        addressList.setClickable(true);
        
        // 设置初始焦点
        addressList.requestFocus();
        
        // 处理ListView的按键事件
        addressList.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                // 只处理确认键选择，让系统处理方向键导航
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || 
                    keyCode == KeyEvent.KEYCODE_ENTER) {
                    return handleListViewKeyEvent(keyCode);
                }
            }
            return false; // 让系统处理所有方向键导航
        });
    }
    
    /**
     * 处理ListView的按键事件
     */
    private boolean handleListViewKeyEvent(int keyCode) {
        int itemCount = adapter.getCount();
        if (itemCount == 0) return false;
        
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            // 直接连接当前项
            int selectedPosition = addressList.getSelectedItemPosition();
            if (selectedPosition >= 0 && selectedPosition < itemCount) {
                ComputerDetails.AddressTuple address = (ComputerDetails.AddressTuple) adapter.getItem(selectedPosition);
                if (listener != null) {
                    listener.onAddressSelected(address);
                }
                dialog.dismiss();
            }
            return true;
        }
        return false;
    }
    
    private class AddressListAdapter extends BaseAdapter {
        private Context context;
        private List<ComputerDetails.AddressTuple> addresses;
        
        public AddressListAdapter(Context context, List<ComputerDetails.AddressTuple> addresses) {
            this.context = context;
            this.addresses = addresses;
        }
        
        @Override
        public int getCount() {
            return addresses.size();
        }
        
        @Override
        public Object getItem(int position) {
            return addresses.get(position);
        }
        
        @Override
        public long getItemId(int position) {
            return position;
        }
        
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.address_list_item, parent, false);
                holder = new ViewHolder();
                // holder.addressIcon = convertView.findViewById(R.id.address_icon);
                holder.addressText = convertView.findViewById(R.id.address_text);
                holder.addressType = convertView.findViewById(R.id.address_type);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            
            ComputerDetails.AddressTuple address = addresses.get(position);
            
            // 设置地址文本
            holder.addressText.setText(address.toString());
            
            // 设置地址类型和图标
            String addressType = computerDetails.getAddressTypeDescription(address);
            holder.addressType.setText(addressType);
            
            // 设置焦点状态
            boolean isFocused = (position == addressList.getSelectedItemPosition());
            convertView.setSelected(isFocused);
            
            // 设置点击事件 - 直接连接
            convertView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onAddressSelected(address);
                }
                dialog.dismiss();
            });
            
            return convertView;
        }
        
        private class ViewHolder {
            ImageView addressIcon;
            TextView addressText;
            TextView addressType;
        }
    }
}
