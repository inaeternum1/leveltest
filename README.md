# 오늘의 레벨 테스트 (초등 4·5학년)

- 학생: https://inaeternum1.github.io/leveltest/level-test.html
- 학생 앱 받기(안드로이드): https://inaeternum1.github.io/leveltest/student-app.html
- 선생님 앱 받기(안드로이드): https://inaeternum1.github.io/leveltest/app.html

## 구성
| 위치 | 내용 |
|---|---|
| `level-test.html` | 학생 문제 풀이·결과, 교사 채점 화면(`#teacher=키`, 앱은 `#app`) |
| `student-app.html`, `app.html` | 앱(APK) 다운로드 페이지 |
| `exam.html`, `gosi.html` | 임상병리사·공무원 모의고사 (예전 방식: 제출 코드) |
| `supabase/functions/api/index.ts` | 서버 API (Supabase Edge Function `api`) |
| `content/RULES.md` | 매일 출제 규칙 |
| `android/` | 앱 원본. 바뀌면 GitHub Actions가 APK를 다시 만들어 루트에 올림 |

## 데이터
Supabase 프로젝트 `leveltest` (서울). 테이블 `sets`(날짜·학년별 문제, 정답 포함), `submissions`(제출), `config`.
모든 테이블은 공개 키로 접근할 수 없고, Edge Function만 읽고 쓴다.

## 화면을 고칠 때
`level-test.html`을 바꾸면 `version.txt`와 파일 안의 `const BUILD='…'`를 같은 새 값(예: 202610160900)으로 함께 바꾼다. 그러면 폰에 예전 화면이 저장돼 있어도 한 번 자동으로 새로 불러온다.
