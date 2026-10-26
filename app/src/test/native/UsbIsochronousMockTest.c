// Execute the real native sender with fake JNI buffers and USBFS syscalls, without Android/device access.
#define USB_ISO_MOCK_TEST
#include "../../main/jni/moonlight-core/usb_isochronous.c"
#include <assert.h>
#include <stdio.h>
#include <string.h>

#define CALL(name) JNI_NAME(name)
typedef struct { unsigned char data[384]; int references; } BUFFER;
static struct {
    struct usbdevfs_urb* pending[16];
    struct usbdevfs_urb* completed[32];
    int count, queued, closed, refs, submits, discards, reaps, reap_error, poll_interrupt;
} kernel;

static void* buffer_address(JNIEnv* env, jobject object) { (void)env; return ((BUFFER*)object)->data; }
static jlong buffer_capacity(JNIEnv* env, jobject object) { (void)env; (void)object; return 384; }
static jobject global_ref(JNIEnv* env, jobject object) {
    (void)env; ((BUFFER*)object)->references++; kernel.refs++; return object;
}
static void clear_exception(JNIEnv* env) { (void)env; }
static int same_object(JNIEnv* env, jobject first, jobject second) { (void)env; return first == second; }
static void delete_ref(JNIEnv* env, jobject object) {
    (void)env; assert(((BUFFER*)object)->references == 1);
    ((BUFFER*)object)->references--; kernel.refs--;
}
static const struct MockJniTable table = {
    buffer_address, buffer_capacity, global_ref, clear_exception, same_object, delete_ref
};
static JNIEnv env = &table;

int mock_mutex_init(pthread_mutex_t* mutex, const void* attributes) { (void)attributes; mutex->locked = 0; return 0; }
int mock_mutex_lock(pthread_mutex_t* mutex) { assert(mutex->locked++ == 0); return 0; }
int mock_mutex_unlock(pthread_mutex_t* mutex) { assert(--mutex->locked == 0); return 0; }
int mock_mutex_destroy(pthread_mutex_t* mutex) { assert(mutex->locked == 0); return 0; }
int mock_dup(int fd) { assert(fd == 7); return 42; }
int mock_close(int fd) { assert(fd == 42 && kernel.count == 0); kernel.closed++; return 0; }
int mock_poll(struct pollfd* descriptor, unsigned long count, int timeout) {
    assert(count == 1 && descriptor->fd == 42 && descriptor->events == POLLOUT && timeout <= 1000);
    if (kernel.poll_interrupt) { kernel.poll_interrupt = 0; errno = EINTR; return -1; }
    return kernel.queued ? 1 : 0;
}

static void enqueue(struct usbdevfs_urb* urb) {
    for (int i = 0; i < kernel.queued; i++) if (kernel.completed[i] == urb) return;
    assert(kernel.queued < 32);
    kernel.completed[kernel.queued++] = urb;
}

int mock_ioctl(int fd, int operation, void* argument) {
    assert(fd == 42 && kernel.closed == 0);
    struct usbdevfs_urb* urb = argument;
    if (operation == USBDEVFS_SUBMITURB) {
        // These assertions would reject the previous bulk/interrupt workaround.
        assert(urb->type == USBDEVFS_URB_TYPE_ISO && urb->endpoint == 1 &&
            urb->flags == USBDEVFS_URB_ISO_ASAP && urb->number_of_packets == 1 &&
            urb->buffer_length == 384 && urb->iso_frame_desc[0].length == 384);
        for (int i = 0; i < kernel.count; i++) assert(kernel.pending[i] != urb);
        assert(kernel.refs > 0 && urb->buffer != NULL);
        kernel.pending[kernel.count++] = urb;
        kernel.submits++;
        return 0;
    }
    if (operation == USBDEVFS_DISCARDURB) {
        urb->status = -ENOENT;
        enqueue(urb);
        kernel.discards++;
        return 0;
    }
    assert(operation == USBDEVFS_REAPURB || operation == USBDEVFS_REAPURBNDELAY);
    if (kernel.reap_error) { errno = kernel.reap_error; kernel.reap_error = 0; return -1; }
    if (!kernel.queued) { errno = EAGAIN; return -1; }
    urb = kernel.completed[0];
    memmove(kernel.completed, kernel.completed + 1, --kernel.queued * sizeof(urb));
    int found = 0;
    for (int i = 0; i < kernel.count; i++) {
        if (kernel.pending[i] != urb) continue;
        kernel.pending[i] = kernel.pending[--kernel.count]; found = 1; break;
    }
    assert(found && kernel.refs > 0); // JNI memory must remain pinned through cancellation/reaping.
    *(struct usbdevfs_urb**)argument = urb;
    kernel.reaps++;
    return 0;
}

