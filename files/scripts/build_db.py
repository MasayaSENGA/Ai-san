#!/usr/bin/env python3
"""files/*.json の局データを検証して radio_stations.db を作り、アプリのアセットにコピーする。

データベースの user_version は AppDatabase.kt の version と同じ値にする。
Room は端末上のDBとバージョンが異なるとアセットのDBをコピーし直すため、
データを更新したら AppDatabase.kt の version を1つ上げてからこのスクリプトを実行する。

stations テーブルには JSON の列(stationdata.COLUMNS)に加えて、放送局一覧の絞り込み用に
次の列(stationdata.DERIVED_COLUMNS)を JSON の内容から導出して入れる(derive() を参照)。
    prefecture  都道府県。area から判定する(jp_am_fm_stations.json の主要局は「東京」のような放送局の都道府県、
                それ以外は送信所の市区町村の都道府県)。国際放送は NULL。
    category    局の種類。nhk / commercial(民放)/ community / afn / international / other
    role        送信所の役割。main(親局)/ relay(中継局)/ fm_complement(FM補完中継局)。国際放送は NULL。

使い方:
    python3 files/scripts/build_db.py
"""

import re
import shutil
import sqlite3
import sys
from collections import Counter

import stationdata
from stationdata import COLUMNS, DERIVED_COLUMNS, FILES_DIR

REPO_DIR = FILES_DIR.parent
DB_PATH = FILES_DIR / "radio_stations.db"
ASSET_PATH = REPO_DIR / "app/src/main/assets/database/radio_stations.db"
APP_DATABASE_KT = REPO_DIR / "app/src/main/java/com/aisan/radio/data/AppDatabase.kt"

SOURCES = [
    "jp_am_fm_stations.json",
    "community_fm_stations.json",
    "jp_sw_stations.json",
    "afn_stations.json",
    "mic_stations.json",
    "intl_stations.json",
]
CURATED_SOURCES = {"jp_am_fm_stations.json", "community_fm_stations.json", "jp_sw_stations.json"}
INTL_SOURCE = "intl_stations.json"
AFN_SOURCE = "afn_stations.json"
MIC_SOURCE = "mic_stations.json"
# mic_import.generate() が FM補完中継局の note の先頭に書く文字列
FM_COMPLEMENT_NOTE_PREFIX = "FM補完中継局"

# Room の Station エンティティと一致させること(列の型・NOT NULL・インデックス名)
SCHEMA = """
CREATE TABLE stations (
    id TEXT PRIMARY KEY NOT NULL,
    band TEXT NOT NULL,
    freq_khz REAL,
    name TEXT NOT NULL,
    area TEXT,
    callsign TEXT,
    network TEXT,
    language TEXT,
    target_area TEXT,
    note TEXT,
    power_kw REAL,
    lat REAL,
    lon REAL,
    prefecture TEXT,
    category TEXT NOT NULL,
    role TEXT
);
CREATE INDEX idx_freq ON stations(freq_khz);
CREATE INDEX idx_band ON stations(band);
"""

FREQ_RANGES_KHZ = {"AM": (520, 1720), "FM": (76000, 99000), "SW": (2300, 26100)}
# 国内局(総務省の免許情報・AFN)の座標は日本の範囲に収まるはず
JAPAN_BOUNDS = {"lat": (20.0, 46.0), "lon": (122.0, 154.0)}
DOMESTIC_SOURCES = {"jp_am_fm_stations.json", "community_fm_stations.json", "jp_sw_stations.json",
                    "afn_stations.json", "mic_stations.json"}


def room_version():
    m = re.search(r"version\s*=\s*(\d+)", APP_DATABASE_KT.read_text(encoding="utf-8"))
    if not m:
        raise SystemExit(f"{APP_DATABASE_KT} から version を読み取れない")
    return int(m.group(1))


def check_coordinates(s, domestic):
    lat, lon = s["lat"], s["lon"]
    if lat is None and lon is None:
        return []
    if not (isinstance(lat, float) and isinstance(lon, float)):
        return [f"lat/lon は両方とも数値か両方とも null にする ({lat}, {lon})"]
    low_lat, high_lat = JAPAN_BOUNDS["lat"] if domestic else (-90.0, 90.0)
    low_lon, high_lon = JAPAN_BOUNDS["lon"] if domestic else (-180.0, 180.0)
    if not (low_lat <= lat <= high_lat and low_lon <= lon <= high_lon):
        return [f"座標 ({lat}, {lon}) が範囲外"]
    return []


def load_stations():
    """(ファイル名, 局) の組のリストを返す。"""
    stations, errors = [], []
    for filename in SOURCES:
        for s in stationdata.load(FILES_DIR / filename)["stations"]:
            where = f"{filename}: {s.get('id')}"
            if list(s) != COLUMNS:
                errors.append(f"{where}: 列が {COLUMNS} と一致しない")
                continue
            if s["band"] not in FREQ_RANGES_KHZ:
                errors.append(f"{where}: 不明な band {s['band']}")
                continue
            low, high = FREQ_RANGES_KHZ[s["band"]]
            if not (isinstance(s["freq_khz"], float) and low <= s["freq_khz"] <= high):
                errors.append(f"{where}: freq_khz {s['freq_khz']} が {s['band']} の範囲外")
            if not s["id"] or not s["name"]:
                errors.append(f"{where}: id または name が空")
            if s["power_kw"] is not None and not s["power_kw"] > 0:
                errors.append(f"{where}: power_kw {s['power_kw']} が正の数でない")
            errors.extend(f"{where}: {e}" for e in check_coordinates(s, filename in DOMESTIC_SOURCES))
            stations.append((filename, s))
    for station_id, count in Counter(s["id"] for _, s in stations).items():
        if count > 1:
            errors.append(f"id が重複: {station_id}")
    duplicates = Counter((s["band"], s["freq_khz"], s["name"], s["area"]) for _, s in stations)
    for key, count in duplicates.items():
        if count > 1:
            errors.append(f"同じ局が重複: {key}")
    if errors:
        raise SystemExit("\n".join(errors))
    return stations


