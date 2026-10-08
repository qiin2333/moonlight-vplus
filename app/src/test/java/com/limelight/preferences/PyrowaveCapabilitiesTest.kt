package com.limelight.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PyrowaveCapabilitiesTest {
    private val supported = PyrowaveGpuCapabilities(
        name = "test-gpu",
        apiVersion = VULKAN_1_3,
        subgroupOperations = REQUIRED_SUBGROUP_OPERATIONS,
        subgroupStages = COMPUTE_STAGE,
        subgroupSize = 32,
        subgroupSizeControl = true,
        computeFullSubgroups = true,
        minSubgroupSize = 4,
        maxSubgroupSize = 64,
        storageBuffer8BitAccess = true,
        graphicsComputeQueue = true,
        swapchain = true
    )

    private fun report(vararg devices: PyrowaveGpuCapabilities) = PyrowaveCapabilities(
        runtimeAvailable = true,
        gpuFrontendAvailable = true,
        loaderVersion = VULKAN_1_3,
        androidSurface = true,
        devices = devices.toList()
    )

    @Test
    fun driverPatchVersionIsPreserved() {
        assertEquals("1.0.66", vulkanVersionString((1 shl 22) or 66))
        assertEquals("1.3.280", vulkanVersionString(VULKAN_1_3 or 280))
    }

    @Test
    fun versionAloneNeverEstablishesDecoderSupport() {
        assertFalse(report(PyrowaveGpuCapabilities("version-only", VULKAN_1_3)).hasAnyPrerequisites)
    }

    @Test
    fun legacyComputeGpuDoesNotPassModernDecoderRequirements() {
        val legacy = PyrowaveGpuCapabilities(
            name = "legacy-gpu",
            apiVersion = (1 shl 22) or 66,
            graphicsComputeQueue = true,
            maxTexelBufferElements = 65536,
            maxComputeWorkGroupInvocations = 384,
            maxComputeSharedMemorySize = 32768
        )
        assertFalse(report(legacy).hasAnyPrerequisites)
        assertFalse(legacy.hasStoragePath)
        assertFalse(legacy.hasSubgroupOperations)
    }

    @Test
    fun completeRequirementsPassBothPaths() {
        val report = report(supported)
        assertTrue(report.hasAnyPrerequisites)
        assertTrue(report.hasSurfacePrerequisites(supported))
        assertTrue(report.hasStagingPrerequisites(supported))
    }

    @Test
    fun everyRequiredSubgroupOperationIsChecked() {
        listOf(1, 2, 4, 8, 16, 32).forEach { bit ->
            assertFalse(supported.copy(subgroupOperations = REQUIRED_SUBGROUP_OPERATIONS xor bit).hasDecoderPrerequisites)
        }
        assertFalse(supported.copy(subgroupStages = 0x10).hasSubgroupOperations)
    }

    @Test
    fun sizeControlAndFullSubgroupsAreBothRequired() {
        assertFalse(supported.copy(subgroupSizeControl = false).hasSubgroupSizeRange)
        assertFalse(supported.copy(computeFullSubgroups = false).hasSubgroupSizeRange)
    }

    @Test
    fun fullyContainedSubgroupRangeDoesNotRequireFixedSizeStage() {
        assertTrue(supported.copy(requiredSubgroupSizeStages = 0).hasSubgroupSizeRange)
    }

    @Test
    fun partialRangeOverlapRequiresComputeSizeControlStage() {
        val partial = supported.copy(minSubgroupSize = 2, maxSubgroupSize = 64)
        assertFalse(partial.hasSubgroupSizeRange)
        assertTrue(partial.copy(requiredSubgroupSizeStages = COMPUTE_STAGE).hasSubgroupSizeRange)
        assertFalse(partial.copy(requiredSubgroupSizeStages = 0x10).hasSubgroupSizeRange)
    }

    @Test
    fun disjointOrInvalidSubgroupRangesFail() {
        assertFalse(supported.copy(minSubgroupSize = 256, maxSubgroupSize = 256,
            requiredSubgroupSizeStages = COMPUTE_STAGE).hasSubgroupSizeRange)
        assertFalse(supported.copy(minSubgroupSize = 1, maxSubgroupSize = 2).hasSubgroupSizeRange)
        assertFalse(supported.copy(minSubgroupSize = 0, maxSubgroupSize = 0).hasSubgroupSizeRange)
        assertFalse(supported.copy(minSubgroupSize = 32, maxSubgroupSize = 16).hasSubgroupSizeRange)
    }

    @Test
    fun texelBufferAlternativeUsesTheActualUpstreamLimit() {
        val fallback = supported.copy(storageBuffer8BitAccess = false,
            maxTexelBufferElements = MIN_TEXEL_BUFFER_ELEMENTS)
        assertTrue(fallback.hasDecoderPrerequisites)
        assertFalse(fallback.copy(maxTexelBufferElements = MIN_TEXEL_BUFFER_ELEMENTS - 1).hasStoragePath)
    }

    @Test
    fun missingGpuFrontendDoesNotDisableTheSeparateSdrPath() {
        val report = report(supported).copy(gpuFrontendAvailable = false)
        assertTrue(report.hasStagingPrerequisites(supported))
        assertFalse(report.hasSurfacePrerequisites(supported))
    }

    @Test
    fun vulkan12AllowsOnlySdrStagingPrerequisites() {
        val device = supported.copy(apiVersion = VULKAN_1_2)
        val report = report(device).copy(loaderVersion = VULKAN_1_2)
        assertTrue(report.hasStagingPrerequisites(device))
        assertFalse(report.hasSurfacePrerequisites(device))
    }

    @Test
    fun loaderVersionAndRuntimeAreIndependentRequirements() {
        assertFalse(report(supported).copy(loaderVersion = 1 shl 22).hasAnyPrerequisites)
        assertFalse(report(supported).copy(runtimeAvailable = false).hasAnyPrerequisites)
    }

    @Test
    fun surfaceExtensionsDoNotAffectTheSdrStagingPath() {
        val noSwapchain = supported.copy(swapchain = false)
        assertTrue(report(noSwapchain).hasStagingPrerequisites(noSwapchain))
        assertFalse(report(noSwapchain).hasSurfacePrerequisites(noSwapchain))
        assertFalse(report(supported).copy(androidSurface = false).hasSurfacePrerequisites(supported))
    }

    @Test
    fun featuresFromDifferentGpusCannotBeCombined() {
        val missingSubgroup = supported.copy(subgroupOperations = 0)
        val missingStorage = supported.copy(storageBuffer8BitAccess = false)
        assertFalse(report(missingSubgroup, missingStorage).hasAnyPrerequisites)
        assertTrue(report(missingSubgroup, supported).hasAnyPrerequisites)
    }

    @Test
    fun failedOrEmptyProbeNeverReportsReady() {
        assertFalse(report().hasAnyPrerequisites)
        assertFalse(report(supported).copy(failure = PyrowaveProbeFailure.QUERY_FAILED).hasAnyPrerequisites)
    }
}
