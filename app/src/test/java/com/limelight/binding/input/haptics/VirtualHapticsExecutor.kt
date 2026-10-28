package com.limelight.binding.input.haptics

import java.util.PriorityQueue
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RunnableScheduledFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** A serial hardware worker driven by the same test clock as packet arrival and source expiry. */
internal class VirtualHapticsExecutor(private val clockNanos: () -> Long) :
    AbstractExecutorService(), ScheduledExecutorService {
    private val queue = PriorityQueue<Task<*>>()
    private var sequence = 0L
    private var closed = false
    var blocked = false
    var maxPending = 0
        private set

    private inner class Task<V>(val atNanos: Long, val order: Long, callable: Callable<V>) :
        FutureTask<V>(callable), RunnableScheduledFuture<V> {
        override fun getDelay(unit: TimeUnit): Long = unit.convert(atNanos - clockNanos(), TimeUnit.NANOSECONDS)
        override fun isPeriodic() = false
        override fun compareTo(other: Delayed): Int {
            other as VirtualHapticsExecutor.Task<*>
            val due = atNanos.compareTo(other.atNanos)
            return if (due == 0) order.compareTo(other.order) else due
        }
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean =
            super.cancel(mayInterruptIfRunning).also { if (it) queue.remove(this) }
    }

    fun runReady() {
        if (blocked) return
        var iterations = 0
        while (queue.peek()?.atNanos?.let { it <= clockNanos() } == true) {
            check(++iterations < 10_000) { "A virtual worker must not spin or replay an unbounded backlog" }
            val task = queue.remove()
            task.run()
            if (!task.isCancelled) task.get() // Propagate mock failures to the test thread.
        }
    }
    override fun execute(command: Runnable) { schedule(command, 0, TimeUnit.NANOSECONDS) }
    override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
        schedule(Executors.callable(command, Unit), delay, unit)
    override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> {
        if (closed) throw RejectedExecutionException("Virtual worker is closed")
        val task = Task(clockNanos() + unit.toNanos(delay), ++sequence, callable)
        queue.add(task)
        maxPending = maxOf(maxPending, queue.size)
        return task
    }
    override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long,
                                     unit: TimeUnit): ScheduledFuture<*> =
        throw UnsupportedOperationException("Haptics schedules individual source-owned commands")
    override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long,
                                        unit: TimeUnit): ScheduledFuture<*> =
        throw UnsupportedOperationException("Haptics schedules individual source-owned commands")
    override fun shutdown() { closed = true }
    override fun shutdownNow(): MutableList<Runnable> {
        closed = true
        val remaining = queue.map { it as Runnable }.toMutableList()
        queue.clear()
        return remaining
    }
    override fun isShutdown() = closed
    override fun isTerminated() = closed && queue.isEmpty()
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
}
