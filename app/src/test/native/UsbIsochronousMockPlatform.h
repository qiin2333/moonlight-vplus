#pragma once

// Host-only syscall/JNI boundary doubles. Production builds use the NDK/Linux ABI headers.
#include <stdint.h>
#include <stddef.h>

typedef int32_t jint;
typedef int64_t jlong;
typedef void* jobject;
typedef void* jclass;
typedef const struct MockJniTable* JNIEnv;
struct MockJniTable {
    void* (*GetDirectBufferAddress)(JNIEnv*, jobject);
    jlong (*GetDirectBufferCapacity)(JNIEnv*, jobject);
    jobject (*NewGlobalRef)(JNIEnv*, jobject);
    void (*ExceptionClear)(JNIEnv*);
    int (*IsSameObject)(JNIEnv*, jobject, jobject);
    void (*DeleteGlobalRef)(JNIEnv*, jobject);
};
#define JNIEXPORT
#define JNICALL

struct usbdevfs_iso_packet_desc { unsigned int length, actual_length, status; };
struct usbdevfs_urb {
    unsigned char type, endpoint;
    int status;
    unsigned int flags;
    void* buffer;
    int buffer_length, actual_length, start_frame, number_of_packets, error_count;
    unsigned int signr;
    void* usercontext;
    struct usbdevfs_iso_packet_desc iso_frame_desc[];
};
#define USBDEVFS_URB_TYPE_ISO 0
#define USBDEVFS_URB_ISO_ASAP 2
enum { USBDEVFS_SUBMITURB = 1, USBDEVFS_DISCARDURB, USBDEVFS_REAPURBNDELAY, USBDEVFS_REAPURB };
struct pollfd { int fd; short events, revents; };
#define POLLOUT 4
typedef struct { int locked; } pthread_mutex_t;

int mock_mutex_init(pthread_mutex_t*, const void*);
int mock_mutex_lock(pthread_mutex_t*);
int mock_mutex_unlock(pthread_mutex_t*);
int mock_mutex_destroy(pthread_mutex_t*);
int mock_dup(int);
int mock_close(int);
int mock_poll(struct pollfd*, unsigned long, int);
int mock_ioctl(int, int, void*);

#define pthread_mutex_init mock_mutex_init
#define pthread_mutex_lock mock_mutex_lock
#define pthread_mutex_unlock mock_mutex_unlock
#define pthread_mutex_destroy mock_mutex_destroy
#define dup mock_dup
#define close mock_close
#define poll mock_poll
#define ioctl mock_ioctl
