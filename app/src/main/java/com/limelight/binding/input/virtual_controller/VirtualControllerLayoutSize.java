package com.limelight.binding.input.virtual_controller;

final class VirtualControllerLayoutSize {
    final int width;
    final int height;

    private VirtualControllerLayoutSize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    static VirtualControllerLayoutSize resolve(int containerWidth, int containerHeight,
                                               int fallbackWidth, int fallbackHeight) {
        if (containerWidth > 0 && containerHeight > 0) {
            return new VirtualControllerLayoutSize(containerWidth, containerHeight);
        }

        return new VirtualControllerLayoutSize(
                Math.max(1, fallbackWidth),
                Math.max(1, fallbackHeight));
    }
}
