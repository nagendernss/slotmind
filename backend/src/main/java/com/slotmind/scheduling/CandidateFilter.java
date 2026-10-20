package com.slotmind.scheduling;

import com.slotmind.domain.Slot;

/** Hard constraint applied by ScheduleEngine before any strategy sees the slots. New constraint = new @Component. */
public interface CandidateFilter {
    String name();

    boolean test(Slot slot, FilterContext ctx);
}
