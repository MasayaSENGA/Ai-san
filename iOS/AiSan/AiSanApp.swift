import SwiftUI
import CoreLocation
import SQLite3

@main
struct AiSanApp: App {
    var body: some Scene { WindowGroup { ContentView() } }
}

struct Station: Identifiable {
    let id: String
    let band: String
    let frequency: Double?
    let name: String
    let area: String?
    let callsign: String?
    let network: String?
    let language: String?
    let targetArea: String?
    let note: String?
    let power: Double?
    let latitude: Double?
    let longitude: Double?
    let prefecture: String?
    let category: String
    let role: String?

    var categoryLabel: String {
        switch category {
        case "nhk": "NHK"; case "commercial": "民放"; case "community": "コミュニティFM"
        case "afn": "AFN(在日米軍)"; case "international": "国際放送"; default: "その他"
        }
    }
    func distance(from point: CLLocation) -> Double? {
        guard let latitude, let longitude else { return nil }
        return point.distance(from: CLLocation(latitude: latitude, longitude: longitude)) / 1000
    }
}

final class StationStore {
    static let shared = StationStore()
    private var db: OpaquePointer?
    private(set) var stations: [Station] = []
    private init() {
        guard let url = Bundle.main.url(forResource: "radio_stations", withExtension: "db", subdirectory: "Database"),
              sqlite3_open_v2(url.path, &db, SQLITE_OPEN_READONLY, nil) == SQLITE_OK else { return }
        var statement: OpaquePointer?
        let sql = "SELECT id,band,freq_khz,name,area,callsign,network,language,target_area,note,power_kw,lat,lon,prefecture,category,role FROM stations"
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(statement) }
        while sqlite3_step(statement) == SQLITE_ROW {
            func string(_ i: Int32) -> String? { sqlite3_column_type(statement, i) == SQLITE_NULL ? nil : String(cString: sqlite3_column_text(statement, i)) }
            func number(_ i: Int32) -> Double? { sqlite3_column_type(statement, i) == SQLITE_NULL ? nil : sqlite3_column_double(statement, i) }
            stations.append(Station(id: string(0) ?? UUID().uuidString, band: string(1) ?? "", frequency: number(2), name: string(3) ?? "", area: string(4), callsign: string(5), network: string(6), language: string(7), targetArea: string(8), note: string(9), power: number(10), latitude: number(11), longitude: number(12), prefecture: string(13), category: string(14) ?? "other", role: string(15)))
        }
    }
    func search(band: String, khz: Double, tolerance: Double) -> [Station] {
        stations.filter { $0.band == band && ($0.frequency ?? .infinity) >= khz - tolerance && ($0.frequency ?? -.infinity) <= khz + tolerance }
            .sorted { a, b in
                let da = abs((a.frequency ?? khz) - khz), db = abs((b.frequency ?? khz) - khz)
                if da != db { return da < db }; if a.power != b.power { return (a.power ?? -1) > (b.power ?? -1) }; return a.name < b.name
            }
    }
}

final class LocationModel: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var location: CLLocation?
    @Published var message: String?
    private let manager = CLLocationManager()
    override init() { super.init(); manager.delegate = self; manager.desiredAccuracy = kCLLocationAccuracyKilometer }
    func request() {
        guard CLLocationManager.locationServicesEnabled() else { message = "端末の位置情報がオフです"; return }
        if manager.authorizationStatus == .notDetermined { manager.requestWhenInUseAuthorization() }
        else if manager.authorizationStatus == .authorizedAlways || manager.authorizationStatus == .authorizedWhenInUse { manager.requestLocation() }
        else { message = "位置情報の利用が許可されていません" }
    }
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager.authorizationStatus == .authorizedAlways || manager.authorizationStatus == .authorizedWhenInUse { manager.requestLocation() }
    }
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) { location = locations.last; message = nil }
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) { message = "現在地を取得できませんでした" }
}

