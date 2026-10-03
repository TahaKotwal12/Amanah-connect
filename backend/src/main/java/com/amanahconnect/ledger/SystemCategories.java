package com.amanahconnect.ledger;

import com.amanahconnect.billing.FeeKind;
import java.util.Map;

/** The income categories the system posts to by itself, found by key so renaming one breaks nothing. */
public final class SystemCategories {

    public static final String MEMBERSHIP_FEES = "MEMBERSHIP_FEES";
    public static final String DONATIONS = "DONATIONS";
    public static final String EVENTS = "EVENTS";
    public static final String OTHER_INCOME = "OTHER_INCOME";

    /** The name a missing system category is created with. */
    static final Map<String, String> DEFAULT_NAMES =
            Map.of(MEMBERSHIP_FEES, "Membership Fees", DONATIONS, "Donations", EVENTS, "Events", OTHER_INCOME, "Other");

    private SystemCategories() {}

    /** Where a payment of an invoice of this kind is posted. */
    public static String forKind(FeeKind kind) {
        return switch (kind) {
            case MAINTENANCE, SUBSCRIPTION, MEMBERSHIP -> MEMBERSHIP_FEES;
            case DONATION -> DONATIONS;
            case EVENT -> EVENTS;
            case FINE, OTHER -> OTHER_INCOME;
        };
    }
}
