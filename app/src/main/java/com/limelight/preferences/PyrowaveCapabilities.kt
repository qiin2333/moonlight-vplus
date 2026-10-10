/* SPDX-License-Identifier: GPL-3.0-only */
package com.limelight.preferences

import android.os.Build
import android.os.Bundle
import com.limelight.framegen.FramegenInterceptor
import com.limelight.nvstream.jni.MoonBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

internal enum class PyrowaveProbeFailure {
    LOADER_UNAVAILABLE, NO_DEVICES, QUERY_FAILED
}

internal data class PyrowaveCapabilities(
    val runtimeAvailable: Boolean = false,
    val gpuFrontendAvailable: Boolean = false,
    val loaderVersion: Int = 0,
    val androidSurface: Boolean = false,
    val swapchainColorspace: Boolean = false,
    val vkResult: Int? = null,
    val devices: List<PyrowaveGpuCapabilities> = emptyList(),
    val failure: PyrowaveProbeFailure? = null
) {
    fun hasStagingPrerequisites(device: PyrowaveGpuCapabilities): Boolean =
        failure == null && runtimeAvailable && loaderVersion >= VULKAN_1_2 &&
            device.hasDecoderPrerequisites

    fun hasSurfacePrerequisites(device: PyrowaveGpuCapabilities): Boolean =
        hasStagingPrerequisites(device) && gpuFrontendAvailable && androidSurface &&
            loaderVersion >= VULKAN_1_3 && device.apiVersion >= VULKAN_1_3 && device.swapchain

    val hasAnyPrerequisites: Boolean
        get() = devices.any { hasStagingPrerequisites(it) || hasSurfacePrerequisites(it) }
}

internal data class PyrowaveGpuCapabilities(
    val name: String,
    val apiVersion: Int = 0,
    val subgroupOperations: Int = 0,
    val subgroupStages: Int = 0,
    val subgroupSize: Int = 0,
    val subgroupSizeControl: Boolean = false,
    val computeFullSubgroups: Boolean = false,
    val minSubgroupSize: Int = 0,
    val maxSubgroupSize: Int = 0,
    val requiredSubgroupSizeStages: Int = 0,
    val storageBuffer8BitAccess: Boolean = false,
    val maxTexelBufferElements: Long = 0,
    val graphicsComputeQueue: Boolean = false,
    val swapchain: Boolean = false,
    val hdrMetadata: Boolean = false,
    val maxComputeWorkGroupInvocations: Int = 0,
    val maxComputeSharedMemorySize: Int = 0
) {
    val hasSubgroupOperations: Boolean
        get() = subgroupOperations and REQUIRED_SUBGROUP_OPERATIONS == REQUIRED_SUBGROUP_OPERATIONS &&
            subgroupStages and COMPUTE_STAGE != 0

    // Match Granite's wave4-wave128 test, not merely extension presence.
    val hasSubgroupSizeRange: Boolean
        get() {
            if (!subgroupSizeControl || !computeFullSubgroups ||
                minSubgroupSize <= 0 || maxSubgroupSize < minSubgroupSize) return false
            if (minSubgroupSize >= 4 && maxSubgroupSize <= 128) return true
            if (maxSubgroupSize < 4 || minSubgroupSize > 128) return false
            return requiredSubgroupSizeStages and COMPUTE_STAGE != 0
        }

    val hasStoragePath: Boolean
        get() = storageBuffer8BitAccess || maxTexelBufferElements >= MIN_TEXEL_BUFFER_ELEMENTS

    val hasDecoderPrerequisites: Boolean
        get() = apiVersion >= VULKAN_1_2 && graphicsComputeQueue &&
            hasSubgroupOperations && hasSubgroupSizeRange && hasStoragePath
}

internal const val VULKAN_1_2 = (1 shl 22) or (2 shl 12)
internal const val VULKAN_1_3 = (1 shl 22) or (3 shl 12)
internal const val COMPUTE_STAGE = 0x20
internal const val REQUIRED_SUBGROUP_OPERATIONS = 0x3f
internal const val MIN_TEXEL_BUFFER_ELEMENTS = 16L * 1024 * 1024

internal fun vulkanVersionString(version: Int): String =
    "${(version ushr 22) and 0x7f}.${(version ushr 12) and 0x3ff}.${version and 0xfff}"

internal object PyrowaveCapabilityProbe {
    suspend fun collect(): PyrowaveCapabilities = withContext(Dispatchers.IO) {
        try {
            val raw = MoonBridge.pyrowaveGetCapabilities()
                ?: return@withContext PyrowaveCapabilities(failure = PyrowaveProbeFailure.QUERY_FAILED)
            read(raw)
        } catch (error: CancellationException) {
            throw error
        } catch (_: LinkageError) {
            PyrowaveCapabilities(failure = PyrowaveProbeFailure.QUERY_FAILED)
        } catch (_: RuntimeException) {
            PyrowaveCapabilities(failure = PyrowaveProbeFailure.QUERY_FAILED)
        }
    }

    @Suppress("DEPRECATION")
    private fun read(raw: Bundle): PyrowaveCapabilities {
        val devices = raw.getParcelableArray("devices").orEmpty().mapNotNull { item ->
            val device = item as? Bundle ?: return@mapNotNull null
            PyrowaveGpuCapabilities(
                name = device.getString("name").orEmpty(),
                apiVersion = device.getInt("apiVersion"),
                subgroupOperations = device.getInt("subgroupOperations"),
                subgroupStages = device.getInt("subgroupStages"),
                subgroupSize = device.getInt("subgroupSize"),
                subgroupSizeControl = device.getBoolean("subgroupSizeControl"),
                computeFullSubgroups = device.getBoolean("computeFullSubgroups"),
                minSubgroupSize = device.getInt("minSubgroupSize"),
                maxSubgroupSize = device.getInt("maxSubgroupSize"),
                requiredSubgroupSizeStages = device.getInt("requiredSubgroupSizeStages"),
                storageBuffer8BitAccess = device.getBoolean("storageBuffer8BitAccess"),
                maxTexelBufferElements = device.getInt("maxTexelBufferElements").toLong() and 0xffffffffL,
                graphicsComputeQueue = device.getBoolean("graphicsComputeQueue"),
                swapchain = device.getBoolean("swapchain"),
                hdrMetadata = device.getBoolean("hdrMetadata"),
                maxComputeWorkGroupInvocations = device.getInt("maxComputeWorkGroupInvocations"),
                maxComputeSharedMemorySize = device.getInt("maxComputeSharedMemorySize")
            )
        }
        return PyrowaveCapabilities(
            runtimeAvailable = raw.getBoolean("runtimeAvailable"),
            gpuFrontendAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                FramegenInterceptor.isAvailable(),
            loaderVersion = raw.getInt("loaderVersion"),
            androidSurface = raw.getBoolean("androidSurface"),
            swapchainColorspace = raw.getBoolean("swapchainColorspace"),
            vkResult = if (raw.containsKey("vkResult")) raw.getInt("vkResult") else null,
            devices = devices,
            failure = when (raw.getString("error")) {
                null -> null
                "loader_unavailable" -> PyrowaveProbeFailure.LOADER_UNAVAILABLE
                "no_devices" -> PyrowaveProbeFailure.NO_DEVICES
                else -> PyrowaveProbeFailure.QUERY_FAILED
            }
        )
    }
}
