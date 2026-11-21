package com.limelight

/** Deterministic [DynamicResolutionManager.Scheduler]: tasks run only when the test advances time. */
internal class FakeScheduler : DynamicResolutionManager.Scheduler {
    private class Task(val runnable: Runnable, val at: Long)

    private val tasks = mutableListOf<Task>()
    private var now = 0L

    override fun post(task: Runnable) = postDelayed(task, 0)

    override fun postDelayed(task: Runnable, delayMs: Long) {
        tasks += Task(task, now + delayMs)
    }

    override fun cancel(task: Runnable) {
        tasks.removeAll { it.runnable === task }
    }

    override fun cancelAll() {
        tasks.clear()
    }

    /** Runs due tasks in time order; ties run in posting order, like a Handler. */
    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = tasks.filter { it.at <= end }.minByOrNull { it.at } ?: break
            tasks.remove(next)
            now = next.at
            next.runnable.run()
        }
        now = end
    }
}
