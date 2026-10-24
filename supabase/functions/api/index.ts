// 오늘의 레벨 테스트 API (공개 엔드포인트, 교사 기능은 교사 키로 보호)
// verify_jwt=false: 학생은 로그인 없이 쓰고, 교사 기능은 아래 isTeacher()로 직접 확인한다.
import { createClient } from "npm:@supabase/supabase-js@2";

const sb = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});

const PAGES = ["level", "exam", "gosi"];
const GRADES = [4, 5];
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization, apikey, x-client-info",
  "Access-Control-Max-Age": "86400",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
  });
const kst = (plusDays = 0) => new Date(Date.now() + 9 * 3600e3 + plusDays * 86400e3).toISOString().slice(0, 10);
const todayKST = () => kst(0);
// 오늘(한국 시간)까지 최근 7일, 오래된 날부터
const week = () => [-6, -5, -4, -3, -2, -1, 0].map((d) => kst(d));
const DAILY_DEFAULT = { on: true, time: "16:00", title: "오늘의 레벨 테스트", body: "오늘 문제가 올라왔어요. 지금 풀어 볼까요?" };

let keyHash: string | null = null;
async function sha256(s: string) {
  const b = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
}
async function isTeacher(key: unknown) {
  if (typeof key !== "string" || !key || key.length > 200) return false;
  if (!keyHash) {
    const { data } = await sb.from("config").select("value").eq("key", "teacher_key_sha256").maybeSingle();
    keyHash = data?.value ?? null;
  }
  if (!keyHash) return false;
  const h = await sha256(key);
  let diff = h.length ^ keyHash.length;
  for (let i = 0; i < Math.min(h.length, keyHash.length); i++) diff |= h.charCodeAt(i) ^ keyHash.charCodeAt(i);
  return diff === 0;
}

type Q = { s: string; unit: string; q: string; passage?: string; fig?: string; script?: string; options?: string[]; answer?: number; open?: boolean; model?: string; num?: boolean; ans?: string; sudoku?: string; sol?: string; exp: string };
type Push = { id: number; at: number; title: string; body: string; name?: string; grade?: number };
type SetRow = { date: string; grade: number; questions: Q[]; released: boolean };
type SubRow = { rid: string; page: string; name: string; set_id: string; grade: number | null; answers: number[]; texts: string | null; created_at: string };

// 학생에게 보내는 문항: 정답(answer·ans)·해설·모범답안 제외. 듣기 대본(script)은 소리로 읽어 주려고 보내지만 화면에는 표시하지 않는다.
// num = 사고력 수학(숫자를 직접 입력), sudoku = 스도쿠 문제판(81자, 0=빈칸). 정답 판(sol)은 보내지 않는다.
const strip = (q: Q) => ({ s: q.s, unit: q.unit, q: q.q, passage: q.passage, fig: q.fig, script: q.script, options: q.options, open: q.open, num: q.num, sudoku: q.sudoku });
const subOut = (r: SubRow) => ({
  rid: r.rid, page: r.page, name: r.name, setId: r.set_id, grade: r.grade ?? undefined,
  answers: r.answers, texts: r.texts ?? undefined, at: new Date(r.created_at).getTime(),
});
const setOut = (s: SetRow) => ({ date: s.date, grade: s.grade, questions: s.questions, released: s.released });

async function getSet(date: string, grade: number): Promise<SetRow | null> {
  const { data, error } = await sb.from("sets").select("date,grade,questions,released").eq("date", date).eq("grade", grade).maybeSingle();
  if (error) throw error;
  return data as SetRow | null;
}

const ALPHA = "abcdefghjkmnpqrstuvwxyz23456789";
const newRid = () => {
  const b = new Uint8Array(10);
  crypto.getRandomValues(b);
  return Array.from(b, (x) => ALPHA[x % ALPHA.length]).join("");
};

