/*
 * Copyright 2010-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include "GCScheduler.hpp"

#include "CompilerConstants.hpp"
#include "KAssert.h"
#include "Porting.h"

using namespace kotlin;

void gc::GCScheduler::ThreadData::OnSafePointSlowPath() noexcept {
    onSafePoint_(*this);
    ClearCountersAndUpdateThresholds();
}

void gc::GCScheduler::ThreadData::ClearCountersAndUpdateThresholds() noexcept {
    allocatedBytes_ = 0;
    safePointsCounter_ = 0;

    allocatedBytesThreshold_ = config_.allocationThresholdBytes;
    safePointsCounterThreshold_ = config_.threshold;
}

gc::GCSchedulerConfig::GCSchedulerConfig() noexcept {
    if (compiler::gcAggressive()) {
        // TODO: Make it even more aggressive and run on a subset of backend.native tests.
        threshold = 1000;
        allocationThresholdBytes = 10000;
        cooldownThresholdNs = 0;
    }
}

gc::GCScheduler::GCData::GCData(gc::GCSchedulerConfig& config, CurrentTimeCallback currentTimeCallbackNs) noexcept :
    config_(config), currentTimeCallbackNs_(std::move(currentTimeCallbackNs)), timeOfLastGcNs_(currentTimeCallbackNs_()) {}

void gc::GCScheduler::GCData::OnSafePoint(ThreadData& threadData) noexcept {
    size_t allocatedBytes = threadData.allocatedBytes();
    if (allocatedBytes > config_.allocationThresholdBytes || currentTimeCallbackNs_() - timeOfLastGcNs_ >= config_.cooldownThresholdNs) {
        RuntimeAssert(static_cast<bool>(scheduleGC_), "scheduleGC_ cannot be empty");
        scheduleGC_();
    }
}

void gc::GCScheduler::GCData::SetScheduleGC(std::function<void()> scheduleGC) noexcept {
    RuntimeAssert(static_cast<bool>(scheduleGC), "scheduleGC cannot be empty");
    RuntimeAssert(!static_cast<bool>(scheduleGC_), "scheduleGC must not have been set");
    scheduleGC_ = std::move(scheduleGC);
    timerThread_ = std::thread([this]() {TimerThreadRoutine();});
}

void RUNTIME_NORETURN gc::GCScheduler::GCData::TimerThreadRoutine() noexcept {
    while (true) {
        std::this_thread::sleep_for(std::chrono::microseconds(config_.regularGcIntervalMs));
        OnTimer();
    }
}

void gc::GCScheduler::GCData::OnTimer() noexcept {
    // TODO: Probably makes sense to check memory usage of the process.
    scheduleGC_();
}

void gc::GCScheduler::GCData::OnPerformFullGC() noexcept {
    timeOfLastGcNs_ = currentTimeCallbackNs_();
}

gc::GCScheduler::GCScheduler() noexcept : gcData_(config_, []() { return konan::getTimeNanos(); }) {}

gc::GCScheduler::ThreadData gc::GCScheduler::NewThreadData() noexcept {
    return ThreadData(config_, [this](ThreadData& threadData) {
        gcData().OnSafePoint(threadData);
    });
}
