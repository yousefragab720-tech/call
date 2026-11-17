package com.callguard.app

enum class JobType { WARN, BLOCK }

class GuardJob(
    val type: JobType,
    val number: String,
    val pkg: String,
    val text: String = ""
) {
    val created: Long = System.currentTimeMillis()
}

object JobQueue {
    private val pending = ArrayList<GuardJob>()
    private var active: GuardJob? = null

    @Synchronized
    fun add(job: GuardJob): Boolean {
        val a = active
        if (a != null && a.type == job.type && a.number == job.number) return false
        if (pending.any { it.type == job.type && it.number == job.number }) return false
        if (job.type == JobType.BLOCK) {
            pending.removeAll { it.type == JobType.WARN && it.number == job.number }
        }
        pending.add(job)
        return true
    }

    @Synchronized
    fun hasPending(): Boolean = pending.isNotEmpty()

    @Synchronized
    fun pendingCount(): Int = pending.size

    @Synchronized
    fun take(): GuardJob? {
        val now = System.currentTimeMillis()
        pending.removeAll { now - it.created > 12 * 3_600_000L }
        if (pending.isEmpty()) return null
        val j = pending.removeAt(0)
        active = j
        return j
    }

    @Synchronized
    fun finish() {
        active = null
    }
}
