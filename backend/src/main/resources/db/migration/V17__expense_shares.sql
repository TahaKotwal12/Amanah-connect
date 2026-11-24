-- Dividing an expense among members: each member's share is an ordinary invoice that points back at the
-- expense (ledger entry) it came from. No new money table: bills, payments, receipts and the ledger already
-- work for invoices, so a share is paid, receipted and reversed exactly like any other invoice.
ALTER TABLE invoices ADD COLUMN expense_entry_id uuid;

ALTER TABLE invoices
    ADD CONSTRAINT fk_invoices_expense_entry
        FOREIGN KEY (expense_entry_id, community_id) REFERENCES ledger_entries (id, community_id);

-- One live share per expense and member: dividing the same expense twice (a double click, two admins at once)
-- cannot bill anyone twice. A cancelled share does not count, so the expense can be divided again afterwards.
CREATE UNIQUE INDEX uq_invoices_expense_member
    ON invoices (community_id, expense_entry_id, member_id)
    WHERE expense_entry_id IS NOT NULL AND status <> 'CANCELLED';

CREATE INDEX idx_invoices_expense ON invoices (community_id, expense_entry_id) WHERE expense_entry_id IS NOT NULL;
