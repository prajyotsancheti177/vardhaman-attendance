import { createClient } from '@supabase/supabase-js';

let client;
const db = new Proxy({}, { get(_, k) {
  if (!client) {
    if (!process.env.SUPABASE_SERVICE_ROLE_KEY) throw new HttpError(500, 'SUPABASE_SERVICE_ROLE_KEY not set on Vercel');
    client = createClient(process.env.SUPABASE_URL, process.env.SUPABASE_SERVICE_ROLE_KEY, { auth: { persistSession: false } });
  }
  return client[k].bind ? client[k].bind(client) : client[k];
} });

// Minutes between two "HH:MM[:SS]" times, minus lunch.
export function workedMinutes(entry, exit, lunch, extra) {
  const toMin = (t) => { const [h, m] = t.split(':').map(Number); return h * 60 + m; };
  return Math.max(0, toMin(exit) - toMin(entry) - (lunch || 0) - (extra || 0));
}

function monthRange(month) {
  if (!/^\d{4}-\d{2}$/.test(month || '')) throw new HttpError(400, 'month must be YYYY-MM');
  const [y, m] = month.split('-').map(Number);
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
  return [`${month}-01`, `${month}-${String(last).padStart(2, '0')}`];
}

class HttpError extends Error { constructor(status, msg) { super(msg); this.status = status; } }
const must = ({ data, error }) => { if (error) throw new HttpError(400, error.message); return data; };
const round2 = (n) => Math.round(n * 100) / 100;

function withHours(e) {
  const minutes = workedMinutes(e.entry_time, e.exit_time, e.lunch_minutes, e.extra_break_minutes);
  return { ...e, worked_minutes: minutes, amount: round2((minutes / 60) * Number(e.hourly_rate)) };
}

function buildSlip(employee, month, entries, advances) {
  const totalMinutes = entries.reduce((s, e) => s + e.worked_minutes, 0);
  const gross = round2(entries.reduce((s, e) => s + e.amount, 0));
  const advanceTotal = round2(advances.reduce((s, a) => s + Number(a.amount), 0));
  return {
    employee, month, entries, advances,
    days_worked: entries.length, total_minutes: totalMinutes,
    gross, advance_total: advanceTotal, net: round2(gross - advanceTotal),
  };
}

// Loads one month in 3 parallel queries (optionally for a single employee).
async function monthData(month, employeeId) {
  const [from, to] = monthRange(month);
  let eq = db.from('att_employee').select('*').order('name');
  let en = db.from('att_entry').select('*').gte('work_date', from).lte('work_date', to).order('work_date');
  let ad = db.from('att_advance').select('*').gte('given_on', from).lte('given_on', to).order('given_on');
  if (employeeId) { eq = eq.eq('id', employeeId); en = en.eq('employee_id', employeeId); ad = ad.eq('employee_id', employeeId); }
  const [emps, entries, advances] = (await Promise.all([eq, en, ad])).map(must);
  return emps.map((e) => buildSlip(e, month,
    entries.filter((x) => x.employee_id === e.id).map(withHours),
    advances.filter((x) => x.employee_id === e.id)));
}

async function payslip(employeeId, month) {
  const [slip] = await monthData(month, employeeId);
  if (!slip) throw new HttpError(404, 'employee not found');
  return slip;
}

async function route(method, path, q, body) {
  const seg = path.split('/').filter(Boolean); // e.g. ['employees','3']
  const id = seg[1] ? Number(seg[1]) : null;

  switch (seg[0]) {
    case 'ping': return { ok: true };

    case 'employees':
      if (method === 'GET') {
        let qb = db.from('att_employee').select('*').order('name');
        if (q.active === 'true') qb = qb.eq('active', true);
        return must(await qb);
      }
      if (method === 'POST') return must(await db.from('att_employee').insert(pick(body, EMP)).select().single());
      if (method === 'PUT' && id) return must(await db.from('att_employee').update(pick(body, EMP)).eq('id', id).select().single());
      break;

    case 'entries':
      if (method === 'GET') {
        let qb = db.from('att_entry').select('*').order('work_date');
        if (q.date) qb = qb.eq('work_date', q.date);
        else if (q.employee_id && q.month) {
          const [from, to] = monthRange(q.month);
          qb = qb.eq('employee_id', q.employee_id).gte('work_date', from).lte('work_date', to);
        } else throw new HttpError(400, 'pass date, or employee_id + month');
        return must(await qb).map(withHours);
      }
      if (method === 'PUT') { // upsert one day for one employee
        const row = pick(body, ['employee_id', 'work_date', 'entry_time', 'exit_time', 'lunch_minutes', 'extra_break_minutes', 'note']);
        if (body.hourly_rate == null) {
          const emp = must(await db.from('att_employee').select('hourly_rate').eq('id', row.employee_id).single());
          row.hourly_rate = emp.hourly_rate;
        } else row.hourly_rate = body.hourly_rate;
        return withHours(must(await db.from('att_entry').upsert(row, { onConflict: 'employee_id,work_date' }).select().single()));
      }
      if (method === 'DELETE' && id) { must(await db.from('att_entry').delete().eq('id', id)); return { ok: true }; }
      break;

    case 'advances':
      if (method === 'GET') {
        let qb = db.from('att_advance').select('*').order('given_on', { ascending: false });
        if (q.month) { const [from, to] = monthRange(q.month); qb = qb.gte('given_on', from).lte('given_on', to); }
        if (q.employee_id) qb = qb.eq('employee_id', q.employee_id);
        return must(await qb);
      }
      if (method === 'POST') return must(await db.from('att_advance').insert(pick(body, ['employee_id', 'amount', 'given_on', 'note'])).select().single());
      if (method === 'DELETE' && id) { must(await db.from('att_advance').delete().eq('id', id)); return { ok: true }; }
      break;

    case 'payslip':
      return payslip(Number(q.employee_id), q.month);

    case 'summary': {
      const slips = await monthData(q.month);
      return slips.filter((s) => s.employee.active || s.days_worked || s.advances.length)
        .map(({ entries, advances, ...rest }) => rest);
    }
  }
  throw new HttpError(404, 'not found');
}

const EMP = ['name', 'phone', 'hourly_rate', 'active'];
function pick(obj = {}, keys) {
  return Object.fromEntries(keys.filter((k) => obj[k] !== undefined).map((k) => [k, obj[k]]));
}

export default async function handler(req, res) {
  try {
    if (!process.env.ADMIN_PIN || req.headers['x-admin-pin'] !== process.env.ADMIN_PIN) {
      throw new HttpError(401, 'wrong PIN');
    }
    const url = new URL(req.url, 'http://x');
    const path = url.pathname.replace(/^\/api/, '');
    const q = Object.fromEntries(url.searchParams);
    res.status(200).json(await route(req.method, path, q, req.body));
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message });
  }
}
