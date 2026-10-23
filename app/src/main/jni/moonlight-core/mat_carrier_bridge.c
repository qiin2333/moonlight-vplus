#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "moonlight-common-c/src/MatReceiver.h"
#include "mat_carrier_bridge.h"

/* Owned by MatNativeReceiver. All entry points are serialized by its monitor.
 * Separate 61,440-byte global array; never use the AC3 16KB sample callback. */
typedef struct {
    MAT_RECEIVER receiver;
    JNIEnv* env; /* Valid only during the current synchronous push. */
    jobject consumer;
    jbyteArray cycle;
    jmethodID offer, end;
    uint8_t input[61440];
} MAT_JNI_RECEIVER;
static int submit(void* context, const MAT_CYCLE_INFO* info, const uint8_t* bytes, size_t size) {
    MAT_JNI_RECEIVER* s = context;
    JNIEnv* env = s->env;
    (*env)->SetByteArrayRegion(env, s->cycle, 0, (jsize)size, (const jbyte*)bytes);
    if ((*env)->ExceptionCheck(env)) return 0;
    jboolean ok = (*env)->CallBooleanMethod(env, s->consumer, s->offer, s->cycle, (jint)size,
        (jlong)info->epoch, (jlong)info->domain, (jlong)info->index, (jlong)info->pts48k);
    return !(*env)->ExceptionCheck(env) && ok;
}
static int end_stream(void* context, uint64_t cycles) {
    MAT_JNI_RECEIVER* s = context;
    jboolean ok = (*s->env)->CallBooleanMethod(s->env, s->consumer, s->end, (jlong)cycles);
    return !(*s->env)->ExceptionCheck(s->env) && ok;
}
JNIEXPORT jlong JNICALL Java_com_limelight_binding_audio_MatNativeReceiver_nativeOpen
    (JNIEnv* env, jclass clazz, jobject consumer) {
    (void)clazz;
    if (!consumer) return 0;
    MAT_JNI_RECEIVER* s = calloc(1, sizeof(*s));
    if (!s) return 0;
    jclass type = (*env)->GetObjectClass(env, consumer);
    if (!type) { free(s); return 0; }
    s->offer = (*env)->GetMethodID(env, type, "offerMatCycle", "([BIJJJJ)Z");
    if (!(*env)->ExceptionCheck(env)) s->end = (*env)->GetMethodID(env, type, "endMatStream", "(J)Z");
    (*env)->DeleteLocalRef(env, type);
    if ((*env)->ExceptionCheck(env)) { free(s); return 0; }
    s->consumer = (*env)->NewGlobalRef(env, consumer);
    if (!s->consumer) { free(s); return 0; }
    jbyteArray array = (*env)->NewByteArray(env, 61440);
    if (array) { s->cycle = (*env)->NewGlobalRef(env, array); (*env)->DeleteLocalRef(env, array); }
    if (!s->consumer || !s->cycle || (*env)->ExceptionCheck(env)) {
        if (s->consumer) (*env)->DeleteGlobalRef(env, s->consumer);
        if (s->cycle) (*env)->DeleteGlobalRef(env, s->cycle);
        free(s); return 0;
    }
    MatReceiverInit(&s->receiver, submit, end_stream, s);
    return (jlong)(intptr_t)s;
}
static int copy_input(JNIEnv* env, MAT_JNI_RECEIVER* s, jbyteArray bytes, jint length, int exact) {
    if (!s || !bytes || length != exact || (*env)->GetArrayLength(env, bytes) < length) {
        if (s) MatReceiverAbort(&s->receiver);
        return 0;
    }
    (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte*)s->input);
    if ((*env)->ExceptionCheck(env)) { MatReceiverAbort(&s->receiver); return 0; }
    s->env = env;
    return 1;
}
JNIEXPORT jboolean JNICALL Java_com_limelight_binding_audio_MatNativeReceiver_nativePushPacket
    (JNIEnv* env, jclass clazz, jlong handle, jbyteArray bytes, jint length) {
    (void)clazz;
    MAT_JNI_RECEIVER* s = (MAT_JNI_RECEIVER*)(intptr_t)handle;
    if (!copy_input(env, s, bytes, length, 1104)) return JNI_FALSE;
    int result = MatReceiverPush(&s->receiver, s->input, (size_t)length);
    s->env = NULL;
    return result >= 0;
}
JNIEXPORT jboolean JNICALL Java_com_limelight_binding_audio_MatNativeReceiver_nativePushCycle
    (JNIEnv* env, jclass clazz, jlong handle, jbyteArray bytes, jint length,
     jlong epoch, jlong domain, jlong index, jlong pts48k) {
    (void)clazz;
    MAT_JNI_RECEIVER* s = (MAT_JNI_RECEIVER*)(intptr_t)handle;
    if (!copy_input(env, s, bytes, length, 61440)) return JNI_FALSE;
    MAT_CYCLE_INFO info = {(uint64_t)epoch, (uint64_t)domain, (uint64_t)index, (uint64_t)pts48k};
    int result = (epoch <= 0 || domain <= 0 || index < 0 || pts48k < 0) ? -1 :
        MatReceiverPushRecordingCycle(&s->receiver, &info, s->input, (size_t)length);
    if (result < 0) MatReceiverAbort(&s->receiver);
    s->env = NULL;
    return result >= 0;
}
JNIEXPORT jboolean JNICALL Java_com_limelight_binding_audio_MatNativeReceiver_nativeEnd
    (JNIEnv* env, jclass clazz, jlong handle, jlong cycles) {
    (void)clazz;
    MAT_JNI_RECEIVER* s = (MAT_JNI_RECEIVER*)(intptr_t)handle;
    if (!s) return JNI_FALSE;
    s->env = env;
    int result = cycles > 0 ? MatReceiverEndRecording(&s->receiver, (uint64_t)cycles) : -1;
    if (result < 0) MatReceiverAbort(&s->receiver);
    s->env = NULL;
    return result == 2;
}
JNIEXPORT void JNICALL Java_com_limelight_binding_audio_MatNativeReceiver_nativeClose
    (JNIEnv* env, jclass clazz, jlong handle) {
    (void)clazz;
    MAT_JNI_RECEIVER* s = (MAT_JNI_RECEIVER*)(intptr_t)handle;
    if (!s) return;
    if (!MatReceiverFinish(&s->receiver)) MatReceiverAbort(&s->receiver);
    (*env)->DeleteGlobalRef(env, s->consumer);
    (*env)->DeleteGlobalRef(env, s->cycle);
    free(s);
}

