package com.saltbridge.core

/** 单调毫秒时钟。生产实现见 [SystemClock]，测试用可控实现。 */
fun interface Clock {
    fun nowMs(): Long
}

/** 生产时钟：`nanoTime` 不受系统时间调整影响。 */
object SystemClock : Clock {
    private val originNs = System.nanoTime()

    override fun nowMs(): Long = (System.nanoTime() - originNs) / 1_000_000
}

/** 可取消的定时任务句柄。 */
interface ScheduledHandle {
    fun cancel()
}

/** 单线程语义的调度器抽象；核心只按绝对时刻安排，不阻塞。 */
interface Scheduler {
    fun scheduleAt(atMs: Long, action: () -> Unit): ScheduledHandle
}

/** 什么都不做的调度器，用于不需要定时器的测试。 */
object NoopScheduler : Scheduler {
    override fun scheduleAt(atMs: Long, action: () -> Unit): ScheduledHandle =
        object : ScheduledHandle {
            override fun cancel() = Unit
        }
}
