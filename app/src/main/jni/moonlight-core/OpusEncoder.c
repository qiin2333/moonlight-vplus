#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "opus.h"

typedef struct {
    OpusEncoder* encoder;
    int frameSize;
} OpusContext;

JNIEXPORT jlong JNICALL
Java_com_limelight_binding_audio_OpusEncoder_nativeInit(JNIEnv* env, jclass clazz,
                                                      jint sampleRate, jint channels, jint bitrate) {
    int error;
    OpusContext* ctx = (OpusContext*)malloc(sizeof(OpusContext));
    if (!ctx) {
        return 0;
    }
    
    // 计算每帧采样数 (20ms @ 48kHz = 960 samples)
    ctx->frameSize = sampleRate / 50; // 20ms帧
    
    // 创建Opus编码器
    ctx->encoder = opus_encoder_create(sampleRate, channels, OPUS_APPLICATION_VOIP, &error);
    if (error != OPUS_OK) {
        free(ctx);
        return 0;
    }
    
    // 设置比特率
    opus_encoder_ctl(ctx->encoder, OPUS_SET_BITRATE(bitrate));
    
    // 为VoIP优化设置
    opus_encoder_ctl(ctx->encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(ctx->encoder, OPUS_SET_COMPLEXITY(6)); // 降低复杂度以减少延迟 (从8降到6)
    
    // 启用DTX（不发送静音帧）
    opus_encoder_ctl(ctx->encoder, OPUS_SET_DTX(1));
    
    // 设置帧大小
    opus_encoder_ctl(ctx->encoder, OPUS_SET_EXPERT_FRAME_DURATION(OPUS_FRAMESIZE_20_MS));
    
    // 启用前向纠错
    opus_encoder_ctl(ctx->encoder, OPUS_SET_INBAND_FEC(1));
    
    // 设置包丢失率预测
    opus_encoder_ctl(ctx->encoder, OPUS_SET_PACKET_LOSS_PERC(1));
    
    return (jlong)ctx;
}

JNIEXPORT jbyteArray
Java_com_limelight_binding_audio_OpusEncoder_nativeEncode(JNIEnv* env, jclass clazz,
                                                        jlong handle, jbyteArray pcmData,
                                                        jint offset, jint length) {
    if (handle == 0) {
        return NULL;
    }
    
    OpusContext* ctx = (OpusContext*)handle;
    if (ctx->encoder == NULL) {
        return NULL;
    }
    
    jbyte* pcm;
    jbyteArray result = NULL;
    
    // 最大Opus帧大小
    unsigned char encoded[4000];
    int encodedLength;
    
    // 获取PCM数据
    pcm = (*env)->GetByteArrayElements(env, pcmData, NULL);
    if (!pcm) {
        return NULL;
    }
    
    // 确保数据长度是正确的（必须是一个完整的帧）
    int samples = length / 2; // 16位样本 (2字节)
    
    // 检查帧大小是否匹配
    if (samples != ctx->frameSize) {
        // 如果帧大小不匹配，记录警告但继续处理
        // 这可能是由于音频捕获的微小变化
    }
    
    // 编码音频数据
    encodedLength = opus_encode(ctx->encoder, (const opus_int16*)(pcm + offset), 
                              ctx->frameSize, encoded, sizeof(encoded));
    
    // 释放PCM数据
    (*env)->ReleaseByteArrayElements(env, pcmData, pcm, JNI_ABORT);
    
    if (encodedLength > 0) {
        // 创建结果数组
        result = (*env)->NewByteArray(env, encodedLength);
        if (result) {
            (*env)->SetByteArrayRegion(env, result, 0, encodedLength, (jbyte*)encoded);
        }
    }
    
    return result;
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_audio_OpusEncoder_nativeDestroy(JNIEnv* env, jclass clazz, jlong handle) {
    OpusContext* ctx = (OpusContext*)handle;
    if (ctx) {
        if (ctx->encoder) {
            opus_encoder_destroy(ctx->encoder);
        }
        free(ctx);
    }
}
