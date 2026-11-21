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

-- كل تعديل يختم وقت التعديل، حتى يسأل البرنامج «شو تغيّر؟» بطلب صغير بدل تنزيل كل البيانات كل 12 ثانية
create or replace function public.acc_touch() returns trigger language plpgsql set search_path = '' as $$ begin new.updated_at := now(); return new; end $$;
drop trigger if exists acc_touch on public.acc_docs;
create trigger acc_touch before update on public.acc_docs for each row execute function public.acc_touch();
create index if not exists acc_docs_upd on public.acc_docs (updated_at);

-- ملخص آخر النهار: كل يوم 9:41 مساءً بتوقيت دمشق (18:41 UTC) على تطبيق ntfy بالجوال، فقط بالأيام اللي فيها حركة
-- (الدالة public.acc_evening_summary موجودة بقاعدة البيانات؛ للتجربة: select public.acc_evening_summary(true);)

-- أسعار موقع الكاتالوج من المحاسبة (الموقع بيقرأها، والمالك بس بيكتبها). فاضية = الموقع بيعرض أسعار لوحة الإدارة
create table if not exists public.pub_prices (code text primary key, price numeric not null check (price >= 0), updated_at timestamptz not null default now());
alter table public.pub_prices enable row level security;
grant select on public.pub_prices to anon, authenticated;
grant insert, update, delete on public.pub_prices to authenticated;
drop policy if exists pub_prices_read on public.pub_prices;
create policy pub_prices_read on public.pub_prices for select to anon, authenticated using (true);
drop policy if exists pub_prices_owner_write on public.pub_prices;
create policy pub_prices_owner_write on public.pub_prices for all to authenticated using (public.acc_is_owner()) with check (public.acc_is_owner());
