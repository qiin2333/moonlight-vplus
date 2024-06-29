package com.limelight.binding.input.advance_setting;

public class Debug {


    public static void _DBG(String value) {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        StackTraceElement caller = stackTrace[2]; // 获取调用_DBG的方法信息

        String className = caller.getClassName();
        String methodName = caller.getMethodName();

        System.out.println("_DBG--" + className + "--" + methodName + ":" + value);
    }
}
