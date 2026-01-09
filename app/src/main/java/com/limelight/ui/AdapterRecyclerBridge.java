package com.limelight.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A small bridge to reuse existing BaseAdapter implementations (like GenericGridAdapter)
 * inside a RecyclerView. It will call BaseAdapter.getView() and attach the returned
 * view into the ViewHolder container.
 */
public class AdapterRecyclerBridge extends RecyclerView.Adapter<AdapterRecyclerBridge.VH> {
    private final BaseAdapter baseAdapter;
    private final Context context;
    private OnItemClickListener onItemClickListener;
    private OnItemKeyListener onItemKeyListener;
    private OnItemLongClickListener onItemLongClickListener;
    
    
    // A键长按检测相关
    private long aKeyDownTime = 0;
    private static final long LONG_PRESS_DURATION = 1000; // 1秒长按
    private final android.os.Handler longPressHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable longPressRunnable;

    public AdapterRecyclerBridge(Context context, BaseAdapter baseAdapter) {
        this.context = context;
        this.baseAdapter = baseAdapter;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (baseAdapter == null) {
            // Return an empty placeholder view
            View placeholder = new View(parent.getContext());
            return new VH(placeholder);
        }
        // Let baseAdapter create a view by calling getView with null convertView to inflate layout
        View v = baseAdapter.getView(0, null, parent);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        if (baseAdapter == null) {
            return;
        }
        
        // 优化：避免重复设置相同的监听器
        boolean needsListenerSetup = holder.container.getTag() == null;
        
        // Ask the base adapter to populate the provided convertView (holder.container)
        View convert = holder.container;
        View populated = baseAdapter.getView(position, convert, (ViewGroup) convert.getParent());

        if (populated != convert) {
            // Replace child views if the base adapter returned a different view instance
            ViewGroup parent = (ViewGroup) convert.getParent();
            if (parent != null) {
                int index = parent.indexOfChild(convert);
                parent.removeViewAt(index);
                parent.addView(populated, index);
                holder.container = populated;
                needsListenerSetup = true; // 新view需要设置监听器
            }
        }
        
        // 只在需要时设置焦点和监听器
        if (needsListenerSetup) {
            holder.container.setFocusable(true);
            holder.container.setClickable(true);
            
            // 标记已设置监听器
            holder.container.setTag("listeners_set");
        }
        
        // 只在需要时设置监听器，避免重复设置
        if (needsListenerSetup) {
            // 设置硬件加速层以提高性能
            holder.container.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            
            // 设置点击监听器（通过OnItemClickListener统一处理）
            // 注意：使用 holder.getAdapterPosition() 而不是捕获 position 参数，以支持RecyclerView的回收机制
            holder.container.setOnClickListener(v -> {
                if (onItemClickListener != null) {
                    int adapterPosition = holder.getAdapterPosition();
                    if (adapterPosition != RecyclerView.NO_POSITION) {
                        onItemClickListener.onItemClick(adapterPosition, baseAdapter.getItem(adapterPosition));
                    }
                }
            });
            
            // 设置按键监听器
            holder.container.setOnKeyListener((v, keyCode, event) -> {
                // 获取当前adapter位置，而不是捕获的position
                int adapterPosition = holder.getAdapterPosition();
                if (adapterPosition == RecyclerView.NO_POSITION) {
                    return false;
                }
                
                if (onItemKeyListener != null) {
                    return onItemKeyListener.onItemKey(adapterPosition, baseAdapter.getItem(adapterPosition), keyCode, event);
                }
                
                // 适配器内部处理A键长按检测
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || 
                    keyCode == android.view.KeyEvent.KEYCODE_BUTTON_A) {
                    
                    if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                        // A键按下 - 开始长按检测
                        aKeyDownTime = System.currentTimeMillis();
                        longPressRunnable = () -> {
                            // 长按触发 - 再次获取当前位置
                            int longPressPosition = holder.getAdapterPosition();
                            if (longPressPosition != RecyclerView.NO_POSITION && onItemLongClickListener != null) {
                                onItemLongClickListener.onItemLongClick(longPressPosition, baseAdapter.getItem(longPressPosition));
                            }
                        };
                        longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_DURATION);
                        return true;
                        
                    } else if (event.getAction() == android.view.KeyEvent.ACTION_UP) {
                        // A键释放 - 检查是否为短按
                        long pressDuration = System.currentTimeMillis() - aKeyDownTime;
                        
                        // 取消长按检测
                        if (longPressRunnable != null) {
                            longPressHandler.removeCallbacks(longPressRunnable);
                            longPressRunnable = null;
                        }
                        
                        // 如果按下时间小于长按阈值，执行短按操作
                        if (pressDuration < LONG_PRESS_DURATION) {
                            if (onItemClickListener != null && adapterPosition != RecyclerView.NO_POSITION) {
                                onItemClickListener.onItemClick(adapterPosition, baseAdapter.getItem(adapterPosition));
                            }
                        }
                        return true;
                    }
                }
                
                return false;
            });
            
            // 设置长按监听器
            holder.container.setOnLongClickListener(v -> {
                if (onItemLongClickListener != null) {
                    int adapterPosition = holder.getAdapterPosition();
                    if (adapterPosition != RecyclerView.NO_POSITION) {
                        boolean result = onItemLongClickListener.onItemLongClick(adapterPosition, baseAdapter.getItem(adapterPosition));
                        return result;
                    }
                }
                return false;
            });
        }
    }

    @Override
    public int getItemCount() {
        return baseAdapter != null ? baseAdapter.getCount() : 0;
    }
    
    /**
     * 设置item点击监听器
     */
    public void setOnItemClickListener(OnItemClickListener listener) {
        this.onItemClickListener = listener;
    }
    
    /**
     * 设置item按键监听器
     */
    public void setOnItemKeyListener(OnItemKeyListener listener) {
        this.onItemKeyListener = listener;
    }
    
    /**
     * 设置item长按监听器
     */
    public void setOnItemLongClickListener(OnItemLongClickListener listener) {
        this.onItemLongClickListener = listener;
    }
    
    /**
     * Item点击监听器接口
     */
    public interface OnItemClickListener {
        void onItemClick(int position, Object item);
    }
    
    /**
     * Item按键监听器接口
     */
    public interface OnItemKeyListener {
        boolean onItemKey(int position, Object item, int keyCode, android.view.KeyEvent event);
    }
    
    /**
     * Item长按监听器接口
     */
    public interface OnItemLongClickListener {
        boolean onItemLongClick(int position, Object item);
    }

    /**
     * 清理资源
     */
    public void cleanup() {
        // 清理长按检测
        if (longPressRunnable != null) {
            longPressHandler.removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
    }

    public static class VH extends RecyclerView.ViewHolder {
        public View container;

        public VH(@NonNull View itemView) {
            super(itemView);
            this.container = itemView;
        }
    }
}
