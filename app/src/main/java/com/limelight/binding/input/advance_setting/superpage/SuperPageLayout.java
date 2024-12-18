package com.limelight.binding.input.advance_setting.superpage;


import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;

public class SuperPageLayout extends FrameLayout {

    public interface DoubleFingerSwipeListener{
        void onRightSwipe();
        void onLeftSwipe();
    }

    public interface ReturnListener {
        void returnCallBack();
    }

    private static final int SWIPE_THRESHOLD = 70;
    private float startX;
    private boolean isTwoFingerSwipe = false;
    private boolean isSwipeActionDone = false;
    private DoubleFingerSwipeListener doubleFingerSwipeListener;
    private ReturnListener returnListener;
    private boolean disableTouch = false;
    private ObjectAnimator animator;
    private SuperPageLayout lastPage;


    public SuperPageLayout(Context context) {
        super(context);
    }

    public SuperPageLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SuperPageLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public SuperPageLayout(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }



    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (disableTouch){
            return true;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = ev.getX();
                isTwoFingerSwipe = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (ev.getPointerCount() == 2) {
                    isTwoFingerSwipe = true;
                    startX = ev.getX(1);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (isTwoFingerSwipe && ev.getPointerCount() == 2) {
                    float diffX = ev.getX(1) - startX;
                    if (Math.abs(diffX) > SWIPE_THRESHOLD) {
                        // 拦截双指滑动事件
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (ev.getPointerCount() == 2) {
                    isTwoFingerSwipe = false;
                }
                break;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isTwoFingerSwipe && doubleFingerSwipeListener != null) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    float diffX = event.getX(1) - startX;
                    if (Math.abs(diffX) > SWIPE_THRESHOLD && !isSwipeActionDone) {
                        if (diffX > 0) {
                            // 处理右滑操作
                            doubleFingerSwipeListener.onRightSwipe();
                        } else {
                            // 处理左滑操作
                            doubleFingerSwipeListener.onLeftSwipe();
                        }
                        isSwipeActionDone = true;
                    }
                    return true; // 处理双指滑动事件
                case MotionEvent.ACTION_POINTER_UP:
                    isTwoFingerSwipe = false;
                    isSwipeActionDone = false;
                    break;
            }
        } else {
            // 单指操作传递给子控件
            return super.onTouchEvent(event);
        }
        return true;
    }


    public void setDoubleFingerSwipeListener(DoubleFingerSwipeListener doubleFingerSwipeListener){
        this.doubleFingerSwipeListener = doubleFingerSwipeListener;
    }

    protected void pageReturn(){
        if (returnListener != null){
            returnListener.returnCallBack();
        }

    }

    public void setPageReturnListener(ReturnListener returnListener){
        this.returnListener = returnListener;
    }

    public void startAnimator(float startX, float endX, AnimatorListenerAdapter animatorListenerAdapter){
        animator = ObjectAnimator.ofFloat(this, "translationX", startX, endX);
        animator.setDuration(300); // 设置动画持续时间为1秒
        animator.setInterpolator(new AccelerateDecelerateInterpolator()); // 设置动画插值器
        animator.addListener(animatorListenerAdapter);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                disableTouch = false;
            }
        });
        disableTouch = true;
        animator.start();
    }

    public void endAnimator(){
        if (animator != null){
            animator.end();
        }
        animator = null;
    }

    public void setLastPage(SuperPageLayout lastPage){
        this.lastPage = lastPage;
    }

    public SuperPageLayout getLastPage() {
        return lastPage;
    }
}
