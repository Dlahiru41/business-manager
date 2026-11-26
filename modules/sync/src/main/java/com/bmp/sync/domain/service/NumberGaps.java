package com.bmp.sync.domain.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Rule SYN-06: a register numbers its bills 1, 2, 3, ...; a number below the highest one received that never arrived
 * is missing. Each missing number is reported once.
 */
public final class NumberGaps {

    private NumberGaps() {}

    /** The numbers missing below the highest received, not reported before, in order. */
    public static List<Long> newGaps(Collection<Long> received, Set<Long> reported) {
        TreeSet<Long> numbers = new TreeSet<>(received);
        List<Long> missing = new ArrayList<>();
        if (numbers.isEmpty()) {
            return missing;
        }
        for (long n = 1; n < numbers.last(); n++) {
            if (!numbers.contains(n) && !reported.contains(n)) {
                missing.add(n);
            }
        }
        return missing;
    }
}
