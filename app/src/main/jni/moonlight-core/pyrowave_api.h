/*
 * Copyright (c) 2026 Hans-Kristian Arntzen
 * SPDX-License-Identifier: MIT
 *
 * Narrow ABI declarations copied from the public PyroWave C API. The renderer
 * loads the implementation dynamically and only uses the declarations below.
 */
#pragma once

#include <vulkan/vulkan.h>

#include <stddef.h>
#include <stdint.h>

typedef enum pyrowave_result {
    PYROWAVE_SUCCESS = 0,
    PYROWAVE_ERROR_GENERIC = -1,
} pyrowave_result;

/* The current frame was incomplete, but the decoder and presentation target
 * remain valid. The caller should keep the last presented frame. */
#define PYROWAVE_SUBMIT_FRAME_DROPPED 1

typedef enum pyrowave_chroma_subsampling {
    PYROWAVE_CHROMA_SUBSAMPLING_420 = 0,
} pyrowave_chroma_subsampling;

typedef enum pyrowave_cpu_buffer_format {
    PYROWAVE_CPU_BUFFER_FORMAT_YUV420P = 1,
} pyrowave_cpu_buffer_format;

typedef enum pyrowave_color_primaries {
    PYROWAVE_COLOR_PRIMARIES_BT709 = 0,
    PYROWAVE_COLOR_PRIMARIES_BT2020 = 1,
} pyrowave_color_primaries;

typedef enum pyrowave_transfer_function {
    PYROWAVE_TRANSFER_BT709 = 0,
    PYROWAVE_TRANSFER_PQ = 1,
    PYROWAVE_TRANSFER_HLG = 2,
} pyrowave_transfer_function;

typedef enum pyrowave_ycbcr_transform {
    PYROWAVE_YCBCR_BT709 = 0,
    PYROWAVE_YCBCR_BT2020 = 1,
} pyrowave_ycbcr_transform;

typedef enum pyrowave_ycbcr_range {
    PYROWAVE_YCBCR_FULL = 0,
    PYROWAVE_YCBCR_LIMITED = 1,
} pyrowave_ycbcr_range;

typedef struct pyrowave_color_metadata {
    pyrowave_color_primaries primaries;
    pyrowave_transfer_function transfer;
    pyrowave_ycbcr_transform transform;
    pyrowave_ycbcr_range range;
    uint32_t chroma_siting;
} pyrowave_color_metadata;

// The shared library ABI is checked before any other entry point is used.
// Keep these values pinned to the runtime revision built by the Android
// packaging script; a major-version change is not ABI compatible.
#define PYROWAVE_API_VERSION_MAJOR 0u
#define PYROWAVE_API_VERSION_MINOR 6u
#define PYROWAVE_API_VERSION_PATCH 1u

typedef struct pyrowave_device_opaque *pyrowave_device;
typedef struct pyrowave_decoder_opaque *pyrowave_decoder;
typedef struct pyrowave_image_opaque *pyrowave_image;

typedef struct pyrowave_device_create_queue_info {
    VkQueue queue;
    uint32_t familyIndex;
    uint32_t index;
} pyrowave_device_create_queue_info;

typedef void (*pyrowave_queue_lock_cb)(void *userdata);

typedef struct pyrowave_device_create_info {
    PFN_vkGetInstanceProcAddr GetInstanceProcAddr;
    VkInstance instance;
    VkPhysicalDevice physical_device;
    VkDevice device;
    const VkInstanceCreateInfo *instance_create_info;
    const VkDeviceCreateInfo *device_create_info;
    pyrowave_device_create_queue_info *queue_info;
    uint32_t queue_info_count;
    pyrowave_queue_lock_cb queue_lock_callback;
    pyrowave_queue_lock_cb queue_unlock_callback;
    void *userdata;
} pyrowave_device_create_info;

typedef struct pyrowave_decoder_create_info {
    pyrowave_device device;
    int width;
    int height;
    pyrowave_chroma_subsampling chroma;
    bool fragment_path;
    uint32_t output_bit_depth;
} pyrowave_decoder_create_info;

typedef struct pyrowave_image_view {
    VkImage image;
    uint32_t width;
    uint32_t height;
    VkFormat image_format;
    VkFormat view_format;
    uint32_t mip_level;
    uint32_t layer;
    VkImageAspectFlagBits aspect;
    VkComponentSwizzle swizzle;
    VkImageLayout layout;
} pyrowave_image_view;

typedef struct pyrowave_gpu_buffers {
    pyrowave_image_view planes[3];
} pyrowave_gpu_buffers;

typedef struct pyrowave_sync_point {
    VkSemaphore semaphore;
    uint64_t value;
} pyrowave_sync_point;

typedef struct pyrowave_gpu_external_reference {
    pyrowave_image image;
    uint32_t queue_family_index;
} pyrowave_gpu_external_reference;

typedef struct pyrowave_gpu_sync_operation {
    const pyrowave_gpu_external_reference *images;
    size_t num_images;
    pyrowave_sync_point sync;
} pyrowave_gpu_sync_operation;

typedef struct pyrowave_cpu_buffer {
    void *data[3];
    size_t row_stride_in_bytes[3];
    size_t plane_size_in_bytes[3];
    int width;
    int height;
    pyrowave_cpu_buffer_format format;
} pyrowave_cpu_buffer;

typedef pyrowave_result (*pyrowave_create_device_fn)(const pyrowave_device_create_info *, pyrowave_device *);
typedef pyrowave_result (*pyrowave_create_default_device_fn)(pyrowave_device *);
typedef void (*pyrowave_destroy_device_fn)(pyrowave_device);
typedef pyrowave_result (*pyrowave_set_queue_type_fn)(pyrowave_device, VkQueueFlagBits);
typedef pyrowave_result (*pyrowave_create_decoder_fn)(const pyrowave_decoder_create_info *, pyrowave_decoder *);
typedef void (*pyrowave_destroy_decoder_fn)(pyrowave_decoder);
typedef pyrowave_result (*pyrowave_push_packet_fn)(pyrowave_decoder, const void *, size_t);
typedef bool (*pyrowave_decode_ready_fn)(pyrowave_decoder, bool);
typedef pyrowave_result (*pyrowave_decode_gpu_fn)(pyrowave_decoder,
                                                   const pyrowave_gpu_sync_operation *,
                                                   const pyrowave_gpu_sync_operation *,
                                                   const pyrowave_gpu_buffers *);
typedef bool (*pyrowave_get_color_metadata_fn)(pyrowave_decoder, pyrowave_color_metadata *);
typedef void (*pyrowave_set_command_buffer_fn)(pyrowave_device, VkCommandBuffer);
