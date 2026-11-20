-- برنامج محاسبة شركة الثقة: صلاحيات الموظفين (يُشغَّل مرة واحدة في Supabase > SQL Editor)
-- صاحب الشركة (أقدم مستخدم) يبقى له كل شيء كما هو: هذه القواعد تضيف صلاحيات محدودة للموظف فقط.
-- الموظف يُضاف من البرنامج: الإعدادات ← المستخدمين والصلاحيات (يُكتب سجل roles/<الإيميل>).
-- صلاحية «بيّاع»: يقرأ ويكتب الفواتير والذمم والزبائن والصندوق والمخزون، ولا يقرأ الموظفين
-- ولا المصاريف ولا الموردين ولا الحسابات ولا الصلاحيات، ولا يحذف أي شيء.

create or replace function public.acc_staff_role() returns text
language sql stable security definer set search_path = ''
as $$ select d.data->>'role' from public.acc_docs d
      where d.path = 'roles/' || lower(coalesce(auth.jwt()->>'email','')) limit 1 $$;

create or replace function public.acc_staff_can_read(p text, c text) returns boolean
language sql stable security definer set search_path = ''
as $$ select public.acc_staff_role() = 'sales'
      and (c not in ('staff','fin','exp','macc','suppliers','sledgers','purch','roles','conflicts')
           or p = 'roles/' || lower(coalesce(auth.jwt()->>'email',''))) $$;

create or replace function public.acc_staff_can_write(p text, c text) returns boolean
language sql stable security definer set search_path = ''
as $$ select public.acc_staff_role() = 'sales'
      and (c in ('invoices','ledgers','customers','cash','audit')
           or p in ('meta/stock','meta/orders','meta/products','meta/stocklog')) $$;

drop policy if exists acc_staff_select on public.acc_docs;
create policy acc_staff_select on public.acc_docs for select to authenticated
  using (public.acc_staff_can_read(path, col));

drop policy if exists acc_staff_insert on public.acc_docs;
create policy acc_staff_insert on public.acc_docs for insert to authenticated
  with check (public.acc_staff_can_write(path, col));

drop policy if exists acc_staff_update on public.acc_docs;
create policy acc_staff_update on public.acc_docs for update to authenticated
  using (public.acc_staff_can_write(path, col)) with check (public.acc_staff_can_write(path, col));

-- لا توجد قاعدة حذف للموظف: الحذف لصاحب الشركة فقط.
