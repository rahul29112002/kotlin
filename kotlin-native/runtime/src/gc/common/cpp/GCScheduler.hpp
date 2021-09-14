/*
 * Copyright 2010-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#ifndef RUNTIME_GC_COMMON_GC_SCHEDULER_H
#define RUNTIME_GC_COMMON_GC_SCHEDULER_H

#include <atomic>
#include <cinttypes>
#include <cstddef>
#include <functional>

#include "CompilerConstants.hpp"
#include "Utils.hpp"

namespace kotlin {
namespace gc {

struct GCSchedulerConfig {
    std::atomic<size_t> threshold = 100000; // Roughly 1 safepoint per 10ms (on a subset of examples on one particular machine).
    std::atomic<size_t> allocationThresholdBytes = 10 * 1024 * 1024; // 10MiB by default.
    std::atomic<uint64_t> cooldownThresholdNs = 200 * 1000 * 1000; // 200 milliseconds by default.
    std::atomic<bool> autoTune = false;

    GCSchedulerConfig() noexcept;
};

// TODO: Consider calling GC from the scheduler itself.
class GCScheduler : private Pinned {
public:
    class ThreadData {
    public:
        using OnSafePointCallback = std::function<void(size_t, size_t)>;

        static constexpr size_t kFunctionEpilogueWeight = 1;
        static constexpr size_t kLoopBodyWeight = 1;
        static constexpr size_t kExceptionUnwindWeight = 1;

        explicit ThreadData(GCSchedulerConfig& config, OnSafePointCallback onSafePoint) noexcept :
            config_(config), onSafePoint_(std::move(onSafePoint)) {
            ClearCountersAndUpdateThresholds();
        }

        // Should be called on encountering a safepoint.
        void OnSafePointRegular(size_t weight) noexcept {
            // TODO: Counting safepoints is also needed for targets without threads.
            if (compiler::gcAggressive()) {
                safePointsCounter_ += weight;
                if (safePointsCounter_ < safePointsCounterThreshold_) {
                    return;
                }
                OnSafePointSlowPath();
            }
        }

        // Should be called on encountering a safepoint placed by the allocator.
        // TODO: Should this even be a safepoint (i.e. a place, where we suspend)?
        void OnSafePointAllocation(size_t size) noexcept {
            allocatedBytes_ += size;
            if (allocatedBytes_ < allocatedBytesThreshold_) {
                return;
            }
            OnSafePointSlowPath();
        }

        void OnStoppedForGC() noexcept { ClearCountersAndUpdateThresholds(); }

    private:
        void OnSafePointSlowPath() noexcept;
        void ClearCountersAndUpdateThresholds() noexcept;

        GCSchedulerConfig& config_;
        OnSafePointCallback onSafePoint_;

        size_t allocatedBytes_ = 0;
        size_t allocatedBytesThreshold_ = 0;
        size_t safePointsCounter_ = 0;
        size_t safePointsCounterThreshold_ = 0;
    };

    class GCData {
    public:
        using CurrentTimeCallback = std::function<uint64_t()>;

        GCData(GCSchedulerConfig& config, CurrentTimeCallback currentTimeCallbackNs) noexcept;

        // May be called by different threads via `ThreadData`.
        void OnSafePoint(size_t allocatedBytes, size_t safePointsCounter) noexcept;

        // Always called by the GC thread.
        void OnPerformFullGC() noexcept;

        void SetScheduleGC(std::function<void()> scheduleGC) noexcept {
            scheduleGC_ = std::move(scheduleGC);
        }

    private:
        GCSchedulerConfig& config_;
        CurrentTimeCallback currentTimeCallbackNs_;

        std::atomic<uint64_t> timeOfLastGcNs_;
        std::function<void()> scheduleGC_;
    };

    GCScheduler() noexcept;

    GCSchedulerConfig& config() noexcept { return config_; }
    GCData& gcData() noexcept { return gcData_; }
    ThreadData NewThreadData() noexcept;

    // Can only be called once.
    void SetScheduleGC(std::function<void()> scheduleGC) noexcept;

private:
    GCSchedulerConfig config_;
    GCData gcData_;
    std::function<void()> scheduleGC_;
};

} // namespace gc
} // namespace kotlin

#endif // RUNTIME_GC_COMMON_GC_SCHEDULER_H
