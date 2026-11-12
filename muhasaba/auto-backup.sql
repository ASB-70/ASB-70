-- نسخة احتياطية تلقائية أسبوعية (كل جمعة 3 فجراً بتوقيت دمشق) تحتفظ بآخر 8 نسخ
create extension if not exists pg_cron;

create table if not exists public.acc_backups (
  id bigint generated always as identity primary key,
  created_at timestamptz not null default now(),
  n int not null,
  bytes int not null,
  data jsonb not null
);
alter table public.acc_backups enable row level security;
revoke all on public.acc_backups from anon;
revoke insert, update, delete on public.acc_backups from authenticated;
grant select on public.acc_backups to authenticated;
drop policy if exists acc_backups_owner on public.acc_backups;
create policy acc_backups_owner on public.acc_backups for select to authenticated
  using (public.acc_is_owner());

create or replace function public.acc_make_backup() returns void
language plpgsql security definer set search_path = ''
as $fn$
declare j jsonb; cnt int;
begin
  select count(*) into cnt from public.acc_docs;
  select jsonb_build_object(
           'app', 'thiqa-accounting', 'v', 1, 'at', now(), 'auto', true,
           'data', coalesce(jsonb_object_agg(col, arr), '{}'::jsonb))
    into j
  from (
    select col,
           jsonb_agg(jsonb_build_object('id', substr(path, length(col) + 2), 'd', data) order by path) as arr
    from public.acc_docs
    group by col
  ) g;
  insert into public.acc_backups (n, bytes, data)
    values (cnt, octet_length(j::text), coalesce(j, '{}'::jsonb));
  delete from public.acc_backups
   where id not in (select id from public.acc_backups order by id desc limit 8);
end
$fn$;
revoke all on function public.acc_make_backup() from public, anon, authenticated;

do $$ begin perform cron.unschedule('acc-weekly-backup'); exception when others then null; end $$;
select cron.schedule('acc-weekly-backup', '0 0 * * 5', $$select public.acc_make_backup()$$);

-- أول نسخة الآن
select public.acc_make_backup();