def derive(pairs):
    """各局に prefecture / category / role を足した行のリストを返す。判定できない局があれば止める。"""
    errors = []
    curated_names = {s["name"] for filename, s in pairs if filename in CURATED_SOURCES}
    # 総務省データでコールサインを持つ民放・コミュニティFMの送信所のうち、局名ごとに出力が最大のもの
    # (手で整備したファイルに無い局の親局。例: 栃木放送は鹿沼市の JOXF が親局で、足利市の JOXM などは中継局)
    representatives = {}
    for filename, s in pairs:
        if (filename == MIC_SOURCE and s["callsign"]
                and stationdata.NETWORK_CATEGORIES.get(s["network"]) in ("commercial", "community")):
            key = (-(s["power_kw"] or 0), s["id"])
            if s["name"] not in representatives or key < representatives[s["name"]][0]:
                representatives[s["name"]] = (key, s["id"])

    rows = []
    for filename, s in pairs:
        where = f"{filename}: {s['id']}"
        intl = filename == INTL_SOURCE

        prefecture = None if intl else stationdata.prefecture_of(s["area"])
        if not intl and prefecture is None:
            errors.append(f"{where}: area {s['area']!r} から都道府県を判定できない")

        if intl:
            category = "international"
        elif s["network"] is None:
            category = "other"  # 臨時災害放送局、中継元が分からない受信障害対策中継局
        elif s["network"] in stationdata.NETWORK_CATEGORIES:
            category = stationdata.NETWORK_CATEGORIES[s["network"]]
        else:
            category = "other"
            errors.append(f"{where}: 不明な network {s['network']!r}")

        if intl:
            role = None
        elif filename == AFN_SOURCE:
            role = "main"  # AFN はコールサインを持たないが、いずれも各基地の放送局
        elif (s["note"] or "").startswith(FM_COMPLEMENT_NOTE_PREFIX):
            role = "fm_complement"
        elif s["callsign"] is None:
            role = "relay"
        elif filename in CURATED_SOURCES:
            role = "main"
        elif s["network"] in ("NHK", None):
            role = "main"  # NHK の地域の放送局(函館など)、臨時災害放送局
        elif s["name"] not in curated_names and representatives.get(s["name"], (None, None))[1] == s["id"]:
            role = "main"
        else:
            role = "relay"  # コールサインを持つ中継局(HBCラジオ 北見 JOQN など)

        if (category == "afn") != (filename == AFN_SOURCE):
            errors.append(f"{where}: category afn は afn_stations.json の局だけ")
        if role == "fm_complement" and not (filename == MIC_SOURCE and s["band"] == "FM"
                                            and s["callsign"] is None and category == "commercial"):
            errors.append(f"{where}: FM補完中継局は総務省データの民放FMでコールサインが無い局だけ")
        for token in (s["language"] or "").split("、"):
            if re.fullmatch(r"[A-Za-z .'-]+", token):
                errors.append(f"{where}: language は日本語で書く ({token!r})")
        rows.append({**s, "prefecture": prefecture, "category": category, "role": role})
    if errors:
        raise SystemExit("\n".join(errors))
    return rows


def main():
    version = room_version()
    stations = derive(load_stations())
    stations.sort(key=lambda s: (s["band"], s["freq_khz"], s["id"]))
    columns = COLUMNS + DERIVED_COLUMNS

    tmp_path = DB_PATH.with_suffix(".db.tmp")
    tmp_path.unlink(missing_ok=True)
    con = sqlite3.connect(tmp_path)
    con.executescript(SCHEMA)
    con.executemany(
        f"INSERT INTO stations ({', '.join(columns)}) VALUES ({', '.join('?' * len(columns))})",
        [tuple(s[c] for c in columns) for s in stations],
    )
    con.execute(f"PRAGMA user_version = {version}")
    con.commit()
    con.execute("VACUUM")
    con.close()
    tmp_path.replace(DB_PATH)
    shutil.copyfile(DB_PATH, ASSET_PATH)

    counts = Counter(s["band"] for s in stations)
    located = sum(1 for s in stations if s["lat"] is not None)
    print(f"{DB_PATH.name}: {len(stations)}件 {dict(sorted(counts.items()))} / 座標あり {located}件 / user_version={version}",
          file=sys.stderr)
    print(f"  category: {dict(Counter(s['category'] for s in stations).most_common())}", file=sys.stderr)
    print(f"  role: {dict(Counter(s['role'] or 'NULL' for s in stations).most_common())}", file=sys.stderr)
    print(f"コピー先: {ASSET_PATH.relative_to(REPO_DIR)}", file=sys.stderr)


if __name__ == "__main__":
    main()