async function body(req: Request): Promise<Record<string, unknown> | null> {
  try {
    const t = await req.text();
    if (t.length > 20000) return null;
    return JSON.parse(t);
  } catch {
    return null;
  }
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  const url = new URL(req.url);
  const p = url.searchParams;
  const route = url.pathname.split("/").filter(Boolean).pop();
  try {
    // 오늘(한국 시간) 이하 최신 세트 또는 지정 날짜 세트 (정답 제외)
    if (req.method === "GET" && route === "set") {
      const grade = Number(p.get("grade"));
      const date = p.get("date");
      if (!GRADES.includes(grade)) return json({ error: "bad grade" }, 400);
      if (date && !DATE_RE.test(date)) return json({ error: "bad date" }, 400);
      let set: SetRow | null;
      if (date) set = date > todayKST() ? null : await getSet(date, grade);
      else {
        const { data, error } = await sb.from("sets").select("date,grade,questions,released")
          .eq("grade", grade).lte("date", todayKST()).order("date", { ascending: false }).limit(1).maybeSingle();
        if (error) throw error;
        set = data as SetRow | null;
      }
      if (!set) return json({ error: "not found" }, 404);
      return json({ date: set.date, grade: set.grade, released: set.released, questions: set.questions.map(strip) });
    }

    if (req.method === "POST" && route === "submit") {
      const b = await body(req);
      if (!b) return json({ error: "bad json" }, 400);
      const page = b.page;
      const name = typeof b.name === "string" ? b.name.trim() : "";
      const setId = typeof b.setId === "string" ? b.setId : "";
      const answers = b.answers;
      const texts = b.texts;
      const grade = b.grade === undefined ? undefined : Number(b.grade);
      if (typeof page !== "string" || !PAGES.includes(page)) return json({ error: "bad page" }, 400);
      if (!name || name.length > 20) return json({ error: "bad name" }, 400);
      if (!setId || setId.length > 40) return json({ error: "bad setId" }, 400);
      if (!Array.isArray(answers) || answers.length === 0 || answers.length > 100 ||
          !answers.every((a) => Number.isInteger(a) && a >= -2 && a < 20)) return json({ error: "bad answers" }, 400);
      if (texts !== undefined && (typeof texts !== "string" || texts.length > 4000)) return json({ error: "bad texts" }, 400);
      if (page === "level") {
        if (grade === undefined || !GRADES.includes(grade)) return json({ error: "bad grade" }, 400);
        const set = await getSet(setId, grade);
        if (!set) return json({ error: "no set" }, 400);
        // -2 = '잘 몰라요', -1 = 서술형·숫자 입력·스도쿠(답은 texts)
        const ok = answers.length === set.questions.length && set.questions.every((q, i) => {
          const a = answers[i] as number;
          if (a === -2) return true;
          return q.open || q.num || q.sudoku ? a === -1 : a >= 0 && a < (q.options?.length ?? 0);
        });
        if (!ok) return json({ error: "answers mismatch" }, 400);
      }
      for (let i = 0; i < 3; i++) {
        const rid = newRid();
        const { error } = await sb.from("submissions").insert({
          rid, page, name, set_id: setId, grade: page === "level" ? grade : null,
          answers, texts: (texts as string | undefined) ?? null,
        });
        if (!error) return json({ rid });
        if (error.code !== "23505") throw error;
      }
      return json({ error: "save failed" }, 500);
    }

    // 학생 결과: 선생님이 공개(OK)하기 전에는 잠금 상태만
    if (req.method === "GET" && route === "result") {
      const rid = p.get("rid") ?? "";
      if (!/^[a-z0-9]{10}$/.test(rid)) return json({ error: "bad rid" }, 400);
      const { data, error } = await sb.from("submissions").select("*").eq("rid", rid).maybeSingle();
      if (error) throw error;
      if (!data) return json({ error: "not found" }, 404);
      const d = subOut(data as SubRow);
      if (d.page !== "level" || d.grade === undefined) return json({ ...d, locked: false });
      const set = await getSet(d.setId, d.grade);
      if (!set) return json({ ...d, locked: false });
      if (!set.released) return json({ rid: d.rid, name: d.name, setId: d.setId, grade: d.grade, at: d.at, locked: true });
      return json({ ...d, locked: false, questions: set.questions });
    }

    // 학생 시작 화면: 최근 7일(오늘 포함) 날짜별 문제 유무·제출 여부
    if (req.method === "GET" && route === "history") {
      const grade = Number(p.get("grade"));
      const name = (p.get("name") ?? "").trim();
      if (!GRADES.includes(grade)) return json({ error: "bad grade" }, 400);
      if (!name || name.length > 20) return json({ error: "bad name" }, 400);
      const days = week();
      const [{ data: srows, error: se }, { data: urows, error: ue }] = await Promise.all([
        sb.from("sets").select("date").eq("grade", grade).in("date", days),
        sb.from("submissions").select("set_id").eq("page", "level").eq("grade", grade).eq("name", name).in("set_id", days),
      ]);
      if (se) throw se;
      if (ue) throw ue;
      const has = new Set((srows ?? []).map((r: { date: string }) => r.date));
      const done = new Set((urows ?? []).map((r: { set_id: string }) => r.set_id));
      return json({ today: todayKST(), days: days.map((d) => ({ date: d, set: has.has(d), done: done.has(d) })) });
    }

    // 교사 화면: 날짜 목록(2주 뒤까지), 기본은 오늘 이하 최신 날짜
    if (req.method === "GET" && route === "teacher") {
      if (!(await isTeacher(p.get("key")))) return json({ error: "forbidden" }, 403);
      const { data: drows, error: de } = await sb.from("sets").select("date").lte("date", kst(14))
        .order("date", { ascending: false }).limit(240);
      if (de) throw de;
      const dates = [...new Set((drows ?? []).map((r: { date: string }) => r.date))];
      const want = p.get("date");
      const today = todayKST();
      const date = want && DATE_RE.test(want) ? want : (dates.find((d) => d <= today) ?? dates[0]);
      if (!date) return json({ dates: [], date: null, sets: [], subs: [] });
      const { data: srows, error: se } = await sb.from("sets").select("date,grade,questions,released").eq("date", date).order("grade");
      if (se) throw se;
      const { data: subs, error: ue } = await sb.from("submissions").select("*").eq("page", "level").eq("set_id", date)
        .order("created_at", { ascending: false }).limit(300);
      if (ue) throw ue;
      // 최근 7일 제출 현황표(학생 × 날짜)
      const days = week();
      const [{ data: wsets, error: we1 }, { data: wsubs, error: we2 }] = await Promise.all([
        sb.from("sets").select("date,grade").in("date", days),
        sb.from("submissions").select("name,grade,set_id").eq("page", "level").in("set_id", days).limit(2000),
      ]);
      if (we1) throw we1;
      if (we2) throw we2;
      const weekOut = {
        today: todayKST(), days,
        sets: (wsets ?? []).map((r: { date: string; grade: number }) => ({ date: r.date, grade: r.grade })),
        subs: (wsubs ?? []).map((r: { name: string; grade: number; set_id: string }) => ({ name: r.name, grade: r.grade, date: r.set_id })),
      };
      return json({ dates, date, sets: (srows as SetRow[]).map(setOut), subs: (subs as SubRow[]).map(subOut), week: weekOut });
    }

    // 학생 앱 알림 설정(앱이 15분마다 읽음): 매일 알림 시각, 선생님이 '지금 보내기'한 알림
    if (req.method === "GET" && route === "notice") {
      const { data, error } = await sb.from("config").select("key,value").in("key", ["notice_daily", "notice_push", "notice_pushes"]);
      if (error) throw error;
      const get = (k: string) => { try { return JSON.parse((data ?? []).find((r: { key: string }) => r.key === k)?.value ?? "null"); } catch { return null; } };
      // pushes: 전체에게 보낸 것 + (이름·학년을 알려 준 앱이면) 그 학생에게만 보낸 것, 오래된 것부터
      const name = (p.get("name") ?? "").trim(), grade = Number(p.get("grade"));
      const all = (get("notice_pushes") ?? []) as Push[];
      const pushes = all.filter((x) => !x.name || (x.name === name && x.grade === grade));
      return json({ daily: get("notice_daily") ?? DAILY_DEFAULT, push: get("notice_push"), pushes });
    }

    if (req.method === "POST" && route === "notice") {
      const b = await body(req);
      if (!b) return json({ error: "bad json" }, 400);
      if (!(await isTeacher(b.key))) return json({ error: "forbidden" }, 403);
      const clean = (t: unknown, max: number) => typeof t === "string" ? t.trim().slice(0, max) : "";
      const rows: { key: string; value: string }[] = [];
      if (b.daily && typeof b.daily === "object") {
        const d = b.daily as Record<string, unknown>;
        const time = String(d.time ?? "");
        if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(time)) return json({ error: "bad time" }, 400);
        rows.push({ key: "notice_daily", value: JSON.stringify({
          on: d.on === true, time, title: clean(d.title, 40) || DAILY_DEFAULT.title, body: clean(d.body, 120) || DAILY_DEFAULT.body,
        }) });
      }
      if (b.push && typeof b.push === "object") {
        const d = b.push as Record<string, unknown>;
        const text = clean(d.body, 120);
        if (!text) return json({ error: "bad body" }, 400);
        const to = clean(d.name, 20), toGrade = Number(d.grade);
        if (to && !GRADES.includes(toGrade)) return json({ error: "bad grade" }, 400);
        const now = Date.now();
        const item: Push = { id: now, at: now, title: clean(d.title, 40) || (to ? `${to} 학생에게` : "선생님 알림"), body: text };
        if (to) { item.name = to; item.grade = toGrade; }
        const { data: cur } = await sb.from("config").select("value").eq("key", "notice_pushes").maybeSingle();
        let list: Push[] = [];
        try { list = JSON.parse(cur?.value ?? "[]"); } catch { list = []; }
        list = [...list.filter((x) => now - x.at < 24 * 3600 * 1000), item].slice(-40);
        rows.push({ key: "notice_pushes", value: JSON.stringify(list) });
        if (!to) rows.push({ key: "notice_push", value: JSON.stringify(item) });
      }
      if (!rows.length) return json({ error: "nothing" }, 400);
      const { error } = await sb.from("config").upsert(rows, { onConflict: "key" });
      if (error) throw error;
      return json({ ok: true });
    }

    if (req.method === "POST" && route === "release") {
      const b = await body(req);
      if (!b) return json({ error: "bad json" }, 400);
      if (!(await isTeacher(b.key))) return json({ error: "forbidden" }, 403);
      const date = String(b.date ?? "");
      const grade = Number(b.grade);
      if (!DATE_RE.test(date) || !GRADES.includes(grade)) return json({ error: "bad args" }, 400);
      const { data, error } = await sb.from("sets").update({ released: b.released === true }).eq("date", date).eq("grade", grade).select("date");
      if (error) throw error;
      return data && data.length ? json({ ok: true }) : json({ error: "not found" }, 404);
    }

    if (req.method === "GET" && route === "list") {
      const page = p.get("page") ?? "";
      if (!(await isTeacher(p.get("key")))) return json({ error: "forbidden" }, 403);
      if (!PAGES.includes(page)) return json({ error: "bad page" }, 400);
      const { data, error } = await sb.from("submissions").select("*").eq("page", page).order("created_at", { ascending: false }).limit(200);
      if (error) throw error;
      return json((data as SubRow[]).map(subOut));
    }

    return json({ error: "not found" }, 404);
  } catch (e) {
    console.error(e);
    return json({ error: "server error" }, 500);
  }
});
