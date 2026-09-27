#!/usr/bin/env python3
"""総務省 電波利用ポータルの無線局等情報検索 Web-API から国内の放送局を取り込む。

- 中波(AM)・超短波(FM。ワイドFMの 90〜99MHz を含む)・短波の「特定地上基幹放送局」の
  免許情報を取得し、キュレーション済みファイル(CURATED_FILES)に無い局
  (中継局・FM補完中継局・コミュニティFMの中継局など)を mic_stations.json に出力する。
- キュレーション済みファイルの局は power_kw(空中線電力)と lat/lon(送信所の所在地の座標)だけを免許情報で更新する。
  座標は送信所の市区町村の代表点(municipality_coords.json、geo_import.py で生成)。
  周波数・コールサインの食い違いや、免許情報に見当たらない局は報告のみ行う(自動修正はしない)。

使い方:
    python3 files/scripts/mic_import.py                 # API から取得して生成
    python3 files/scripts/mic_import.py --raw-dir DIR   # 取得した生データを DIR に保存
    python3 files/scripts/mic_import.py --offline DIR   # DIR に保存済みの生データから生成

Web-API の利用規約(https://www.tele.soumu.go.jp/j/musen/webapi/kiyaku/index.htm)に従い、
リクエストの間隔を空けてアクセスする。取得したデータを使ったサービスでは出典の明示が必要。
"""

import argparse
import hashlib
import json
import re
import sys
import time
import unicodedata
import urllib.parse
import urllib.request
from collections import defaultdict
from pathlib import Path

import stationdata
from stationdata import FILES_DIR

API_URL = "https://www.tele.soumu.go.jp/musen/list"
# User-Agent の無いリクエストは拒否されることがある(Web-API の説明ページより)
USER_AGENT = "Mozilla/5.0 (compatible; Ai-san-radio-db/1.0)"
REQUEST_INTERVAL_SEC = 3
PAGE_SIZE = 500  # DC=2 (詳細情報付きで取得できる最大件数)

# 帯域ごとの検索条件。OW=BC は「特定地上基幹放送局」(民放・NHK・コミュニティFMの送信所)。
QUERIES = {
    "AM": {"FF": "526.5", "TF": "1606.5", "HZ": "1"},
    "FM": {"FF": "76", "TF": "99", "HZ": "2"},
    "SW": {"FF": "3", "TF": "30", "HZ": "2"},
}

CURATED_FILES = [
    "jp_am_fm_stations.json",
    "community_fm_stations.json",
    "jp_sw_stations.json",
]
OUTPUT_FILE = "mic_stations.json"

