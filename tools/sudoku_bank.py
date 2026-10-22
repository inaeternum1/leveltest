"""스도쿠 문제 은행 만들기 (레벨 테스트 11번 문항용)

- 정답이 하나뿐인 퍼즐만 만든다(백트래킹으로 해가 2개 이상인지 확인).
- 사람이 푸는 방법(논리 기법)으로 끝까지 풀리는지 확인하고, 필요한 가장 어려운 기법으로 등급을 매긴다.
  4학년(어려움): 단일 후보만으로 풀리지만 '숨은 하나(hidden single)'만으로는 안 되고, 빈칸이 많다(힌트 24~27개).
  5학년(더 어려움): 칸 후보 줄이기(포인팅·클레이밍, 짝 찾기)가 꼭 필요하다. 찍기는 필요 없다(힌트 22~26개).
- 출력: JSON 줄 목록 {"grade":4,"puzzle":"81자(0=빈칸)","solution":"81자","clues":n,"level":"..."}

사용: python3 tools/sudoku_bank.py 120 > bank.jsonl
"""
import random, sys, json

ROWS = [[r * 9 + c for c in range(9)] for r in range(9)]
COLS = [[r * 9 + c for r in range(9)] for c in range(9)]
BOXS = [[(br * 3 + r) * 9 + bc * 3 + c for r in range(3) for c in range(3)] for br in range(3) for bc in range(3)]
UNITS = ROWS + COLS + BOXS
PEERS = [set() for _ in range(81)]
for u in UNITS:
    for i in u:
        PEERS[i] |= set(u)
for i in range(81):
    PEERS[i].discard(i)


def full_grid(rng):
    g = [0] * 81

    def fill(k):
        if k == 81:
            return True
        ds = list(range(1, 10))
        rng.shuffle(ds)
        for d in ds:
            if all(g[p] != d for p in PEERS[k]):
                g[k] = d
                if fill(k + 1):
                    return True
        g[k] = 0
        return False

    fill(0)
    return g


def count_solutions(g, limit=2):
    g = g[:]
    cands = {}

    def best():
        bi, bc = -1, None
        for i in range(81):
            if g[i] == 0:
                c = {1, 2, 3, 4, 5, 6, 7, 8, 9} - {g[p] for p in PEERS[i]}
                if bc is None or len(c) < len(bc):
                    bi, bc = i, c
                    if len(c) <= 1:
                        break
        return bi, bc

    n = 0

    def rec():
        nonlocal n
        i, c = best()
        if i < 0:
            n += 1
            return n >= limit
        for d in c:
            g[i] = d
            if rec():
                return True
        g[i] = 0
        return False

    rec()
    return n


def logic_solve(puzzle, allow):
    """사람 기법으로 풀기. allow: 쓸 수 있는 기법 집합. 반환: (풀렸는지, 쓴 기법별 횟수)"""
    g = puzzle[:]
    cand = [set() if g[i] else {1, 2, 3, 4, 5, 6, 7, 8, 9} - {g[p] for p in PEERS[i]} for i in range(81)]
    used = {}

    def place(i, d, tech):
        g[i] = d
        cand[i] = set()
        for p in PEERS[i]:
            cand[p].discard(d)
        used[tech] = used.get(tech, 0) + 1

    while 0 in g:
        progress = False
        # 숨은 하나: 한 줄/칸/상자에서 그 숫자가 들어갈 곳이 한 곳뿐
        for u in UNITS:
            for d in range(1, 10):
                if any(g[i] == d for i in u):
                    continue
                spots = [i for i in u if d in cand[i]]
                if len(spots) == 1:
                    place(spots[0], d, "hidden_single")
                    progress = True
                elif not spots:
                    return False, used
        if progress:
            continue
        # 홀로 남은 하나: 그 칸에 들어갈 수 있는 숫자가 하나뿐
        if "naked_single" in allow:
            for i in range(81):
                if g[i] == 0 and len(cand[i]) == 1:
                    place(i, next(iter(cand[i])), "naked_single")
                    progress = True
                    break
            if progress:
                continue
        # 포인팅·클레이밍(상자와 줄이 겹치는 곳)
        if "locked" in allow:
            for b in BOXS:
                for line in ROWS + COLS:
                    inter = set(b) & set(line)
                    if len(inter) != 3:
                        continue
                    for d in range(1, 10):
                        in_b = [i for i in b if d in cand[i]]
                        in_l = [i for i in line if d in cand[i]]
                        if in_b and set(in_b) <= inter:
                            rm = [i for i in line if i not in inter and d in cand[i]]
                            if rm:
                                for i in rm:
                                    cand[i].discard(d)
                                used["locked"] = used.get("locked", 0) + 1
                                progress = True
                        if in_l and set(in_l) <= inter:
                            rm = [i for i in b if i not in inter and d in cand[i]]
                            if rm:
                                for i in rm:
                                    cand[i].discard(d)
                                used["locked"] = used.get("locked", 0) + 1
                                progress = True
            if progress:
                continue
        # 짝 찾기(두 칸에 같은 두 후보)
        if "pair" in allow:
            for u in UNITS:
                pairs = [i for i in u if len(cand[i]) == 2]
                for a in range(len(pairs)):
                    for b2 in range(a + 1, len(pairs)):
                        x, y = pairs[a], pairs[b2]
                        if cand[x] == cand[y]:
                            for i in u:
                                if i not in (x, y) and cand[i] & cand[x]:
                                    cand[i] -= cand[x]
                                    progress = True
                            if progress:
                                used["pair"] = used.get("pair", 0) + 1
                                break
                    if progress:
                        break
                if progress:
                    break
            if progress:
                continue
        return False, used
    return True, used


SINGLES = {"naked_single"}
HARDER = {"naked_single", "locked", "pair"}


def rate(p):
    ok_hidden, _ = logic_solve(p, set())
    if ok_hidden:
        return "easy"
    ok1, _ = logic_solve(p, SINGLES)
    if ok1:
        return "hard4"
    ok2, used = logic_solve(p, HARDER)
    if ok2:
        return "hard5"
    return "too_hard"


def make(rng, want, clue_range):
    while True:
        sol = full_grid(rng)
        p = sol[:]
        order = list(range(81))
        rng.shuffle(order)
        # 대칭으로 빈칸을 판다(보기 좋은 모양)
        for i in order:
            j = 80 - i
            if p[i] == 0:
                continue
            keep = (p[i], p[j])
            p[i] = p[j] = 0
            if count_solutions(p) != 1:
                p[i], p[j] = keep
            if sum(1 for x in p if x) <= clue_range[0]:
                break
        clues = sum(1 for x in p if x)
        if not (clue_range[0] <= clues <= clue_range[1]):
            continue
        if rate(p) == want:
            return p, sol, clues


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 10
    seed = int(sys.argv[2]) if len(sys.argv) > 2 else 20261022
    rng = random.Random(seed)
    seen = set()
    for grade, want, rng_clues in ((4, "hard4", (24, 27)), (5, "hard5", (22, 26))):
        k = 0
        while k < n:
            p, sol, clues = make(rng, want, rng_clues)
            key = "".join(map(str, p))
            if key in seen:
                continue
            seen.add(key)
            assert count_solutions(p) == 1
            assert all(sol[i] == p[i] for i in range(81) if p[i])
            print(json.dumps({"grade": grade, "puzzle": key, "solution": "".join(map(str, sol)), "clues": clues, "level": want}))
            k += 1
            sys.stdout.flush()


if __name__ == "__main__":
    main()
