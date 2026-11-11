-- برنامج محاسبة شركة الثقة: إعداد قاعدة البيانات (يُشغَّل مرة واحدة في Supabase > SQL Editor)
create table if not exists public.acc_docs (
  path text primary key,
  col text not null,
  data jsonb not null,
  updated_at timestamptz not null default now()
);
create index if not exists acc_docs_col on public.acc_docs (col);
alter table public.acc_docs enable row level security;

-- المالك الوحيد = أقدم مستخدم في المشروع (مستخدم الإدارة)
create or replace function public.acc_is_owner() returns boolean
language sql stable security definer set search_path = ''
as $$ select auth.uid() = (select id from auth.users order by created_at asc limit 1) $$;

revoke all on public.acc_docs from anon;
grant select, insert, update, delete on public.acc_docs to authenticated;

drop policy if exists acc_owner_only on public.acc_docs;
create policy acc_owner_only on public.acc_docs for all to authenticated
  using (public.acc_is_owner()) with check (public.acc_is_owner());
