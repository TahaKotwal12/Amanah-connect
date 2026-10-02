-- Reference data and per-community defaults. No users are seeded: the SuperAdmin is created by the
-- bootstrap command, never by a migration.

-- Default plans. PRICES ARE PLACEHOLDERS pending client confirmation (blueprint section 0.4, item 4);
-- super admins can edit them through the plans API. A null limit means "unlimited".
INSERT INTO plans (code, name, price_monthly, price_yearly, limits, features, is_public, active, sort_order)
VALUES ('STARTER', 'Starter', 0.00, 0.00,
        '{"max_members": 100, "storage_mb": 500, "emails_per_month": 500}',
        '{"upi_qr": true, "receipts_email": true, "csv_export": true, "pdf_reports": false, "two_factor": true, "priority_support": false}',
        true, true, 10),
       ('GROWTH', 'Growth', 499.00, 4990.00,
        '{"max_members": 1000, "storage_mb": 5120, "emails_per_month": 5000}',
        '{"upi_qr": true, "receipts_email": true, "csv_export": true, "pdf_reports": true, "two_factor": true, "priority_support": false}',
        true, true, 20),
       ('ENTERPRISE', 'Enterprise', 1999.00, 19990.00,
        '{"max_members": null, "storage_mb": 51200, "emails_per_month": 50000}',
        '{"upi_qr": true, "receipts_email": true, "csv_export": true, "pdf_reports": true, "two_factor": true, "priority_support": true}',
        true, true, 30);

-- Generic default ledger categories. "Events" and "Other" exist for both directions.
-- "Membership Fees" is an addition to the requested list: payment-sourced ledger entries need an
-- income category, and Donations alone would misclassify dues.
INSERT INTO ledger_category_templates (name, type, sort_order)
VALUES ('Membership Fees', 'INCOME', 10),
       ('Donations', 'INCOME', 20),
       ('Events', 'INCOME', 30),
       ('Other', 'INCOME', 90),
       ('Maintenance', 'EXPENSE', 110),
       ('Utilities', 'EXPENSE', 120),
       ('Repairs', 'EXPENSE', 130),
       ('Events', 'EXPENSE', 140),
       ('Administration', 'EXPENSE', 150),
       ('Other', 'EXPENSE', 190);

-- Every new community, however it is created (onboarding, import, tests), gets its own copy of the
-- default categories and a default notification-settings row.
CREATE FUNCTION init_community_defaults() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    INSERT INTO ledger_categories (community_id, name, type)
    SELECT NEW.id, t.name, t.type
    FROM ledger_category_templates t
    ORDER BY t.sort_order;

    INSERT INTO notification_settings (community_id) VALUES (NEW.id);

    RETURN NEW;
END
$$;
CREATE TRIGGER trg_communities_defaults AFTER INSERT ON communities FOR EACH ROW EXECUTE FUNCTION init_community_defaults();