# 免許人 → (局名, 系列)。局名はキュレーション済みファイルと同じ表記にそろえる。
# AM局の免許人がFM(超短波放送)の免許を持つ場合、それはFM補完中継局として扱う。
LICENSEES = {
    # 中波(AM)
    "株式会社STVラジオ": ("STVラジオ", "NRN"),
    "北海道放送株式会社": ("HBCラジオ", "JRN/NRN"),
    "青森放送株式会社": ("青森放送", "JRN/NRN"),
    "株式会社アイビーシー岩手放送": ("IBC岩手放送", "JRN/NRN"),
    "東北放送株式会社": ("東北放送(TBCラジオ)", "JRN/NRN"),
    "株式会社秋田放送": ("秋田放送", "JRN/NRN"),
    "山形放送株式会社": ("山形放送", "JRN/NRN"),
    "株式会社ラジオ福島": ("ラジオ福島", "JRN/NRN"),
    "株式会社LuckyFM茨城放送": ("LuckyFM茨城放送", "NRN"),
    "株式会社栃木放送": ("栃木放送(CRT)", "NRN"),
    "株式会社TBSラジオ": ("TBSラジオ", "JRN"),
    "株式会社文化放送": ("文化放送", "NRN"),
    "株式会社ニッポン放送": ("ニッポン放送", "NRN"),
    "株式会社アール・エフ・ラジオ日本": ("ラジオ日本", "independent"),
    "株式会社新潟放送": ("新潟放送", "JRN/NRN"),
    "信越放送株式会社": ("信越放送(SBCラジオ)", "JRN/NRN"),
    "株式会社山梨放送": ("山梨放送", "JRN/NRN"),
    "静岡放送株式会社": ("静岡放送(SBSラジオ)", "JRN/NRN"),
    "北日本放送株式会社": ("北日本放送", "JRN/NRN"),
    "北陸放送株式会社": ("北陸放送", "JRN/NRN"),
    "福井放送株式会社": ("福井放送", "JRN/NRN"),
    "株式会社CBCラジオ": ("CBCラジオ", "JRN"),
    "東海ラジオ放送株式会社": ("東海ラジオ", "NRN"),
    "株式会社岐阜放送": ("岐阜放送ラジオ(GBS)", "independent"),
    "株式会社京都放送": ("京都放送(KBS京都)", "NRN"),
    "朝日放送ラジオ株式会社": ("朝日放送ラジオ", "JRN/NRN"),
    "株式会社MBSラジオ": ("MBSラジオ", "JRN/NRN"),
    "株式会社ラジオ大阪": ("ラジオ大阪", "NRN"),
    "株式会社ラジオ関西": ("ラジオ関西", "independent"),
    "株式会社和歌山放送": ("和歌山放送", "JRN/NRN"),
    "株式会社山陰放送": ("山陰放送(BSS)", "JRN/NRN"),
    "RSK山陽放送株式会社": ("RSKラジオ", "JRN/NRN"),
    "株式会社中国放送": ("中国放送(RCC)", "JRN/NRN"),
    "山口放送株式会社": ("山口放送(KRY)", "JRN/NRN"),
    "四国放送株式会社": ("四国放送(JRT)", "JRN/NRN"),
    "西日本放送株式会社": ("西日本放送ラジオ(RNC)", "JRN/NRN"),
    "南海放送株式会社": ("南海放送(RNB)", "JRN/NRN"),
    "株式会社高知放送": ("高知放送(RKC)", "JRN/NRN"),
    "RKB毎日放送株式会社": ("RKB毎日放送", "JRN"),
    "九州朝日放送株式会社": ("九州朝日放送(KBC)", "NRN"),
    "長崎放送株式会社": ("長崎放送(NBC)", "JRN/NRN"),
    "株式会社熊本放送": ("熊本放送(RKK)", "JRN/NRN"),
    "株式会社大分放送": ("大分放送(OBS)", "JRN/NRN"),
    "株式会社宮崎放送": ("宮崎放送(MRT)", "JRN/NRN"),
    "株式会社南日本放送": ("南日本放送(MBC)", "JRN/NRN"),
    "琉球放送株式会社": ("琉球放送(RBC)", "JRN"),
    "株式会社ラジオ沖縄": ("ラジオ沖縄(ROK)", "NRN"),
    # 超短波(FM)
    "株式会社エフエム北海道": ("AIR-G'", "JFN"),
    "株式会社FM NORTH WAVE": ("FM NORTH WAVE", "JFL"),
    "株式会社エフエム青森": ("エフエム青森", "JFN"),
    "株式会社エフエム岩手": ("エフエム岩手", "JFN"),
    "株式会社エフエム仙台": ("Date fm(エフエム仙台)", "JFN"),
    "株式会社エフエム秋田": ("エフエム秋田", "JFN"),
    "株式会社エフエム山形": ("エフエム山形", "JFN"),
    "株式会社エフエム福島": ("ふくしまFM", "JFN"),
    "株式会社エフエム栃木": ("RADIO BERRY(エフエム栃木)", "JFN"),
    "株式会社エフエム群馬": ("FM GUNMA(エフエム群馬)", "JFN"),
    "株式会社エフエム東京": ("TOKYO FM", "JFN"),
    "株式会社J-WAVE": ("J-WAVE", "JFL"),
    "株式会社InterFM897": ("InterFM897", "JFN"),
    "株式会社ベイエフエム": ("bayfm78", "independent"),
    "株式会社エフエムナックファイブ": ("NACK5", "independent"),
    "横浜エフエム放送株式会社": ("FMヨコハマ", "JFN"),
    "株式会社エフエムラジオ新潟": ("FM新潟", "JFN"),
    "長野エフエム放送株式会社": ("FM長野", "JFN"),
    "株式会社エフエム富士": ("FM-FUJI", "JFN"),
    "静岡エフエム放送株式会社": ("K-MIX", "JFN"),
    "富山エフエム放送株式会社": ("FMとやま", "JFN"),
    "株式会社エフエム石川": ("エフエム石川(HELLO FIVE)", "JFN"),
    "福井エフエム放送株式会社": ("FM福井", "JFN"),
    "株式会社エフエム愛知": ("FM AICHI", "JFN"),
    "株式会社ZIPーFM": ("ZIP-FM", "JFL"),
    "株式会社エフエム岐阜": ("エフエム岐阜(Radio80)", "JFN"),
    "三重エフエム放送株式会社": ("レディオキューブFM三重", "JFN"),
    "株式会社エフエム滋賀": ("e-radio(エフエム滋賀)", "JFN"),
    "株式会社エフエム京都": ("α-STATION", "JFN"),
    "株式会社エフエム大阪": ("FM大阪", "JFN"),
    "株式会社FM802": ("FM802", "JFL"),
    "兵庫エフエム放送株式会社": ("Kiss FM KOBE", "independent"),
    "株式会社エフエム山陰": ("V-air(エフエム山陰)", "JFN"),
    "岡山エフエム放送株式会社": ("岡山エフエム放送(FM岡山)", "JFN"),
    "広島エフエム放送株式会社": ("広島エフエム放送(広島FM)", "JFN"),
    "株式会社エフエム山口": ("エフエム山口(FMY)", "JFN"),
    "株式会社エフエム徳島": ("エフエム徳島(MV)", "JFN"),
    "株式会社エフエム香川": ("エフエム香川(YU)", "JFN"),
    "株式会社エフエム愛媛": ("エフエム愛媛(EU)", "JFN"),
    "株式会社エフエム高知": ("エフエム高知(Hi-Six)", "JFN"),
    "株式会社エフエム福岡": ("エフエム福岡(FM福岡)", "JFN"),
    "株式会社CROSS FM": ("CROSS FM", "JFL"),
    "ラブエフエム国際放送株式会社": ("ラブエフエム国際放送(LOVE FM)", "independent"),
    "株式会社エフエム佐賀": ("エフエム佐賀(FMS)", "JFN"),
    "株式会社エフエム長崎": ("エフエム長崎(HU)", "JFN"),
    "株式会社エフエム熊本": ("エフエム熊本(FMK)", "JFN"),
    "株式会社エフエム大分": ("Air-Radio(エフエム大分)", "JFN"),
    "株式会社エフエム宮崎": ("JOY FM(エフエム宮崎)", "JFN"),
    "株式会社エフエム鹿児島": ("μFM(エフエム鹿児島)", "JFN"),
    "株式会社エフエム沖縄": ("エフエム沖縄(FM沖縄)", "JFN"),
    # 短波
    "株式会社日経ラジオ社": ("ラジオNIKKEI", "independent"),
}

