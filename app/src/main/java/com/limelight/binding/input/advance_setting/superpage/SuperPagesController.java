package com.limelight.binding.input.advance_setting.superpage;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

public class SuperPagesController {

    public enum BoxPosition{
        Right,
        Left
    }

    private List<SuperPageLayout> pages = new ArrayList<>();
    private BoxPosition boxPosition = BoxPosition.Right;
    private SuperPageLayout.DoubleFingerSwipeListener rightListener;
    private SuperPageLayout.DoubleFingerSwipeListener leftListener;

    private FrameLayout superPagesBox;
    private Context context;

    private SuperPageLayout playingAnimatorPage1;
    private SuperPageLayout playingAnimatorPage2;

    public SuperPagesController(FrameLayout superPagesBox, Context context) {
        this.superPagesBox = superPagesBox;
        this.context = context;
        rightListener = new SuperPageLayout.DoubleFingerSwipeListener() {
            @Override
            public void onRightSwipe() {
                close();
            }

            @Override
            public void onLeftSwipe() {
                setPosition(BoxPosition.Left);
            }
        };
        leftListener = new SuperPageLayout.DoubleFingerSwipeListener() {
            @Override
            public void onRightSwipe() {
                setPosition(BoxPosition.Right);
            }

            @Override
            public void onLeftSwipe() {
                close();
            }
        };

    }

    public boolean setPosition(BoxPosition position){

        if (!pages.isEmpty()){
            if (playingAnimatorPage1 != null){
                playingAnimatorPage1.endAnimator();
            }
            if (playingAnimatorPage2 != null){
                playingAnimatorPage2.endAnimator();
            }

            SuperPageLayout page = pages.get(pages.size() - 1);
            float previousPosition = getVisiblePosition(page);
            boxPosition = position;
            float nextPosition = getVisiblePosition(page);
            playingAnimatorPage1 = page;
            page.startAnimator(previousPosition,nextPosition,new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    // 动画结束后将视图设置到最终位置
                    page.setX(nextPosition);
                    SuperPageLayout.DoubleFingerSwipeListener doubleFingerSwipeListener = boxPosition == BoxPosition.Right ? rightListener : leftListener;
                    for (SuperPageLayout page : pages){
                        page.setDoubleFingerSwipeListener(doubleFingerSwipeListener);
                    }
                    playingAnimatorPage1 = null;
                }
            });
        } else {
            boxPosition = position;
        }
        return true;
    }

    public SuperPageLayout getLastPage(){
        return pages.isEmpty() ? null : pages.get(pages.size() - 1);
    }


    public boolean open(SuperPageLayout page){
        if (playingAnimatorPage1 != null){
            playingAnimatorPage1.endAnimator();
        }
        if (playingAnimatorPage2 != null){
            playingAnimatorPage2.endAnimator();
        }
        pages.add(page);
        if (pages.size() - 2 >= 0){
            SuperPageLayout pagePrevious = pages.get(pages.size() - 2);
            float pagePreviousPreviousPosition = getVisiblePosition(pagePrevious);
            float pagePreviousNextPosition = getHidePosition(pagePrevious);
            playingAnimatorPage2 = pagePrevious;
            pagePrevious.startAnimator(pagePreviousPreviousPosition,pagePreviousNextPosition,new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    // 动画结束后将视图设置到最终位置
                    pagePrevious.setX(pagePreviousNextPosition);
                    playingAnimatorPage2 = null;
                }
            });
        }
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(dpToPx(Integer.parseInt(page.getTag().toString())), ViewGroup.LayoutParams.MATCH_PARENT);
        layoutParams.topMargin = dpToPx(20);
        layoutParams.bottomMargin = dpToPx(20);
        superPagesBox.addView(page,layoutParams);
        page.setDoubleFingerSwipeListener(boxPosition == BoxPosition.Right ? rightListener : leftListener);
        float previousPosition = getHidePosition(page);
        float nextPosition = getVisiblePosition(page);
        page.setX(previousPosition);
        playingAnimatorPage1 = page;
        page.startAnimator(previousPosition,nextPosition,new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // 动画结束后将视图设置到最终位置
                page.setX(nextPosition);
                playingAnimatorPage1 = null;
            }
        });
        return true;
    }

    public boolean close(){
        if (playingAnimatorPage1 != null){
            playingAnimatorPage1.endAnimator();
        }
        if (playingAnimatorPage2 != null){
            playingAnimatorPage2.endAnimator();
        }
        SuperPageLayout page = pages.get(pages.size() - 1);
        pages.remove(page);
        if (pages.size() - 1 >= 0){
            SuperPageLayout pagePrevious = pages.get(pages.size() - 1);
            float pagePreviousPreviousPosition = getHidePosition(pagePrevious);
            float pagePreviousNextPosition = getVisiblePosition(pagePrevious);
            playingAnimatorPage2 = pagePrevious;
            pagePrevious.startAnimator(pagePreviousPreviousPosition, pagePreviousNextPosition,new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    // 动画结束后将视图设置到最终位置
                    pagePrevious.setX(pagePreviousNextPosition);
                    playingAnimatorPage2 = null;
                }
            });
        }

        float previousPosition = getVisiblePosition(page);
        float nextPosition = getHidePosition(page);
        playingAnimatorPage1 = page;
        page.startAnimator(previousPosition, nextPosition,new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // 动画结束后将视图设置到最终位置
                page.setX(nextPosition);
                superPagesBox.removeView(page);
                page.close();
                playingAnimatorPage1 = null;
            }
        });
        return true;
    }



    private int getHidePosition(SuperPageLayout page){
        if (boxPosition == BoxPosition.Right){
            return superPagesBox.getWidth();
        } else {
            return - dpToPx(Integer.parseInt(page.getTag().toString()));
        }
    }

    private int getVisiblePosition(SuperPageLayout page){
        if (boxPosition == BoxPosition.Right){
            return superPagesBox.getWidth() - dpToPx(20) - dpToPx(Integer.parseInt(page.getTag().toString()));
        } else {
            return dpToPx(20);
        }
    }

    private int dpToPx(int dp){
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }
}
