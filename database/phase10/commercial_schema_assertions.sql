-- Phase 10 disposable PostgreSQL proof; run after the main Flyway baseline
-- and the pending commercial SQL (never run in production).
DO $$
DECLARE
  private_cols int;
  public_cost_cols int;
  missing_tables int;
  missing_views int;
BEGIN
  SELECT count(*) INTO private_cols FROM information_schema.columns
  WHERE table_schema='public' AND table_name='commercial_boq_cost_line'
    AND column_name='internal_unit_cost';
  IF private_cols<>1 THEN RAISE EXCEPTION 'Private cost column is absent'; END IF;

  SELECT count(*) INTO public_cost_cols FROM information_schema.columns
  WHERE table_schema='public' AND table_name IN
    ('commercial_quote_version','commercial_quote_line')
    AND (column_name ILIKE '%cost%' OR column_name ILIKE '%margin%'
      OR column_name ILIKE '%supplier%' OR column_name ILIKE '%vendor%');
  IF public_cost_cols<>0 THEN RAISE EXCEPTION 'Private cost leaked into quote tables'; END IF;

  SELECT count(*) INTO missing_tables FROM
    (VALUES('commercial_opportunity'),('commercial_tender_revision'),
      ('commercial_site_visit'),('commercial_scope_version'),
      ('commercial_boq_version'),('commercial_quote_version'),
      ('commercial_contract'),('commercial_client_po'),
      ('commercial_project_handoff'),('commercial_authority_binding')) AS required(name)
    WHERE to_regclass('public.'||required.name) IS NULL;
  IF missing_tables<>0 THEN RAISE EXCEPTION 'Required commercial tables are absent'; END IF;


  SELECT count(*) INTO missing_views FROM
    (VALUES('commercial_finance_award_source'),('commercial_project_scope_source'),
      ('commercial_project_site_source'),('commercial_support_contract_source'),
      ('commercial_released_client_quote')) AS required(name)
    WHERE to_regclass('public.'||required.name) IS NULL;
  IF missing_views<>0 THEN RAISE EXCEPTION 'Required cross-lane commercial views missing'; END IF;

  SELECT count(*) INTO public_cost_cols FROM information_schema.columns
  WHERE table_schema='public' AND table_name IN
    ('commercial_released_client_quote','commercial_finance_award_source',
     'commercial_project_scope_source','commercial_support_contract_source')
    AND (column_name ILIKE '%unit_cost%' OR column_name ILIKE '%margin%'
         OR column_name ILIKE '%supplier%' OR column_name ILIKE '%vendor%');
  IF public_cost_cols<>0 THEN
    RAISE EXCEPTION 'Restricted vendor/private costing column leaked into outbound source views';
  END IF;

  IF (SELECT count(*) FROM commercial_authority_binding)<>0
    THEN RAISE EXCEPTION 'No commercial permissions may be seeded'; END IF;
  RAISE NOTICE 'PHASE10_SCHEMA_ISOLATION_PASS';
END $$;
