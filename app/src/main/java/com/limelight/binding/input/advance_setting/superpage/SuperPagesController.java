package com.limelight.binding.input.advance_setting.superpage;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.limelight.R;

public class SuperPagesController {

    public enum BoxPosition{
        Right,
        Left
    }

    private BoxPosition boxPosition = BoxPosition.Right;
    private SuperPageLayout.DoubleFingerSwipeListener rightListener;
    private SuperPageLayout.DoubleFingerSwipeListener leftListener;
    private SuperPageLayout pageNull;
    private SuperPageLayout pageNow;

    private FrameLayout superPagesBox;
    private Context context;

    private SuperPageLayout openingPage;
    private SuperPageLayout closingPage;

    public SuperPagesController(FrameLayout superPagesBox, Context context) {
        this.superPagesBox = superPagesBox;
        this.context = context;
        rightListener = new SuperPageLayout.DoubleFingerSwipeListener() {
            @Override
            public void onRightSwipe() {
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
            }
        };
        pageNull = (SuperPageLayout) LayoutInflater.from(context).inflate(R.layout.page_null,null);
        pageNow = pageNull;
    }

    public SuperPageLayout getPageNull(){
        return pageNull;
    }

    public SuperPageLayout getPageNow(){return pageNow;}


    public void setPosition(BoxPosition position){

        if (openingPage != null){
            openingPage.endAnimator();
        }
        if (closingPage != null){
            closingPage.endAnimator();
        }

        float previousPosition = getVisiblePosition(pageNow);
        boxPosition = position;
        float nextPosition = getVisiblePosition(pageNow);
        openingPage = pageNow;
        pageNow.startAnimator(previousPosition,nextPosition,new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // 动画结束后将视图设置到最终位置
                pageNow.setX(nextPosition);
                SuperPageLayout.DoubleFingerSwipeListener doubleFingerSwipeListener = boxPosition == BoxPosition.Right ? rightListener : leftListener;
                pageNow.setDoubleFingerSwipeListener(doubleFingerSwipeListener);
                openingPage = null;
            }
        });
    }



    public void openNewPage(SuperPageLayout pageNew){
        if (pageNew == pageNow) return;

        if (closingPage != null){
            closingPage.endAnimator();
        }
        if (openingPage != null){
            openingPage.endAnimator();
        }

        closingPage = pageNow;
        float closingPagePreviousPosition = getVisiblePosition(closingPage);
        float closingPageNextPosition = getHidePosition(closingPage);
        closingPage.startAnimator(closingPagePreviousPosition, closingPageNextPosition,new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // 动画结束后将视图设置到最终位置
                closingPage.setX(closingPageNextPosition);
                superPagesBox.removeView(closingPage);

                closingPage = null;
            }
        });

        openingPage = pageNew;
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(dpToPx(Integer.parseInt(openingPage.getTag().toString())), ViewGroup.LayoutParams.MATCH_PARENT);
        layoutParams.topMargin = dpToPx(20);
        layoutParams.bottomMargin = dpToPx(20);
        superPagesBox.addView(openingPage,layoutParams);
        openingPage.setDoubleFingerSwipeListener(boxPosition == BoxPosition.Right ? rightListener : leftListener);
        float previousPosition = getHidePosition(openingPage);
        float nextPosition = getVisiblePosition(openingPage);
        openingPage.setX(previousPosition);

        openingPage.startAnimator(previousPosition,nextPosition,new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // 动画结束后将视图设置到最终位置
                openingPage.setX(nextPosition);
                openingPage = null;
            }
        });



        pageNew.setLastPage(pageNow);
        pageNow = pageNew;
    }

    public void returnOperation(){
        pageNow.pageReturn();
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