/* Network receiver owns reassembly. Reuse only the bit-exact JNI delivery,
 * not another SMAT receiver. Decoder-thread env is supplied by the caller. */
void* MatPlaybackBridgeCreate(JNIEnv* env,jobject consumer) {
    return (void*)(intptr_t)Java_com_limelight_binding_audio_MatNativeReceiver_nativeOpen(env,NULL,consumer);
}
int MatPlaybackBridgeCycle(void* context,JNIEnv* env,const unsigned char* bytes,int length,
        uint64_t epoch,uint64_t domain,uint64_t index,uint64_t pts) {
    MAT_JNI_RECEIVER* s=context;
    if(!s || !env || !MatCarrierIsValid(bytes,(size_t)length) || epoch>INT64_MAX || domain>INT64_MAX ||
        index>INT64_MAX || pts>INT64_MAX)return 0;
    MAT_CYCLE_INFO info={epoch,domain,index,pts};s->env=env;
    int ok=submit(s,&info,bytes,(size_t)length);s->env=NULL;
    if((*env)->ExceptionCheck(env)){(*env)->ExceptionClear(env);return 0;}
    return ok;
}
int MatPlaybackBridgeEnd(void* context,JNIEnv* env,uint64_t cycles) {
    MAT_JNI_RECEIVER* s=context;if(!s || !env || cycles>INT64_MAX)return 0;
    s->env=env;int ok=end_stream(s,cycles);s->env=NULL;
    if((*env)->ExceptionCheck(env)){(*env)->ExceptionClear(env);return 0;}
    return ok;
}
void MatPlaybackBridgeDestroy(void* context,JNIEnv* env) {
    Java_com_limelight_binding_audio_MatNativeReceiver_nativeClose(env,NULL,(jlong)(intptr_t)context);
}
