# 로또 6/45 주간 예측

매주 월요일 08:00(KST)에 Claude Code 루틴이 `lotto_predict.py`를 실행해
역대 1등 당첨번호 전체를 분석하고 그 주 토요일 추첨 회차의 추천 번호 5세트를 만든다.

- `LATEST.md` — 가장 최근 예측
- `predictions/<회차>.md|json` — 회차별 예측 기록 (다음 주 실행 때 실제 결과와 비교)
- `data/draws.json` — 역대 당첨번호 캐시 (출처: https://smok95.github.io/lotto/ , 동행복권 데이터 미러)

```
python3 lotto_predict.py
```

> 로또는 매 회차 독립 시행이다. 이 분석은 재미용이며 당첨 확률을 높이지 않는다.
