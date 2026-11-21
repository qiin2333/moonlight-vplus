#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "moonlight-common-c/src/MatReceiver.h"
#include "mat_carrier_bridge.h"

/* Audio decoder thread owns the bridge until common-c joins its workers.
 * Only complete authenticated cycles reach here; Java copies before returning. */
typedef struct {
    jobject consumer;
    jbyteArray cycle;
    jmethodID offer, end;
} MAT_JNI_BRIDGE;

void MatPlaybackBridgeDestroy(void* context, JNIEnv* env) {
    MAT_JNI_BRIDGE* s = context;
    if (!s) return;
    if (s->consumer) (*env)->DeleteGlobalRef(env, s->consumer);
    if (s->cycle) (*env)->DeleteGlobalRef(env, s->cycle);
    free(s);
}

void* MatPlaybackBridgeCreate(JNIEnv* env, jobject consumer) {
    if (!consumer) return NULL;
    MAT_JNI_BRIDGE* s = calloc(1, sizeof(*s));
    if (!s) return NULL;
    jclass type = (*env)->GetObjectClass(env, consumer);
    if (!type) goto fail;
    s->offer = (*env)->GetMethodID(env, type, "offerMatCycle", "([BIJJJJ)Z");
    if (!(*env)->ExceptionCheck(env))
        s->end = (*env)->GetMethodID(env, type, "endMatStream", "(J)Z");
    (*env)->DeleteLocalRef(env, type);
    if ((*env)->ExceptionCheck(env)) goto fail;
    s->consumer = (*env)->NewGlobalRef(env, consumer);
    if (!s->consumer) goto fail;
    jbyteArray bytes = (*env)->NewByteArray(env, 61440);
    if (bytes) {
        s->cycle = (*env)->NewGlobalRef(env, bytes);
        (*env)->DeleteLocalRef(env, bytes);
    }
    if (!s->cycle || (*env)->ExceptionCheck(env)) goto fail;
    return s;
fail:
    MatPlaybackBridgeDestroy(s, env);
    return NULL;
}

int MatPlaybackBridgeCycle(void* context, JNIEnv* env, const unsigned char* bytes,
        int length, uint64_t epoch, uint64_t domain, uint64_t index, uint64_t pts) {
    MAT_JNI_BRIDGE* s = context;
    if (!s || !env || !MatCarrierIsValid(bytes, (size_t)length) ||
        !epoch || !domain || epoch > INT64_MAX || domain > INT64_MAX ||
        index > INT64_MAX || pts > INT64_MAX) return 0;
    (*env)->SetByteArrayRegion(env, s->cycle, 0, length, (const jbyte*)bytes);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return 0; }
    jboolean ok = (*env)->CallBooleanMethod(env, s->consumer, s->offer, s->cycle,
        length, (jlong)epoch, (jlong)domain, (jlong)index, (jlong)pts);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return 0; }
    return ok;
}

int MatPlaybackBridgeEnd(void* context, JNIEnv* env, uint64_t cycles) {
    MAT_JNI_BRIDGE* s = context;
    if (!s || !env || !cycles || cycles > INT64_MAX) return 0;
    jboolean ok = (*env)->CallBooleanMethod(env, s->consumer, s->end, (jlong)cycles);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return 0; }
    return ok;
}