struct ContentView: View {
    var body: some View {
        TabView {
            SearchView().tabItem { Label("周波数で検索", systemImage: "magnifyingglass") }
            BrowseView().tabItem { Label("放送局一覧", systemImage: "list.bullet") }
        }.tint(.indigo)
    }
}

struct SearchView: View {
    @State private var band = "FM"
    @State private var input = ""
    @State private var results: [Station] = []
    @State private var searched = false
    @State private var sortNear = false
    @StateObject private var location = LocationModel()
    private let bands = ["AM", "FM", "SW"]
    private var unit: String { band == "FM" ? "MHz" : "kHz" }
    private var tolerance: Double { band == "AM" ? 1 : band == "FM" ? 50 : 5 }
    private var frequency: Double? { Double(input.trimmingCharacters(in: .whitespaces)).map { band == "FM" ? $0 * 1000 : $0 } }
    private var ordered: [Station] {
        guard sortNear, let point = location.location else { return results }
        return results.sorted { ($0.distance(from: point) ?? .infinity) < ($1.distance(from: point) ?? .infinity) }
    }
    var body: some View {
        NavigationStack {
            VStack(spacing: 14) {
                Picker("バンド", selection: $band) { Text("AM").tag("AM"); Text("FM").tag("FM"); Text("短波").tag("SW") }.pickerStyle(.segmented)
                    .onChange(of: band) { _, _ in results = []; searched = false }
                HStack {
                    Text("周波数 (\(unit))").foregroundStyle(.secondary)
                    TextField(band == "FM" ? "例: 82.5" : band == "AM" ? "例: 954" : "例: 9750", text: $input)
                        .keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                }.padding(12).background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
                Button(action: search) { Label("検索", systemImage: "magnifyingglass").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent)
                Toggle("現在地から近い順に並べる", isOn: $sortNear).onChange(of: sortNear) { _, enabled in if enabled { location.request() } }
                if let message = location.message { Text(message).font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading) }
                if searched && results.isEmpty { ContentUnavailableView("放送局が見つかりません", systemImage: "antenna.radiowaves.left.and.right", description: Text("入力した周波数の近くに該当する局はありません")) }
                List {
                    ForEach(ordered) { station in StationRow(station: station, band: band, location: location.location) }
                    Section { DataNotice() }
                }.listStyle(.plain)
            }.padding(.horizontal).padding(.top, 12).navigationTitle("Ai-san")
        }
    }
    private func search() {
        guard let frequency else { results = []; searched = false; return }
        results = StationStore.shared.search(band: band, khz: frequency, tolerance: tolerance); searched = true
        if sortNear { location.request() }
    }
}

