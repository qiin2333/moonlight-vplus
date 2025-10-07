package com.limelight.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import androidx.recyclerview.widget.RecyclerView;

import com.limelight.AppView;
import com.limelight.grid.GenericGridAdapter;

/**
 * Selection Indicator Animator
 * Manages the animation and position calculation of the selection indicator
 */
public class SelectionIndicatorAnimator {

    private View selectionIndicator;
    private RecyclerView recyclerView;
    private GenericGridAdapter<?> adapter;
    private View rootView;

    // Animation configuration
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
     * Update RecyclerView and Adapter references
     */
    public void updateReferences(RecyclerView recyclerView, GenericGridAdapter<?> adapter) {
        this.recyclerView = recyclerView;
        this.adapter = adapter;
    }

    /**
     * Move selection indicator to specified position
     *
     * @param position     Target position
     * @param isFirstFocus Whether this is the first focus (starting from position 0)
     */
    public void moveToPosition(int position, boolean isFirstFocus) {
        if (!isValidPosition(position)) {
            return;
        }

        RecyclerView.ViewHolder viewHolder = recyclerView.findViewHolderForAdapterPosition(position);
        if (viewHolder != null) {
            if (isFirstFocus) {
                // First focus, position directly without animation
                setIndicatorPosition(viewHolder.itemView, false);
            } else {
                // Normal case: item is in visible area, use animation
                animateToView(viewHolder.itemView);
            }
        } else {
            // Edge case: item is not in visible area, need to scroll
            scrollToPositionAndAnimate(position);
        }
    }

    /**
     * Move selection indicator to specified position (with animation by default)
     *
     * @param position Target position
     */
    public void moveToPosition(int position) {
        moveToPosition(position, false);
    }

    /**
     * Update selection indicator position (called during scrolling)
     *
     * @param position Current selected position
     * @return true if position was successfully updated, false if item is not visible
     */
    public boolean updatePosition(int position) {
        if (!isValidPosition(position)) {
            return false;
        }

        RecyclerView.ViewHolder viewHolder = recyclerView.findViewHolderForAdapterPosition(position);
        if (viewHolder != null) {
            setIndicatorPositionFast(viewHolder.itemView);
            return true;
        }
        
        // Item is not visible (scrolled out of screen)
        return false;
    }

    /**
     * Fast set indicator position - dedicated for scroll updates, minimizing calculations
     *
     * @param targetView Target View
     */
    private void setIndicatorPositionFast(View targetView) {
        // Use getLocationInWindow to get absolute position relative to window
        int[] targetLocation = new int[2];
        targetView.getLocationInWindow(targetLocation);
        
        // Get root layout position as reference point
        int[] rootLocation = new int[2];
        rootView.getLocationInWindow(rootLocation);
        
        // Calculate position relative to root layout
        float targetX = targetLocation[0] - rootLocation[0];
        float targetY = targetLocation[1] - rootLocation[1];

        // Set position directly without complex size checks
        selectionIndicator.setTranslationX(targetX);
        selectionIndicator.setTranslationY(targetY);
        selectionIndicator.setVisibility(View.VISIBLE);
    }

    /**
     * Check if position is valid
     */
    private boolean isValidPosition(int position) {
        return selectionIndicator != null &&
                recyclerView != null &&
                adapter != null &&
                position >= 0 &&
                position < adapter.getCount();
    }

    /**
     * Animate to specified View
     */
    private void animateToView(View targetView) {
        // Check if another animation is in progress
        if (recyclerView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            // If RecyclerView is scrolling, wait for scroll to complete
            recyclerView.postDelayed(() -> {
                // Recalculate position as scrolling may have changed it
                RecyclerView.ViewHolder viewHolder = recyclerView.findViewHolderForAdapterPosition(getCurrentPosition());
                if (viewHolder != null) {
                    animateToView(viewHolder.itemView);
                }
            }, RETRY_DELAY);
        } else {
            // Smoothly move to new position
            setIndicatorPosition(targetView, true);
        }
    }

    /**
     * Scroll to specified position and execute animation
     */
    private void scrollToPositionAndAnimate(int position) {
        // Temporarily hide indicator to avoid showing wrong position during scroll
        selectionIndicator.setVisibility(View.INVISIBLE);

        // Smooth scroll to specified position
        recyclerView.smoothScrollToPosition(position);

        // Use OnScrollListener to detect scroll completion
        RecyclerView.OnScrollListener scrollListener = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                // When scrolling stops, execute indicator animation
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    // Remove this temporary scroll listener
                    recyclerView.removeOnScrollListener(this);

                    // Delay briefly to ensure scroll is completely stopped, then execute indicator animation
                    recyclerView.postDelayed(() -> {
                        RecyclerView.ViewHolder viewHolder = recyclerView.findViewHolderForAdapterPosition(position);
                        if (viewHolder != null) {
                            setIndicatorPosition(viewHolder.itemView, true);
                            // Add scale emphasis effect
                            addScaleAnimation();
                        }
                    }, SCROLL_WAIT_DELAY);
                }
            }
        };

        // Add temporary scroll listener
        recyclerView.addOnScrollListener(scrollListener);
    }

    /**
     * Set indicator position
     *
     * @param targetView    Target View
     * @param withAnimation Whether to use animation
     */
    private void setIndicatorPosition(View targetView, boolean withAnimation) {
        // Use getLocationInWindow to get absolute position relative to window
        int[] targetLocation = new int[2];
        targetView.getLocationInWindow(targetLocation);
        
        // Get root layout position as reference point
        int[] rootLocation = new int[2];
        rootView.getLocationInWindow(rootLocation);
        
        // Calculate position relative to root layout
        float targetX = targetLocation[0] - rootLocation[0];
        float targetY = targetLocation[1] - rootLocation[1];

        // Cache dimensions to avoid repeated settings
        int targetWidth = targetView.getWidth();
        int targetHeight = targetView.getHeight();

        // Only update LayoutParams when dimensions change
        ViewGroup.LayoutParams params = selectionIndicator.getLayoutParams();
        if (params.width != targetWidth || params.height != targetHeight) {
            params.width = targetWidth;
            params.height = targetHeight;
            selectionIndicator.setLayoutParams(params);
        }

        // Show indicator
        selectionIndicator.setVisibility(View.VISIBLE);

        if (withAnimation) {
            // Use faster animation method
            selectionIndicator.animate()
                    .translationX(targetX)
                    .translationY(targetY)
                    .setDuration(Math.min(NORMAL_ANIMATION_DURATION, 120)) // Further reduce animation time
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f)) // Use faster interpolator
                    .start();
        } else {
            // Set position directly, use translationX/Y for better performance
            selectionIndicator.setTranslationX(targetX);
            selectionIndicator.setTranslationY(targetY);
        }
    }

    /**
     * Add scale emphasis animation
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
     * Interface for current selected position provider
     */
    public interface PositionProvider {
        int getCurrentPosition();
    }

    private PositionProvider positionProvider;

    public void setPositionProvider(PositionProvider provider) {
        this.positionProvider = provider;
    }

    public void hideIndicator() {
        selectionIndicator.setVisibility(View.INVISIBLE);
    }

    public void showIndicator() {
        selectionIndicator.setVisibility(View.VISIBLE);
    }

    /**
     * Get current selected position
     */
    private int getCurrentPosition() {
        if (positionProvider != null) {
            return positionProvider.getCurrentPosition();
        }
        return -1;
    }
}
