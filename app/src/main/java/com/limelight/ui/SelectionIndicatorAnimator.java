package com.limelight.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import androidx.recyclerview.widget.RecyclerView;

import com.limelight.AppView;
import com.limelight.grid.GenericGridAdapter;

/**
 * 选中框动画控制器
 * 负责管理选中框的移动动画和位置计算
 */
public class SelectionIndicatorAnimator {

    private View selectionIndicator;
    private RecyclerView recyclerView;
    private GenericGridAdapter<?> adapter;
    private View rootView;

    // 动画配置
    private static final int NORMAL_ANIMATION_DURATION = 200;
    private static final int SCALE_ANIMATION_DURATION = 150;
    private static final int SCROLL_WAIT_DELAY = 50;
    private static final int RETRY_DELAY = 100;

    public SelectionIndicatorAnimator(View selectionIndicator, RecyclerView recyclerView,
                                      GenericGridAdapter<?> adapter, View rootView) {
        this.selectionIndicator = selectionIndicator;
        this.recyclerView = recyclerView;
        this.adapter = adapter;
        this.rootView = rootView;
    }

    /**
     * 更新RecyclerView和Adapter引用
     */
    public void updateReferences(RecyclerView recyclerView, GenericGridAdapter<?> adapter) {
        this.recyclerView = recyclerView;
        this.adapter = adapter;
    }

    /**
     * 移动选中框到指定位置
     *
     * @param position     目标位置
     * @param isFirstFocus 是否为第一次获得焦点（从位置0开始）
     */
    public void moveToPosition(int position, boolean isFirstFocus) {
        if (!isValidPosition(position)) {
            return;
        }

        RecyclerView.ViewHolder viewHolder = recyclerView.findViewHolderForAdapterPosition(position);
        if (viewHolder != null) {
            if (isFirstFocus) {
                // 第一次获得焦点，直接定位，不使用动画
                setIndicatorPosition(viewHolder.itemView, false);
            } else {
                // 正常情况：item在可见区域，使用动画
                animateToView(viewHolder.itemView);
            }
        } else {
            // 边界情况：item不在可见区域，需要滚动
            scrollToPositionAndAnimate(position);
        }
    }

    /**
     * 移动选中框到指定位置（默认使用动画）
     *
     * @param position 目标位置
     */
    public void moveToPosition(int position) {
        moveToPosition(position, false);
    }

    /**
     * 更新选中框位置（用于滚动时调用）
     *
     * @param position 当前选中位置
     */
    public void updatePosition(int position) {
        if (!isValidPosition(position)) {
            return;
        }

        RecyclerView.ViewHolder viewHolder = recyclerView.findViewHolderForAdapterPosition(position);
        if (viewHolder != null) {
            setIndicatorPositionFast(viewHolder.itemView);
        }
    }

    /**
     * 快速设置选中框位置 - 专用于滚动时的更新，最小化计算
     *
     * @param targetView 目标View
     */
    private void setIndicatorPositionFast(View targetView) {
        // 使用getLocationInWindow获取相对于窗口的绝对位置
        int[] targetLocation = new int[2];
        targetView.getLocationInWindow(targetLocation);
        
        // 获取根布局的位置作为参考点
        int[] rootLocation = new int[2];
        rootView.getLocationInWindow(rootLocation);
        
        // 计算相对于根布局的位置
        float targetX = targetLocation[0] - rootLocation[0];
        float targetY = targetLocation[1] - rootLocation[1];

        // 直接设置位置，不进行复杂的尺寸检查
        selectionIndicator.setTranslationX(targetX);
        selectionIndicator.setTranslationY(targetY);
        selectionIndicator.setVisibility(View.VISIBLE);
    }

    /**
     * 检查位置是否有效
     */
    private boolean isValidPosition(int position) {
        return selectionIndicator != null &&
                recyclerView != null &&
                adapter != null &&
                position >= 0 &&
                position < adapter.getCount();
    }

    /**
     * 动画移动到指定View
     */
    private void animateToView(View targetView) {
        // 检查是否有其他动画正在进行
        if (recyclerView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            // 如果RecyclerView正在滚动，等待滚动完成
            recyclerView.postDelayed(() -> {
                // 重新计算位置，因为滚动可能已经改变
                RecyclerView.ViewHolder viewHolder = recyclerView.findViewHolderForAdapterPosition(getCurrentPosition());
                if (viewHolder != null) {
                    animateToView(viewHolder.itemView);
                }
            }, RETRY_DELAY);
        } else {
            // 平滑移动到新位置
            setIndicatorPosition(targetView, true);
        }
    }

