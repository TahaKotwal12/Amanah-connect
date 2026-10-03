package com.amanahconnect.member;

import com.amanahconnect.billing.CounterType;
import com.amanahconnect.billing.NumberingService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands out member numbers such as {@code GARDEN-0042}: the community's slug as an upper-case prefix, then a counter
 * that never restarts. Numbers already taken (typed in by hand during a migration) are skipped. Runs in the caller's
 * transaction, so a failed create gives its number back.
 */
@Service
public class MemberNumbers {

    static final String SCOPE = "ALL";
    private static final int PREFIX_MAX = 8;

    private final NumberingService numbering;
    private final MemberRepository members;

    public MemberNumbers(NumberingService numbering, MemberRepository members) {
        this.numbering = numbering;
        this.members = members;
    }

    static String prefixOf(String slug) {
        String prefix = slug == null ? "" : slug.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (prefix.length() > PREFIX_MAX) {
            prefix = prefix.substring(0, PREFIX_MAX);
        }
        return prefix.isEmpty() ? "MEM" : prefix;
    }

    static String format(String prefix, long value) {
        return "%s-%04d".formatted(prefix, value);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(UUID communityId, String slug) {
        return allocate(communityId, slug, 1).get(0);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<String> allocate(UUID communityId, String slug, int count) {
        String prefix = prefixOf(slug);
        long next = numbering.reserve(communityId, CounterType.MEMBER, SCOPE, count);
        List<String> result = new ArrayList<>(count);
        long end = next + count;
        while (result.size() < count) {
            if (next >= end) {
                end = numbering.reserve(communityId, CounterType.MEMBER, SCOPE, 1) + 1; // a taken number used up a slot
                next = end - 1;
            }
            String candidate = format(prefix, next++);
            if (!members.existsByCommunityIdAndMemberNo(communityId, candidate)) {
                result.add(candidate);
            }
        }
        return result;
    }
}
