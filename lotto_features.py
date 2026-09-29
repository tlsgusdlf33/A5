"""조합 인기도 모델 특성 (research/research.py 학습, lotto_predict.py 적용에 공통 사용)."""
from collections import Counter

ROWS = 7  # 로또 용지는 한 줄에 7개 번호

FEATURE_NAMES = ["월(1-12) 개수", "생일(≤31) 개수", "연속 쌍", "용지 가로줄 최대", "용지 세로줄 최대",
                 "등차수열", "7의 배수", "5의 배수", "같은 끝수 최대", "40번대 개수", "합계/100"]


def features(nums):
    nums = sorted(nums)
    rows = Counter((n - 1) // ROWS for n in nums)
    cols = Counter((n - 1) % ROWS for n in nums)
    diffs = [b - a for a, b in zip(nums, nums[1:])]
    lastd = Counter(n % 10 for n in nums)
    f = [
        sum(n <= 12 for n in nums),
        sum(n <= 31 for n in nums),
        sum(d == 1 for d in diffs),
        max(rows.values()),
        max(cols.values()),
        int(len(set(diffs)) == 1),
        sum(n % 7 == 0 for n in nums),
        sum(n % 5 == 0 for n in nums),
        max(lastd.values()),
        sum(n >= 40 for n in nums),
        sum(nums) / 100,
    ]
    return f + [1.0 if n in nums else 0.0 for n in range(1, 46)]
