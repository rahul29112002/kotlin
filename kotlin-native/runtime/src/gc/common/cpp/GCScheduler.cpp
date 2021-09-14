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
    onSafePoint_(allocatedBytes_, safePointsCounter_);
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

void gc::GCScheduler::GCData::OnSafePoint(size_t allocatedBytes, size_t safePointsCounter) noexcept {
    if (allocatedBytes > config_.allocationThresholdBytes || currentTimeCallbackNs_() - timeOfLastGcNs_ >= config_.cooldownThresholdNs) {
        RuntimeAssert(static_cast<bool>(scheduleGC_), "scheduleGC_ cannot be empty");
        scheduleGC_();
    }
}

void gc::GCScheduler::GCData::OnPerformFullGC() noexcept {
    timeOfLastGcNs_ = currentTimeCallbackNs_();
}

gc::GCScheduler::GCScheduler() noexcept : gcData_(config_, []() { return konan::getTimeNanos(); }) {}

gc::GCScheduler::ThreadData gc::GCScheduler::NewThreadData() noexcept {
    return ThreadData(config_, [this](size_t allocatedBytes, size_t safePointsCounter) {
        gcData().OnSafePoint(allocatedBytes, safePointsCounter);
    });
}

void gc::GCScheduler::SetScheduleGC(std::function<void()> scheduleGC) noexcept {
    RuntimeAssert(static_cast<bool>(scheduleGC), "scheduleGC cannot be empty");
    RuntimeAssert(!static_cast<bool>(scheduleGC_), "scheduleGC must not have been set");
    scheduleGC_ = std::move(scheduleGC);
    gcData_.SetScheduleGC(scheduleGC_);
}
