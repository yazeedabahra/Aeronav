package com.yvora.app

import org.junit.Test

class AdaptivePerformanceTest {
    @Test fun pacingAdaptsAndRecovers() {
        val pace = FramePacer(100L)
        check(pace.canStart(0))
        pace.started(0); pace.finished(40)
        check(!pace.canStart(99)); check(pace.canStart(100))
        val heavy = FramePacer(66L)
        heavy.started(0); heavy.finished(160)
        check(heavy.intervalMs == 216L)
        check(!heavy.canStart(215)); check(heavy.canStart(216))
        repeat(30) { heavy.finished(20) }
        check(heavy.intervalMs == 66L)
    }
    @Test fun pointerFiltersSlowAndFastMotion() {
        val filter = AdaptivePointerFilter()
        filter.update(0.3f, 0.4f, 0)
        check(filter.x == 0.3f && filter.y == 0.4f)
        filter.update(0.301f, 0.4f, 67)
        check(filter.x > 0.3f && filter.x < 0.301f)
        filter.update(0.8f, 0.4f, 134)
        check(filter.x > 0.65f && filter.x < 0.8f)
        filter.update(Float.NaN, 0.3f, 200)
        check(filter.x.isFinite())
        filter.reset(); filter.update(0.9f, 0.1f, 300)
        check(filter.x == 0.9f && filter.y == 0.1f)
        filter.update(0.2f, 0.2f, 1000)
        check(filter.x == 0.2f && filter.y == 0.2f)
    }
}
