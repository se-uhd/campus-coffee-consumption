SET TIME ZONE 'UTC';

ALTER TABLE expenses ADD COLUMN expense_type varchar(16) NOT NULL DEFAULT 'BEANS';
ALTER TABLE expenses ADD COLUMN bean_id uuid REFERENCES coffee_beans(id);
ALTER TABLE expenses ALTER COLUMN weight_grams DROP NOT NULL;

ALTER TABLE expenses ADD CONSTRAINT ck_expenses_beans_weight
    CHECK ((expense_type = 'BEANS' AND weight_grams IS NOT NULL)
        OR (expense_type = 'OTHER' AND weight_grams IS NULL));

CREATE INDEX idx_expenses_bean_id ON expenses (bean_id);
