package com.amanahconnect.community.admin;

import com.amanahconnect.common.csv.CsvWriter;
import com.amanahconnect.community.admin.AdminCommunityDtos.CommunityExport;
import com.amanahconnect.community.admin.AdminCommunityDtos.CommunityView;
import com.amanahconnect.community.admin.AdminCommunityDtos.SubscriptionLine;

/**
 * The CSV form of a community export. A single CSV cannot hold nested data, so it is long-format
 * ({@code section,item,field,value}): one row per fact, which opens cleanly in a spreadsheet and filters
 * well. Cell values go through {@link CsvWriter}, which neutralises spreadsheet formulas.
 */
final class CommunityExportCsv {

    private CommunityExportCsv() {}

    static String toCsv(CommunityExport export) {
        CsvWriter csv = new CsvWriter().row("section", "item", "field", "value");
        CommunityView p = export.profile();
        csv.row("export", "", "exportedAt", export.exportedAt());
        csv.row("profile", "", "id", p.id()).row("profile", "", "name", p.name()).row("profile", "", "slug", p.slug())
                .row("profile", "", "status", p.status()).row("profile", "", "statusReason", p.statusReason())
                .row("profile", "", "contactName", p.contactName()).row("profile", "", "contactEmail", p.contactEmail())
                .row("profile", "", "contactPhone", p.contactPhone()).row("profile", "", "addressLine1", p.addressLine1())
                .row("profile", "", "addressLine2", p.addressLine2()).row("profile", "", "city", p.city())
                .row("profile", "", "state", p.state()).row("profile", "", "postalCode", p.postalCode())
                .row("profile", "", "country", p.country()).row("profile", "", "dateOfEstablishment", p.dateOfEstablishment())
                .row("profile", "", "plan", p.plan().code()).row("profile", "", "currency", p.currency())
                .row("profile", "", "financialYearStartMonth", p.financialYearStartMonth())
                .row("profile", "", "require2fa", p.require2fa()).row("profile", "", "createdAt", p.createdAt());
        if (p.owner() != null) {
            csv.row("owner", "", "name", p.owner().fullName()).row("owner", "", "email", p.owner().email())
                    .row("owner", "", "status", p.owner().status());
        }
        csv.row("members", "", "count", export.membersCount());
        int n = 0;
        for (SubscriptionLine line : export.subscriptionHistory()) {
            n++;
            String item = String.valueOf(n);
            csv.row("subscription", item, "plan", line.planCode()).row("subscription", item, "periodStart", line.periodStart())
                    .row("subscription", item, "periodEnd", line.periodEnd()).row("subscription", item, "amount", line.amount())
                    .row("subscription", item, "paidOn", line.paidOn()).row("subscription", item, "reference", line.reference())
                    .row("subscription", item, "status", line.status());
        }
        return csv.toString();
    }
}
