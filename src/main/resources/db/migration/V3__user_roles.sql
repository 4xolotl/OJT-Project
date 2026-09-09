-- Existing and newly created accounts remain ordinary users unless explicitly assigned another role.
ALTER TABLE users
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER';
