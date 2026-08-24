package com.limelight.binding.input.driver

import java.util.concurrent.atomic.AtomicInteger

/** Allocates process-wide IDs shared by every application-managed controller transport. */
internal object ControllerDriverIdAllocator {
    private val nextId = AtomicInteger()

    fun allocate(): Int = nextId.getAndIncrement()
}
