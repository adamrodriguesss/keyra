-- On a shared Cloud SQL instance, other users must not be able to connect to, drop or alter Keyra's database.
do $$
begin
    if (select pg_get_userbyid(datdba) from pg_database where datname = current_database()) <> current_user then
        execute format('alter database %I owner to %I', current_database(), current_user);
    end if;
    if (select pg_get_userbyid(nspowner) from pg_namespace where nspname = current_schema()) <> current_user then
        execute format('alter schema %I owner to %I', current_schema(), current_user);
    end if;
    execute format('revoke all on database %I from public', current_database());
    execute format('revoke all on schema %I from public', current_schema());
exception
    when insufficient_privilege then
        raise notice 'Keyra could not take ownership of its database/schema or revoke PUBLIC access: %', sqlerrm;
end
$$;