# 同じ免許人でも送信所の都道府県によって局名が変わるもの
REGIONAL_NAMES = {
    ("長崎放送(NBC)", "佐賀"): "NBCラジオ佐賀",
    ("京都放送(KBS京都)", "滋賀"): "京都放送(KBS滋賀)",
}

# 外国語放送の免許で局名が変わるもの(FM802 は FM COCOLO も運営する)
FOREIGN_LANGUAGE_NAMES = {
    "FM802": ("FM COCOLO", "independent"),
}

# 総務省「AM局の運用休止に係る特例措置」で休止中のAM送信所(免許人, 送信所の所在地)。
# 出典: 総務省 AM局の運用休止に係る特例措置
#   https://www.soumu.go.jp/menu_seisaku/ictseisaku/housou_suishin/am_station.html
#   (第一期: 令和7年1月31日現在の一覧、第二期: 令和7年9月1日現在の一覧)
# 所在地は免許情報の「無線設備の設置場所」の市区町村で、PDF の局名と対応させている。
AM_SUSPENDED = {
    "株式会社アイビーシー岩手放送": ["田野畑村", "宮古市"],
    "株式会社LuckyFM茨城放送": ["水戸市", "土浦市", "筑西市"],
    "株式会社新潟放送": ["長岡市", "柏崎市", "十日町市", "糸魚川市", "上越市", "魚沼市", "南魚沼市"],
    "北陸放送株式会社": ["七尾市", "輪島市", "加賀市"],
    "福井放送株式会社": ["坂井市", "敦賀市", "小浜市"],
    "東海ラジオ放送株式会社": ["下呂市", "恵那市", "伊賀市", "新城市", "豊橋市", "尾鷲市"],
    "山口放送株式会社": ["下関市", "山口市", "萩市", "岩国市", "周南市"],
    "南海放送株式会社": ["松山市", "大洲市", "愛南町", "新居浜市", "宇和島市", "八幡浜市"],
    "RKB毎日放送株式会社": ["行橋市"],
    "九州朝日放送株式会社": ["行橋市"],
    "長崎放送株式会社": ["佐賀市", "唐津市", "伊万里市", "有田町", "諫早市"],
    "株式会社熊本放送": ["荒尾市", "阿蘇市", "錦町"],
    "株式会社南日本放送": ["阿久根市", "薩摩川内市", "伊佐市"],
    "青森放送株式会社": ["青森市", "弘前市", "八戸市", "十和田市", "深浦町", "野辺地町"],
    "株式会社ラジオ福島": ["南相馬市"],
    "株式会社栃木放送": ["鹿沼市", "那須塩原市", "足利市"],
    "株式会社山梨放送": ["甲斐市", "富士吉田市", "上野原市"],
    "信越放送株式会社": ["上田市", "佐久市", "軽井沢町"],
    "株式会社CBCラジオ": ["豊橋市", "新城市", "中津川市", "下呂市", "伊賀市", "尾鷲市"],
    "静岡放送株式会社": ["三島市", "御殿場市", "富士宮市"],
    "株式会社京都放送": ["彦根市"],
    "株式会社山陰放送": ["倉吉市"],
    "株式会社中国放送": ["三次市", "府中市"],
    "西日本放送株式会社": ["丸亀市", "東かがわ市"],
    "四国放送株式会社": ["徳島市", "三好市", "牟岐町", "美波町"],
    "株式会社大分放送": ["中津市"],
    "株式会社宮崎放送": ["都城市", "小林市", "日南市"],
}
AM_SUSPENDED_NOTE = (
    "総務省の特例措置による実証実験のためAM放送を運用休止中"
    "(休止期間は2026年9月30日まで。延長の手続きが進んでいるため、以降は各局の発表を確認)"
)

