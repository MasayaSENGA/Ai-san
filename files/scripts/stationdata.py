"""局データJSON(files/*.json)の読み書きと共通定義。"""

import json
import re
from pathlib import Path

FILES_DIR = Path(__file__).resolve().parent.parent

# stations テーブルの列順。JSON の各局オブジェクトもこの順でキーを並べる。
COLUMNS = [
    "id", "band", "freq_khz", "name", "area", "callsign", "network",
    "language", "target_area", "note", "power_kw", "lat", "lon",
]

# 都道府県コード(JIS X 0401)順
PREFECTURES = [
    "北海道", "青森県", "岩手県", "宮城県", "秋田県", "山形県", "福島県", "茨城県", "栃木県", "群馬県",
    "埼玉県", "千葉県", "東京都", "神奈川県", "新潟県", "富山県", "石川県", "福井県", "山梨県", "長野県",
    "岐阜県", "静岡県", "愛知県", "三重県", "滋賀県", "京都府", "大阪府", "兵庫県", "奈良県", "和歌山県",
    "鳥取県", "島根県", "岡山県", "広島県", "山口県", "徳島県", "香川県", "愛媛県", "高知県", "福岡県",
    "佐賀県", "長崎県", "熊本県", "大分県", "宮崎県", "鹿児島県", "沖縄県",
]

# DB だけにある列(build_db.py が JSON の列から導出する。stations テーブルでは COLUMNS の後に並ぶ)
DERIVED_COLUMNS = ["prefecture", "category", "role"]
# category(局の種類)と role(送信所の役割)の値。アプリの StationCategory / StationRole と一致させる
CATEGORIES = ("nhk", "commercial", "community", "afn", "international", "other")
ROLES = ("main", "relay", "fm_complement")
COMMERCIAL_NETWORKS = ("JRN", "NRN", "JRN/NRN", "JFN", "JFL", "independent")
NETWORK_CATEGORIES = {
    "NHK": "nhk", "community": "community", "AFN": "afn",
    **{network: "commercial" for network in COMMERCIAL_NETWORKS},
}
PREFECTURE_RE = re.compile(r"^(北海道|東京都|京都府|大阪府|.{2,3}県)")

# 免許情報の所在地の表記ゆれ
LOCATION_ALIASES = {
    "東京都八丈島八丈町": "東京都八丈町",
}


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


def prefecture_of(area):
    """area の都道府県。「東京」のような都道府県の短い名前にも対応する。判定できなければ None。

    部分一致は使わない(「東京都府中市」は「京都府」を含む)。
    """
    if not area:
        return None
    short_names = {p if p == "北海道" else p[:-1]: p for p in PREFECTURES}
    if area in short_names:
        return short_names[area]
    m = PREFECTURE_RE.match(area)
    if m and m.group(1) in PREFECTURES:
        return m.group(1)
    return None


def load_municipality_coords():
    return load(FILES_DIR / "municipality_coords.json")["municipalities"]


def locate(location, coords):
    """「北海道河東郡音更町」のような所在地を (緯度, 経度) にする。見つからなければ None。"""
    location = LOCATION_ALIASES.get(location, location)
    # 郡名は町村の前だけ外す(「蒲郡市」「郡山市」は市名の一部)
    key = re.sub(r"^(北海道|東京都|京都府|大阪府|.{2,3}県)(\S+?郡)(?=\S+[町村]$)", r"\1", location)
    if key in coords:
        return tuple(coords[key])
    # 政令指定都市の区が無ければ市の座標で代用する
    m = re.match(r"^(.+?市)\S+区$", key)
    if m and m.group(1) in coords:
        return tuple(coords[m.group(1)])
    return None