    /**
     * 滚动到指定位置并执行动画
     */
    private void scrollToPositionAndAnimate(int position) {
        // 暂时隐藏选中框，避免在滚动过程中显示错误位置
        selectionIndicator.setVisibility(View.INVISIBLE);

        // 平滑滚动到指定位置
        recyclerView.smoothScrollToPosition(position);

        // 使用OnScrollListener来检测滚动完成
        RecyclerView.OnScrollListener scrollListener = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                // 当滚动停止时，执行选中框动画
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    // 移除这个临时的滚动监听器
                    recyclerView.removeOnScrollListener(this);

                    // 延迟一小段时间确保滚动完全停止，然后执行选中框动画
                    recyclerView.postDelayed(() -> {
                        RecyclerView.ViewHolder viewHolder = recyclerView.findViewHolderForAdapterPosition(position);
                        if (viewHolder != null) {
                            setIndicatorPosition(viewHolder.itemView, true);
                            // 添加缩放强调效果
                            addScaleAnimation();
                        }
                    }, SCROLL_WAIT_DELAY);
                }
            }
        };

        // 添加临时的滚动监听器
        recyclerView.addOnScrollListener(scrollListener);
    }

    /**
     * 设置选中框位置
     *
     * @param targetView    目标View
     * @param withAnimation 是否使用动画
     */
    private void setIndicatorPosition(View targetView, boolean withAnimation) {
        // 使用getLocationInWindow获取相对于窗口的绝对位置
        int[] targetLocation = new int[2];
        targetView.getLocationInWindow(targetLocation);
        
        // 获取根布局的位置作为参考点
        int[] rootLocation = new int[2];
        rootView.getLocationInWindow(rootLocation);
        
        // 计算相对于根布局的位置
        float targetX = targetLocation[0] - rootLocation[0];
        float targetY = targetLocation[1] - rootLocation[1];

        // 缓存尺寸，避免重复设置
        int targetWidth = targetView.getWidth();
        int targetHeight = targetView.getHeight();

        // 只有当尺寸发生变化时才更新LayoutParams
        ViewGroup.LayoutParams params = selectionIndicator.getLayoutParams();
        if (params.width != targetWidth || params.height != targetHeight) {
            params.width = targetWidth;
            params.height = targetHeight;
            selectionIndicator.setLayoutParams(params);
        }

        // 显示选中框
        selectionIndicator.setVisibility(View.VISIBLE);

        if (withAnimation) {
            // 使用更快的动画方法
            selectionIndicator.animate()
                    .translationX(targetX)
                    .translationY(targetY)
                    .setDuration(Math.min(NORMAL_ANIMATION_DURATION, 120)) // 进一步减少动画时间
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f)) // 使用更快的插值器
                    .start();
        } else {
            // 直接设置位置，使用translationX/Y提高性能
            selectionIndicator.setTranslationX(targetX);
            selectionIndicator.setTranslationY(targetY);
        }
    }

    /**
     * 添加缩放强调动画
     */
    private void addScaleAnimation() {
        selectionIndicator.setScaleX(0.8f);
        selectionIndicator.setScaleY(0.8f);
        selectionIndicator.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(SCALE_ANIMATION_DURATION)
                .start();
    }

    /**
     * 设置当前选中位置获取器
     */
    public interface PositionProvider {
        int getCurrentPosition();
    }

    private PositionProvider positionProvider;

    public void setPositionProvider(PositionProvider provider) {
        this.positionProvider = provider;
    }

    /**
     * 获取当前选中位置
     */
    private int getCurrentPosition() {
        if (positionProvider != null) {
            return positionProvider.getCurrentPosition();
        }
        return -1;
    }

    /**
     * 查找running app的位置
     *
     * @param runningAppId running app的ID，0表示没有running app
     * @return running app的位置，如果没有找到返回-1
     */
    public int findRunningAppPosition(int runningAppId) {
        if (runningAppId == 0 || adapter == null) {
            return -1;
        }

        // 遍历所有item查找running app
        for (int i = 0; i < adapter.getCount(); i++) {
            Object item = adapter.getItem(i);
            if (item instanceof AppView.AppObject) {
                AppView.AppObject appObject = (AppView.AppObject) item;
                if (appObject.app.getAppId() == runningAppId && appObject.isRunning) {
                    return i;
                }
            }
        }

        return -1;
    }

    /**
     * 移动到running app位置（如果有的话）
     *
     * @param runningAppId running app的ID
     * @return 是否成功移动到running app
     */
    public boolean moveToRunningApp(int runningAppId) {
        int runningAppPosition = findRunningAppPosition(runningAppId);
        if (runningAppPosition >= 0) {
            // 移动到running app位置，不使用动画（因为是默认焦点）
            moveToPosition(runningAppPosition, true);
            return true;
        }
        return false;
    }
}
