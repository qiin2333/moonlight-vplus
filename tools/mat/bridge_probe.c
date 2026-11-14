#include <jni.h>
#include "mat_carrier_bridge.h"

JNIEXPORT jboolean JNICALL Java_MatBridgeProbe_deliver(JNIEnv* env, jclass cls,
        jobject consumer, jbyteArray input, jboolean end) {
    (void)cls;
    unsigned char bytes[61440];
    if (!input || (*env)->GetArrayLength(env, input) != sizeof(bytes)) return JNI_FALSE;
    (*env)->GetByteArrayRegion(env, input, 0, sizeof(bytes), (jbyte*)bytes);
    if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
    void* bridge = MatPlaybackBridgeCreate(env, consumer);
    if (!bridge) return JNI_FALSE;
    int ok = MatPlaybackBridgeCycle(bridge, env, bytes, sizeof(bytes), 1, 1, 0, 0);
    if (ok && end) ok = MatPlaybackBridgeEnd(bridge, env, 1);
    MatPlaybackBridgeDestroy(bridge, env);
    return ok;
}
