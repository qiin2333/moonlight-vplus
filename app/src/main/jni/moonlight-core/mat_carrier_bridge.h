#pragma once
#include <jni.h>
#include <stdint.h>
void* MatPlaybackBridgeCreate(JNIEnv*,jobject);
int MatPlaybackBridgeCycle(void*,JNIEnv*,const unsigned char*,int,uint64_t,uint64_t,uint64_t,uint64_t);
int MatPlaybackBridgeEnd(void*,JNIEnv*,uint64_t);
void MatPlaybackBridgeDestroy(void*,JNIEnv*);