static void complete(int slot, int urb_status, int packet_status, unsigned int actual) {
    struct usbdevfs_urb* urb = kernel.pending[slot];
    urb->status = urb_status;
    urb->iso_frame_desc[0].status = packet_status;
    urb->iso_frame_desc[0].actual_length = actual;
    enqueue(urb);
}

static jlong open_output(void) {
    memset(&kernel, 0, sizeof(kernel));
    jlong handle = CALL(createUsbIsochronousOutput)(&env, NULL, 7, 1, 4, 384);
    assert(handle != 0);
    return handle;
}

static void test_roundtrip_and_cancel(void) {
    jlong handle = open_output();
    BUFFER buffers[4] = {0};
    for (int i = 0; i < 4; i++) {
        memset(buffers[i].data, 0x20 + i, 384);
        assert(CALL(submitUsbIsochronousOutput)(&env, NULL, handle, i, &buffers[i]) == 0);
        assert(memcmp(kernel.pending[i]->buffer, buffers[i].data, 384) == 0);
    }
    assert(CALL(submitUsbIsochronousOutput)(&env, NULL, handle, 0, &buffers[0]) == -EBUSY);
    assert(CALL(reapUsbIsochronousOutput)(&env, NULL, handle, 5) == -ETIMEDOUT);
    complete(0, 0, 0, 384);
    kernel.poll_interrupt = 1;
    assert(CALL(reapUsbIsochronousOutput)(&env, NULL, handle, 5) == 0);
    BUFFER different = {0};
    assert(CALL(submitUsbIsochronousOutput)(&env, NULL, handle, 0, &different) == -EINVAL);
    assert(CALL(submitUsbIsochronousOutput)(&env, NULL, handle, 0, &buffers[0]) == 0);
    CALL(cancelUsbIsochronousOutput)(&env, NULL, handle);
    assert(CALL(submitUsbIsochronousOutput)(&env, NULL, handle, 0, &buffers[0]) == -EBUSY);
    assert(kernel.refs == 4 && kernel.closed == 0);
    assert(CALL(closeUsbIsochronousOutput)(&env, NULL, handle) == 0);
    assert(kernel.refs == 0 && kernel.closed == 1 && kernel.submits == 5 && kernel.reaps == 5);
    for (int i = 0; i < 4; i++) assert(buffers[i].references == 0);
}

static void test_errors_and_uncertain_retirement(void) {
    jlong handle = open_output();
    BUFFER buffer = {0};
    assert(CALL(submitUsbIsochronousOutput)(&env, NULL, handle, 0, &buffer) == 0);
    complete(0, 0, -EIO, 0);
    assert(CALL(reapUsbIsochronousOutput)(&env, NULL, handle, 5) == -EIO);
    assert(CALL(submitUsbIsochronousOutput)(&env, NULL, handle, 0, &buffer) == 0);
    complete(0, 0, 0, 383);
    assert(CALL(reapUsbIsochronousOutput)(&env, NULL, handle, 5) == -EIO);
    assert(CALL(submitUsbIsochronousOutput)(&env, NULL, handle, 0, &buffer) == 0);
    kernel.reap_error = EIO;
    assert(CALL(closeUsbIsochronousOutput)(&env, NULL, handle) == -EIO);
    assert(buffer.references == 1 && kernel.closed == 0); // No free/close before ownership is proven.
    assert(CALL(closeUsbIsochronousOutput)(&env, NULL, handle) == 0);
    assert(buffer.references == 0 && kernel.closed == 1);
}

static void test_invalid_parameters(void) {
    assert(CALL(createUsbIsochronousOutput)(&env, NULL, -1, 1, 4, 384) == 0);
    assert(CALL(createUsbIsochronousOutput)(&env, NULL, 7, 0, 4, 384) == 0);
    assert(CALL(createUsbIsochronousOutput)(&env, NULL, 7, 1, 17, 384) == 0);
    assert(CALL(createUsbIsochronousOutput)(&env, NULL, 7, 1, 4, 1025) == 0);
}

int main(void) {
    test_invalid_parameters();
    test_roundtrip_and_cancel();
    test_errors_and_uncertain_retirement();
    puts("USB ISO native mock: submit/reap, data ownership, cancel, errors and retirement passed");
    return 0;
}
