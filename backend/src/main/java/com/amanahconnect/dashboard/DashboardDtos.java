package com.amanahconnect.dashboard;

import com.amanahconnect.common.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class DashboardDtos {

    private DashboardDtos() {}

    public record Members(long total, long active) {}

    /**
     * {@code billed} is what was invoiced this month (invoices issued in the month, not cancelled), {@code collected} what has been paid against those
     * invoices and {@code outstanding} the difference (the same figures as the collection report). {@code receivedInMonth} is the cash that came in during
     * the month, whatever it was for, net of reversals.
     */
    public record Collection(Money billed, Money collected, Money outstanding, Money receivedInMonth) {}

    public record Overdue(long count, Money amount) {}

    public record Complaints(long open, long slaBreached) {}

    public record DueItem(UUID invoiceId, String invoiceNo, UUID memberId, String memberName, LocalDate dueDate, Money balance, String status) {}

    /** What falls due in the next {@code days} days (unpaid, not yet overdue): the total and the soonest ten. */
    public record UpcomingDues(int days, long count, Money amount, List<DueItem> items) {}

    public record Activity(Instant at, String summary, String actor, boolean byPlatformStaff, String action) {}

    public record TrendPoint(String month, Money billed, Money collected) {}

    public record Dashboard(
            Instant generatedAt,
            String currency,
            String month,
            Members members,
            Collection collection,
            Overdue overdue,
            Money netBalance,
            Complaints complaints,
            long unreadSupportMessages,
            UpcomingDues upcomingDues,
            List<Activity> recentActivity,
            List<TrendPoint> collectionTrend) {}
}
