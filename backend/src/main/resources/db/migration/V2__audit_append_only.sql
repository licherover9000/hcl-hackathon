-- The audit trail is append-only: the database itself refuses UPDATE and DELETE,
-- so even a bug in application code cannot rewrite history.
CREATE OR REPLACE FUNCTION audit_log_block_changes() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_no_update
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_block_changes();

CREATE TRIGGER audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_block_changes();