PURPOSE_KINDS = {
    "基幹放送用 中波放送": "am",
    "基幹放送用 超短波放送": "fm",
    "基幹放送用 超短波放送（外国語放送）": "foreign",
    "基幹放送用 超短波放送（コミュニティ放送）": "community",
    "基幹放送用 超短波放送（受信障害対策中継放送）": "gapfiller",
    "基幹放送用 超短波放送（臨時目的放送）": "temporary",
    # VICS(道路交通情報)のFM多重データ。音声放送ではないので取り込まない。
    "基幹放送用 超短波文字多重放送（有料放送を含む。）": None,
    "基幹放送用 短波放送": "sw",
    # NHKワールド・ラジオ日本(八俣送信所)。季節ごとの運用は eibi_import.py で取り込む。
    "基幹放送用 短波放送（国際放送）": None,
}

FREQ_RE = re.compile(r"^([\d.]+)\s*(kHz|MHz)$")
POWER_RE = re.compile(r"^([\d.]+)\s*(kW|mW|W)$")
CALLSIGN_RE = re.compile(r"\bJO[A-Z0-9]+(?:-FM)?\b")
PREFECTURE_RE = re.compile(r"^(北海道|東京都|京都府|大阪府|.{2,3}県)")


def norm(text):
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", text or "")).strip()


def prefecture_short(location):
    m = PREFECTURE_RE.match(location)
    if not m:
        raise ValueError(f"都道府県を判定できない所在地: {location}")
    pref = m.group(1)
    return pref if pref == "北海道" else pref[:-1]


def callsign_key(callsign):
    # 免許情報には英字の O と数字の 0 を取り違えたコールサインがある(例: JOZZ3C0-FM)
    return callsign.replace("O", "0")


