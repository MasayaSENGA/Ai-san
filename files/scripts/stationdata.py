"""局データJSON(files/*.json)の読み書きと共通定義。"""

import json
from pathlib import Path

FILES_DIR = Path(__file__).resolve().parent.parent

# stations テーブルの列順。JSON の各局オブジェクトもこの順でキーを並べる。
COLUMNS = [
    "id", "band", "freq_khz", "name", "area", "callsign", "network",
    "language", "target_area", "note", "power_kw",
]


def station(**fields):
    """全列を持つ局オブジェクトを列順で作る(未指定の列は None)。"""
    unknown = set(fields) - set(COLUMNS)
    if unknown:
        raise ValueError(f"unknown columns: {sorted(unknown)}")
    return {col: fields.get(col) for col in COLUMNS}


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def save(path, data):
    # 既存ファイルと同じ書式(indent=2、非ASCIIはそのまま、末尾改行なし)で書き出す
    with open(path, "w", encoding="utf-8") as f:
        f.write(json.dumps(data, ensure_ascii=False, indent=2))
