package com.limelight.binding.input.driver;

public interface UsbDriverListener {
    void reportControllerState(int controllerId, int buttonFlags,
                               float leftStickX, float leftStickY,
                               float rightStickX, float rightStickY,
                               float leftTrigger, float rightTrigger);

    void deviceRemoved(AbstractController controller);
    void deviceAdded(AbstractController controller);

    // Report motion data sourced from the USB controller itself
    void reportControllerMotion(int controllerId, byte motionType, float x, float y, float z);
}