def fetch(raw_dir):
    """API から全件を取得し、{band: [record, ...]} を返す。"""
    records = {}
    first = True
    for band, cond in QUERIES.items():
        records[band] = []
        start = 1
        while True:
            if not first:
                time.sleep(REQUEST_INTERVAL_SEC)
            first = False
            params = {"ST": "1", "OF": "2", "DA": "1", "SC": str(start), "DC": "2", "OW": "BC", **cond}
            req = urllib.request.Request(
                f"{API_URL}?{urllib.parse.urlencode(params)}",
                headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
            )
            with urllib.request.urlopen(req, timeout=120) as res:
                data = json.load(res)
            if raw_dir:
                (raw_dir / f"{band}_{start}.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
            info = data["musenInformation"]
            records[band].extend(data.get("musen") or [])
            total = int(info["totalCount"])
            print(f"  {band}: {len(records[band])}/{total} (更新日 {info['lastUpdateDate']})", file=sys.stderr)
            start += PAGE_SIZE
            if start > total:
                break
    return records, info["lastUpdateDate"]


def load_offline(raw_dir):
    records = {band: [] for band in QUERIES}
    last_update = None
    for band in QUERIES:
        for path in sorted(raw_dir.glob(f"{band}_*.json"), key=lambda p: int(p.stem.split("_")[1])):
            data = json.loads(path.read_text(encoding="utf-8"))
            records[band].extend(data.get("musen") or [])
            last_update = data["musenInformation"]["lastUpdateDate"]
    return records, last_update


def parse_spec(spec, band):
    """radioSpec1(電波の型式・周波数・空中線電力)を [(周波数kHz, 電力W or None), ...] にする。

    行の例: "F8E\\t82.5 MHz\\t10  kW" / " \\t \\t最大実効輻射電力 44  kW"(実効輻射電力は無視)
            "A3E\\t675 kHz\\t5  kW" の次行 " \\t \\t100   W"(同じ周波数の別の電力)
    """
    results = []
    last_freqs = []
    emission = ""
    in_erp = False  # 実効輻射電力の行の後に続く電力だけの行も実効輻射電力(例: JOAK-FM の 2か所目)
    for line in re.split(r"\\n|\n", spec):
        if "実効" in line:
            in_erp = True
            continue
        fields = [f.strip() for f in re.split(r"\\t|\t", line)]
        if fields[0]:
            emission = fields[0]
        freqs, power = [], None
        for field in fields[1:]:
            if m := FREQ_RE.match(field):
                value = float(m.group(1))
                freqs.append(value * 1000 if m.group(2) == "MHz" else value)
            elif m := POWER_RE.match(field):
                value = float(m.group(1))
                power = {"kW": value * 1000, "W": value, "mW": value / 1000}[m.group(2)]
        if "F2D" in emission:
            continue  # データ多重(音声放送ではない)
        if freqs:
            last_freqs = freqs
            in_erp = False
            results.extend((f, power) for f in freqs)
        elif power is not None and not in_erp:
            results.extend((f, power) for f in last_freqs)
    by_freq = {}
    for freq, power in results:
        freq = round(freq, 1)
        if freq not in by_freq or (power or 0) > (by_freq[freq] or 0):
            by_freq[freq] = power
    record_max = max((p for p in by_freq.values() if p), default=None)
    # 電力の記載が無い周波数(同じ送信所の別周波数)は送信所の最大電力で代用する
    return [(f, p if p else record_max) for f, p in by_freq.items()]


def equipment_locations(detail):
    """「無線設備の設置場所」の市区町村(重複を除き、書かれている順)。"""
    places = [p.strip() for p in re.split(r"\\n|\n", detail["radioEuipmentLocation"])]
    return list(dict.fromkeys(p for p in places if p))


# 演奏所を判別できない免許人のうち、先頭の設置場所が送信所ではないと確かめた局(コールサイン → 送信所の市区町村)
TRANSMITTER_OVERRIDES = {
    # Kiss FM KOBE: 本社(神戸市中央区)ではなく摩耶山送信所(灘区)(Wikipedia「兵庫エフエム放送」)
    "JOIV-FM": "兵庫県神戸市灘区",
    # e-radio: 空中線電力・実効輻射電力・方向別の指定が湖南市のNHK FM大津(JOQP-FM)と同一で、同じ送信所とみられる
    "JOUV-FM": "滋賀県湖南市",
}


def transmitter_locations(records):
    """免許ごとの送信所の所在地 {id(免許): 市区町村}。

    「無線設備の設置場所」には送信所のほかに演奏所(本社)や予備送信所が並び、どれが先に書かれるかは
    免許によって違う(例: MBSラジオ 1179kHz は 大阪市北区(本社・予備送信所)→高石市(送信所)→大阪市北区)。
    同じ免許人の、設置場所が複数ある免許すべてに現れる場所が1つだけあれば、それを演奏所とみなし、
    それ以外の最初の場所を送信所とする。演奏所を決められない免許人(NHKや、設置場所が複数ある免許が
    1件だけの免許人)は最初の場所を送信所とし、違うと確かめた局は TRANSMITTER_OVERRIDES で直す。
    """
    by_licensee = defaultdict(list)
    for recs in records.values():
        for rec in recs:
            by_licensee[norm(rec["detailInfo"]["name"])].append(rec)
    result = {}
    for recs in by_licensee.values():
        multi = [equipment_locations(r["detailInfo"]) for r in recs]
        multi = [places for places in multi if len(places) > 1]
        shared = set.intersection(*(set(places) for places in multi)) if len(multi) > 1 else set()
        studio = next(iter(shared)) if len(shared) == 1 else None
        for rec in recs:
            places = equipment_locations(rec["detailInfo"]) or [rec["listInfo"]["tdfkCd"]]
            signals = re.sub(r"\\[nt]", " ", rec["detailInfo"]["identificationSignals"])
            override = next((TRANSMITTER_OVERRIDES[c] for c in CALLSIGN_RE.findall(signals) if c in TRANSMITTER_OVERRIDES), None)
            if override is not None:
                if override not in places:
                    raise SystemExit(f"TRANSMITTER_OVERRIDES の所在地 {override} が免許情報の設置場所 {places} に無い")
                result[id(rec)] = override
            else:
                result[id(rec)] = next((p for p in places if p != studio), places[0])
    return result


def parse_records(records, coords):
    """免許情報を 1周波数=1件 の送信所リストにする。"""
    items = []
    unresolved = set()
    locations = transmitter_locations(records)
    for band, recs in records.items():
        for rec in recs:
            detail = rec["detailInfo"]
            purpose = detail["radioStationPurpose"]
            if purpose not in PURPOSE_KINDS:
                raise SystemExit(f"未知の無線局の目的: {purpose}(PURPOSE_KINDS に追加してください)")
            kind = PURPOSE_KINDS[purpose]
            if kind is None:
                continue
            location = locations[id(rec)]
            # 複数行の項目は改行が "\n" の2文字のまま入っているので、空白に置き換えてから探す
            signals = re.sub(r"\\[nt]", " ", detail["identificationSignals"])
            callsigns = CALLSIGN_RE.findall(signals)
            point = stationdata.locate(location, coords)
            if point is None:
                unresolved.add(location)
                continue
            for freq, power_w in parse_spec(detail["radioSpec1"], band):
                items.append({
                    "band": band,
                    "kind": kind,
                    "licensee": norm(detail["name"]),
                    "location": location,
                    "pref": prefecture_short(location),
                    "callsigns": callsigns,
                    "freq_khz": freq,
                    "power_kw": round(power_w / 1000, 4) if power_w else None,
                    "lat": point[0],
                    "lon": point[1],
                })
    if unresolved:
        raise SystemExit(
            f"座標が見つからない所在地: {sorted(unresolved)}"
            "(geo_import.py を再実行するか、stationdata.LOCATION_ALIASES に表記ゆれを追加してください)"
        )
    return items


def load_curated():
    curated = {}
    for filename in CURATED_FILES:
        path = FILES_DIR / filename
        curated[filename] = stationdata.load(path)
    return curated


def brand_of(item):
    """免許人・種別から (局名, 系列) を決める。コミュニティFM等はここでは扱わない。"""
    if item["licensee"] == "日本放送協会":
        return ("NHK AM" if item["band"] == "AM" else "NHK FM"), "NHK"
    if item["licensee"] not in LICENSEES:
        raise SystemExit(f"未登録の免許人: {item['licensee']}(LICENSEES に追加してください)")
    name, network = LICENSEES[item["licensee"]]
    if item["kind"] == "foreign" and name in FOREIGN_LANGUAGE_NAMES:
        name, network = FOREIGN_LANGUAGE_NAMES[name]
    name = REGIONAL_NAMES.get((name, item["pref"]), name)
    return name, network


def match_curated(items, curated):
    """キュレーション済みの局と免許情報を対応づける。1つの局には1つの送信所だけを対応させる。"""
    entries = [s for data in curated.values() for s in data["stations"]]
    by_callsign = defaultdict(list)
    for s in entries:
        if s["callsign"]:
            by_callsign[(s["band"], s["freq_khz"], callsign_key(s["callsign"]))].append(s)
    matched = {}  # id(entry) -> item

    # 1) コールサインと周波数が一致するもの
    for item in items:
        for cs in item["callsigns"]:
            for s in by_callsign.get((item["band"], item["freq_khz"], callsign_key(cs)), []):
                if id(s) not in matched:
                    matched[id(s)] = item
                    item["curated"] = s
    # 2) 局名・周波数・都道府県が一致するもの(免許情報でコールサインが伏せられている局など)
    for s in entries:
        if id(s) in matched or s["network"] == "community":
            continue
        candidates = [
            it for it in items
            if "curated" not in it and it["kind"] not in ("community", "gapfiller", "temporary")
            and it["band"] == s["band"] and it["freq_khz"] == s["freq_khz"]
            and (s["area"] or "").startswith(it["pref"]) and brand_of(it)[0] == s["name"]
        ]
        if candidates:
            best = max(candidates, key=lambda it: it["power_kw"] or 0)
            matched[id(s)] = best
            best["curated"] = s
    return entries, matched


def report_curated(entries, matched):
    problems = 0
    for s in entries:
        item = matched.get(id(s))
        if item is None:
            print(f"[要確認] 免許情報に見当たらない: {s['id']} {s['name']} {s['band']} {s['freq_khz']}", file=sys.stderr)
            problems += 1
            continue
        if s["callsign"] and item["callsigns"] and callsign_key(s["callsign"]) not in map(callsign_key, item["callsigns"]):
            print(f"[要確認] コールサイン不一致: {s['id']} {s['callsign']} / 免許 {item['callsigns']}", file=sys.stderr)
            problems += 1
        if not s["callsign"] and item["callsigns"]:
            print(f"[要確認] コールサイン未記載: {s['id']} {s['name']} → 免許 {item['callsigns']}", file=sys.stderr)
            problems += 1
        if is_suspended(item) and "休止" not in (s["note"] or ""):
            print(f"[要確認] AM運用休止中だが注記に無い: {s['id']} {s['name']}", file=sys.stderr)
            problems += 1
    return problems


def community_names(items, curated):
    """コミュニティFMの免許人 → [(周波数, 局名), ...](親局のコールサインで対応づける)。"""
    by_callsign = {
        callsign_key(s["callsign"]): s
        for s in curated["community_fm_stations.json"]["stations"] if s["callsign"]
    }
    names = defaultdict(list)
    for item in items:
        if item["kind"] != "community":
            continue
        for cs in item["callsigns"]:
            s = by_callsign.get(callsign_key(cs))
            if s:
                names[item["licensee"]].append((s["freq_khz"], s["name"], s["network"]))
    return names


def main_am_frequencies(items):
    """局名 → 最大電力のAM送信所の周波数(FM補完中継局の注記用)。"""
    best = {}
    for item in items:
        if item["band"] != "AM":
            continue
        name = brand_of(item)[0]
        if name not in best or (item["power_kw"] or 0) > (best[name]["power_kw"] or 0):
            best[name] = item
    return {name: item["freq_khz"] for name, item in best.items()}


def is_suspended(item):
    places = AM_SUSPENDED.get(item["licensee"], [])
    return item["band"] == "AM" and any(item["location"].endswith(p) for p in places)


def generate(items, curated, last_update):
    # NHK は AM と FM を別の放送として持つので、NHK FM は FM補完中継局ではない
    am_licensees = {it["licensee"] for it in items if it["band"] == "AM"} - {"日本放送協会"}
    main_am = main_am_frequencies(items)
    community = community_names(items, curated)
    stations = []
    pending_gapfillers = []

    for item in items:
        if "curated" in item:
            continue
        kind = item["kind"]
        callsign = item["callsigns"][0] if item["callsigns"] else None
        notes = []
        if kind in ("am", "fm", "foreign", "sw"):
            name, network = brand_of(item)
            if item["band"] == "FM" and item["licensee"] in am_licensees:
                am_freq = main_am.get(name)
                notes.append(f"FM補完中継局(ワイドFM)。AM {am_freq:g}kHzと同じ放送" if am_freq else "FM補完中継局(ワイドFM)")
            elif not callsign:
                notes.append("中継局")
        elif kind == "community":
            candidates = community.get(item["licensee"])
            if not candidates:
                raise SystemExit(f"コミュニティFMの局名を決められない: {item['licensee']} {item['freq_khz']} {item['location']}")
            same_freq = [c for c in candidates if c[0] == item["freq_khz"]]
            _, name, network = (same_freq or candidates)[0]
            if not callsign:
                notes.append("中継局")
        elif kind == "temporary":
            name, network = f"臨時災害放送局({item['licensee']})", None
            notes.append(f"臨時目的放送(災害時などに開設される臨時の放送局)。免許人: {item['licensee']}")
        elif kind == "gapfiller":
            pending_gapfillers.append(item)
            continue
        else:
            raise AssertionError(kind)
        if is_suspended(item):
            notes.append(AM_SUSPENDED_NOTE)
        stations.append({"item": item, "name": name, "network": network, "callsign": callsign, "notes": notes})

    # 受信障害対策中継局: 難聴地域向けに自治体等が既存局を同じ周波数で再送信する中継局。
    # 同じ市区町村(なければ同じ都道府県)の同一周波数の局を中継元とみなす。
    known = [(s["band"], s["freq_khz"], s["area"], s["name"], s["network"])
             for data in curated.values() for s in data["stations"]]
    known += [(st["item"]["band"], st["item"]["freq_khz"], st["item"]["location"], st["name"], st["network"])
              for st in stations]
    for item in pending_gapfillers:
        same = [k for k in known if k[0] == item["band"] and k[1] == item["freq_khz"]]
        local = [k for k in same if k[2] == item["location"]]
        sources = local or [k for k in same if (k[2] or "").startswith(item["pref"])]
        if len({k[3] for k in sources}) == 1:
            name, network = sources[0][3], sources[0][4]
            notes = [f"受信障害対策中継局。{name}を難聴地域向けに同じ周波数で再送信(免許人: {item['licensee']})"]
        else:
            name, network = "受信障害対策中継局", None
            notes = [f"難聴地域向けに既存の放送を再送信する中継局(免許人: {item['licensee']})。中継元の放送局は免許情報からは特定できない"]
        stations.append({"item": item, "name": name, "network": network, "callsign": None, "notes": notes})

    # 同じ市区町村・同一周波数・同じ局名の送信所は1件にまとめる。
    # キュレーション済みの局と市区町村・周波数・局名まで同じもの(同一周波数の補完送信所)は載せない。
    curated_keys = {(s["band"], s["freq_khz"], s["name"], s["area"])
                    for data in curated.values() for s in data["stations"]}
    grouped = defaultdict(list)
    for st in stations:
        it = st["item"]
        grouped[(it["band"], it["freq_khz"], st["name"], it["location"])].append(st)
    output = []
    for (band, freq, name, location), group in grouped.items():
        if (band, freq, name, location) in curated_keys:
            continue
        head = max(group, key=lambda st: st["item"]["power_kw"] or 0)
        notes = list(head["notes"])
        if len(group) > 1:
            notes.append(f"同じ市区町村内に同一周波数の送信所が{len(group)}か所")
        digest = hashlib.sha1(f"{band}|{freq:g}|{name}|{location}".encode()).hexdigest()[:10]
        output.append(stationdata.station(
            id=f"mic-{band.lower()}-{digest}",
            band=band,
            freq_khz=freq,
            name=name,
            area=location,
            callsign=next((st["callsign"] for st in group if st["callsign"]), None),
            network=head["network"],
            note="。".join(notes) or None,
            power_kw=head["item"]["power_kw"],
            lat=head["item"]["lat"],
            lon=head["item"]["lon"],
        ))
    output.sort(key=lambda s: (s["band"], s["freq_khz"], s["area"], s["name"]))
    ids = [s["id"] for s in output]
    assert len(ids) == len(set(ids)), "id が重複している"
    return {
        "_readme": (
            "総務省 電波利用ポータルの無線局等情報検索 Web-API から生成した国内の放送局データ"
            f"(免許情報の更新日 {last_update})。キュレーション済みファイル"
            "(jp_am_fm_stations.json・community_fm_stations.json・jp_sw_stations.json)に無い送信所"
            "(NHK・民放の中継局、FM補完中継局、コミュニティFMの中継局、受信障害対策中継局など)を収録。"
            "files/scripts/mic_import.py で再生成するため手で編集しないこと。"
            "このデータは総務省 電波利用ポータルのWeb-API機能を利用して取得した情報をもとに作成しているが、"
            "内容は総務省によって保証されたものではない。"
        ),
        "_schema_version": 4,
        "_source_updated": last_update,
        "stations": output,
    }


def sync_curated(curated, matched):
    """キュレーション済みファイルの power_kw・lat・lon を免許情報(空中線電力・送信所の所在地)で更新する。"""
    for filename, data in curated.items():
        changed = False
        new_stations = []
        for s in data["stations"]:
            item = matched.get(id(s))
            licensed = {k: item[k] for k in ("power_kw", "lat", "lon")} if item else {}
            entry = stationdata.station(**{**s, **licensed})
            changed |= entry != s
            new_stations.append(entry)
        if changed:
            data["stations"] = new_stations
            stationdata.save(FILES_DIR / filename, data)
            print(f"  {filename}: power_kw・lat・lon を更新", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--raw-dir", type=Path, help="取得した生データ(JSON)の保存先")
    parser.add_argument("--offline", type=Path, help="保存済みの生データから生成する(API にアクセスしない)")
    args = parser.parse_args()

    if args.offline:
        records, last_update = load_offline(args.offline)
    else:
        if args.raw_dir:
            args.raw_dir.mkdir(parents=True, exist_ok=True)
        print("総務省 Web-API から取得中...", file=sys.stderr)
        records, last_update = fetch(args.raw_dir)

    items = parse_records(records, stationdata.load_municipality_coords())
    curated = load_curated()
    entries, matched = match_curated(items, curated)
    problems = report_curated(entries, matched)
    data = generate(items, curated, last_update)
    stationdata.save(FILES_DIR / OUTPUT_FILE, data)
    sync_curated(curated, matched)

    counts = defaultdict(int)
    for s in data["stations"]:
        counts[s["band"]] += 1
    print(f"{OUTPUT_FILE}: {len(data['stations'])}件 {dict(counts)} / 要確認 {problems}件", file=sys.stderr)


if __name__ == "__main__":
    main()