struct BrowseView: View {
    @State private var keyword = ""
    @State private var category = "すべて"
    @State private var band = "すべて"
    @State private var area = "すべて"
    @State private var transmitter = "すべて"
    @State private var network = "すべて"
    @State private var language = "すべて"
    @State private var sort = "都道府県順"
    @StateObject private var location = LocationModel()
    private let categories = ["すべて", "NHK", "民放", "コミュニティFM", "AFN(在日米軍)", "国際放送", "その他"]
    private let prefectures = ["すべて", "北海道", "青森県", "岩手県", "宮城県", "秋田県", "山形県", "福島県", "茨城県", "栃木県", "群馬県", "埼玉県", "千葉県", "東京都", "神奈川県", "新潟県", "富山県", "石川県", "福井県", "山梨県", "長野県", "岐阜県", "静岡県", "愛知県", "三重県", "滋賀県", "京都府", "大阪府", "兵庫県", "奈良県", "和歌山県", "鳥取県", "島根県", "岡山県", "広島県", "山口県", "徳島県", "香川県", "愛媛県", "高知県", "福岡県", "佐賀県", "長崎県", "熊本県", "大分県", "宮崎県", "鹿児島県", "沖縄県"]
    private let sortOptions = ["都道府県順", "局名順", "出力の大きい順", "現在地から近い順"]
    private let regionMap: [(String, [String])] = [
        ("北海道地方", ["北海道"]), ("東北地方", ["青森県", "岩手県", "宮城県", "秋田県", "山形県", "福島県"]),
        ("関東地方", ["茨城県", "栃木県", "群馬県", "埼玉県", "千葉県", "東京都", "神奈川県"]),
        ("中部地方", ["新潟県", "富山県", "石川県", "福井県", "山梨県", "長野県", "岐阜県", "静岡県", "愛知県"]),
        ("近畿地方", ["三重県", "滋賀県", "京都府", "大阪府", "兵庫県", "奈良県", "和歌山県"]),
        ("中国地方", ["鳥取県", "島根県", "岡山県", "広島県", "山口県"]),
        ("四国地方", ["徳島県", "香川県", "愛媛県", "高知県"]),
        ("九州・沖縄地方", ["福岡県", "佐賀県", "長崎県", "熊本県", "大分県", "宮崎県", "鹿児島県", "沖縄県"])
    ]
    private var areaOptions: [String] { ["すべて"] + regionMap.filter { $0.1.count > 1 }.map { $0.0 + "すべて" } + prefectures.dropFirst() }
    private var languages: [String] {
        Array(Set(StationStore.shared.stations.filter { $0.category == "international" }.flatMap { ($0.language ?? "").components(separatedBy: "、") }.filter { !$0.isEmpty })).sorted()
    }
    private var areaPrefectures: [String]? {
        if area == "すべて" { return nil }
        if let region = regionMap.first(where: { area == $0.0 + "すべて" }) { return region.1 }
        return [area]
    }
    private var filtered: [Station] {
        let tokens = keyword.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "ja_JP")).split(whereSeparator: \.isWhitespace).map(String.init)
        let list = StationStore.shared.stations.filter { station in
            let catOk = category == "すべて" || station.categoryLabel == category
            let bandOk = band == "すべて" || station.band == band
            let areaOk = areaPrefectures == nil || areaPrefectures!.contains(station.prefecture ?? "")
            let transmitterOk = transmitter == "すべて" || (transmitter == "中継局を除く" ? (station.role == nil || station.role == "main") : station.role == "fm_complement")
            let networkTokens = (station.network ?? "").split(separator: "/").map(String.init)
            let networkOk = network == "すべて" || (network == "独立局" ? station.network == "independent" : networkTokens.contains(network))
            let languageOk = language == "すべて" || (station.language ?? "").components(separatedBy: "、").contains(language)
            let haystack = [station.name, station.area, station.callsign, station.network, station.language, station.targetArea, station.note, station.prefecture].compactMap{$0}.joined(separator: " ").folding(options: [.caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "ja_JP"))
            return catOk && bandOk && areaOk && transmitterOk && networkOk && languageOk && tokens.allSatisfy { haystack.localizedStandardContains($0) }
        }
        switch sort {
        case "局名順": return list.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        case "出力の大きい順": return list.sorted { ($0.power ?? -1) > ($1.power ?? -1) }
        case "現在地から近い順":
            guard let point = location.location else { return list.sorted { ($0.prefecture ?? "ン").localizedStandardCompare($1.prefecture ?? "ン") == .orderedAscending } }
            return list.sorted { ($0.distance(from: point) ?? .infinity) < ($1.distance(from: point) ?? .infinity) }
        default:
            return list.sorted {
                let a = prefectures.firstIndex(of: $0.prefecture ?? "") ?? prefectures.count
                let b = prefectures.firstIndex(of: $1.prefecture ?? "") ?? prefectures.count
                if a != b { return a < b }
                return ($0.frequency ?? .infinity) < ($1.frequency ?? .infinity)
            }
        }
    }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    TextField("局名・地域・コールサイン・言語など", text: $keyword)
                    Picker("種類", selection: $category) { ForEach(categories, id: \.self) { Text($0) } }
                        .onChange(of: category) { _, value in
                            if value == "国際放送" { area = "すべて"; transmitter = "すべて" }
                            if value != "民放" && value != "すべて" { network = "すべて" }
                            if value != "国際放送" { language = "すべて" }
                        }
                    Picker("バンド", selection: $band) { ForEach(["すべて", "AM", "FM", "SW"], id: \.self) { Text($0 == "SW" ? "短波" : $0) } }
                    Picker("地域", selection: $area) { ForEach(areaOptions, id: \.self) { Text($0) } }
                    if category != "国際放送" {
                        Picker("送信所", selection: $transmitter) { ForEach(["すべて", "中継局を除く", "ワイドFM(FM補完中継局)のみ"], id: \.self) { Text($0) } }
                    }
                    if category == "すべて" || category == "民放" {
                        Picker("系列", selection: $network) { ForEach(["すべて", "JRN", "NRN", "JFN", "JFL", "独立局"], id: \.self) { Text($0) } }
                    }
                    if category == "国際放送" {
                        Picker("言語", selection: $language) { ForEach(["すべて"] + languages, id: \.self) { Text($0) } }
                    }
                    Picker("並び順", selection: $sort) { ForEach(sortOptions, id: \.self) { Text($0) } }.onChange(of: sort) { _, value in if value == "現在地から近い順" { location.request() } }
                    if let message = location.message, sort == "現在地から近い順" { Text(message).font(.caption).foregroundStyle(.secondary) }
                    Text("\(filtered.count)局").font(.caption).foregroundStyle(.secondary)
                }
                if sort == "都道府県順" {
                    let groups = Dictionary(grouping: filtered) { $0.prefecture ?? "国際放送" }
                    ForEach(groups.keys.sorted { a, b in
                        let ai = prefectures.firstIndex(of: a) ?? prefectures.count
                        let bi = prefectures.firstIndex(of: b) ?? prefectures.count
                        return ai == bi ? a < b : ai < bi
                    }, id: \.self) { key in
                        Section(header: Text("\(key) ・ \(groups[key]?.count ?? 0)局")) {
                            ForEach(groups[key] ?? []) { station in StationRow(station: station, band: station.band, location: nil) }
                        }
                    }
                } else {
                    ForEach(filtered) { station in StationRow(station: station, band: station.band, location: sort == "現在地から近い順" ? location.location : nil) }
                }
                Section { DataNotice() }
            }.listStyle(.insetGrouped).navigationTitle("放送局一覧")
        }
    }
}

struct StationRow: View {
    let station: Station
    let band: String
    var location: CLLocation?
    private var frequency: String {
        guard let value = station.frequency else { return "-" }
        return band == "FM" ? String(format: "%.1f MHz", value / 1000) : String(format: "%.0f kHz", value)
    }
    private var power: String? {
        guard let value = station.power else { return nil }
        let shown = value >= 1 ? value : value * 1000
        return "出力 \(String(format: "%.3g", shown))\(value >= 1 ? "kW" : "W")"
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(station.name).font(.headline)
            HStack(spacing: 5) {
                Text(frequency)
                if let distance = location.flatMap(station.distance(from:)) { Text("・現在地から約\(Int(distance.rounded()))km") }
            }.font(.subheadline)
            Text([station.area, station.callsign, station.network.map { $0 == "independent" ? "独立局" : $0 }, power].compactMap{$0}.joined(separator: " ・ ")).font(.caption).foregroundStyle(.secondary)
            if let note = station.note, !note.isEmpty { Text(note).font(.caption).foregroundStyle(.secondary) }
        }.padding(.vertical, 3)
    }
}

struct DataNotice: View {
    var body: some View {
        Text("出典: 総務省 電波利用ポータル、EiBi Shortwave Schedules、Wikipedia、Wikidata。このサービスは、総務省 電波利用ポータルのWeb-API機能を利用して取得した情報をもとに作成していますが、サービスの内容は総務省によって保証されたものではありません。")
            .font(.caption2).foregroundStyle(.secondary).listRowSeparator(.hidden)
    }
}
