import groovy.transform.Field

/*
 * Space Weather Watch (NOAA)
 *
 * Monitors public NOAA Space Weather Prediction Center products and exposes
 * geomagnetic activity, NOAA scales, forecasts and official messages to
 * Hubitat automations. No API key or companion service required.
 *
 * Author: Jon Wallace
 * Copyright 2026 Jon Wallace
 * License: MIT
 * Version: 1.0.0
 */

metadata {
  definition(
    name: "Space Weather Watch (NOAA)",
    namespace: "jonw",
    author: "Jon Wallace",
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/b69ca/hubitat-spaceweatherwatchnoaa/main/SpaceWeatherWatchNOAA.groovy"
  ) {
    capability "Sensor"
    capability "Refresh"
    capability "Initialize"
    capability "PushableButton"

    command "clearMessageHistory"
    command "testActivityEvent"
    command "testStormEvent"
    command "testMessageEvent"

    attribute "watchStatus", "enum", ["setup", "updating", "ready", "partial", "error"]
    attribute "spaceWeatherState", "enum", ["quiet", "unsettled", "storm", "severe"]
    attribute "lastChecked", "string"
    attribute "lastSuccessfulCheck", "string"
    attribute "lastError", "string"
    attribute "dataAgeMinutes", "number"

    attribute "currentKp", "number"
    attribute "currentKpTime", "string"
    attribute "currentKpSource", "enum", ["observed", "estimated", "unknown"]
    attribute "currentGScale", "string"
    attribute "currentSScale", "string"
    attribute "currentRScale", "string"
    attribute "currentGText", "string"
    attribute "currentSText", "string"
    attribute "currentRText", "string"
    attribute "maximumCurrentScale", "number"

    attribute "forecastKpPeak", "number"
    attribute "forecastKpPeakTime", "string"
    attribute "forecastGScale", "string"
    attribute "forecastGToday", "string"
    attribute "forecastGTomorrow", "string"
    attribute "forecastGDayThree", "string"
    attribute "forecastSummary", "string"

    attribute "auroraActivity", "enum", ["low", "active", "storm", "high", "extreme"]
    attribute "forecastAuroraActivity", "enum", ["low", "active", "storm", "high", "extreme"]
    attribute "solarWindSpeed", "number"
    attribute "interplanetaryBz", "number"
    attribute "magneticFieldStrength", "number"
    attribute "solarRadioFlux", "number"

    attribute "messageCount", "number"
    attribute "latestMessageId", "string"
    attribute "latestMessageType", "enum", ["watch", "warning", "alert", "summary", "unknown"]
    attribute "latestMessageIssued", "string"
    attribute "latestMessageTitle", "string"
    attribute "latestMessageText", "string"

    attribute "conditionSummary", "string"
    attribute "notificationText", "string"
    attribute "notificationType", "enum", ["activity", "storm", "message", "test"]
  }

  preferences {
    input name: "setupNotes", type: "paragraph", title: "Space Weather Watch 1.0.0",
      description: "Downloads public NOAA SWPC space-weather products. No account, API key, location or personal information is sent."
    input name: "pollMinutes", type: "enum", title: "Automatic check interval", options: [
      "5":"5 minutes", "10":"10 minutes", "15":"15 minutes", "30":"30 minutes", "60":"1 hour"], defaultValue: "15"
    input name: "forecastHours", type: "enum", title: "Kp forecast window", options: [
      "12":"12 hours", "24":"24 hours", "48":"48 hours", "72":"72 hours"], defaultValue: "24"
    input name: "activityKp", type: "enum", title: "Activity-event Kp threshold", options: [
      "3":"Kp 3 - unsettled", "4":"Kp 4 - active", "5":"Kp 5 - G1 storm", "6":"Kp 6 - G2 storm",
      "7":"Kp 7 - G3 storm", "8":"Kp 8 - G4 storm", "9":"Kp 9 - G5 storm"], defaultValue: "4"
    input name: "stormScale", type: "enum", title: "NOAA-scale event threshold", options: [
      "1":"Level 1 - minor", "2":"Level 2 - moderate", "3":"Level 3 - strong",
      "4":"Level 4 - severe", "5":"Level 5 - extreme"], defaultValue: "1"
    input name: "includeForecastActivity", type: "bool", title: "Activity events may use the Kp forecast", defaultValue: true
    input name: "notifyOnlyOnIncrease", type: "bool", title: "Notify only when a threshold is newly crossed or severity increases", defaultValue: true

    input name: "messageNotes", type: "paragraph", title: "Official SWPC messages",
      description: "Button 3 represents a newly issued NOAA watch, warning or alert. Select which message classes should be considered."
    input name: "monitorMessages", type: "bool", title: "Monitor official SWPC messages", defaultValue: true
    input name: "includeWatches", type: "bool", title: "Include watches", defaultValue: true
    input name: "includeWarnings", type: "bool", title: "Include warnings", defaultValue: true
    input name: "includeAlerts", type: "bool", title: "Include alerts", defaultValue: true
    input name: "messageLookbackHours", type: "enum", title: "Message lookback", options: [
      "6":"6 hours", "12":"12 hours", "24":"24 hours", "48":"48 hours", "72":"72 hours"], defaultValue: "24"

    input name: "automationNotes", type: "paragraph", title: "Automation behavior",
      description: "Button 1: Kp activity threshold. Button 2: NOAA G, S or R scale threshold. Button 3: new official SWPC message. When several happen together, the driver emits the most important one: scale, then message, then activity."
    input name: "notifyInitialState", type: "bool", title: "Notify for the most important condition on the first successful check", defaultValue: false
    input name: "keepStateHours", type: "enum", title: "Keep activity state elevated for", options: [
      "1":"1 hour", "3":"3 hours", "6":"6 hours", "12":"12 hours", "24":"24 hours"], defaultValue: "6"
    input name: "collectSolarWind", type: "bool", title: "Collect solar-wind and 10.7 cm radio-flux measurements", defaultValue: true
    input name: "logEnable", type: "bool", title: "Debug logging (automatically disables after 30 minutes)", defaultValue: false
  }
}

@Field static final String BASE_URL = "https://services.swpc.noaa.gov"
@Field static final Map SCALE_WORDS = [0:"none", 1:"minor", 2:"moderate", 3:"strong", 4:"severe", 5:"extreme"]

def installed() {
  unschedule()
  state.generation = (state.generation ?: 0L) + 1L
  state.inFlight = null
  state.configured = false
  sendEvent(name: "numberOfButtons", value: 3)
  sendChanged("watchStatus", "setup")
}

def updated() {
  state.configured = true
  initialize()
}

def uninstalled() {
  unschedule()
}

def initialize() {
  unschedule()
  state.configured = true
  state.generation = (state.generation ?: 0L) + 1L
  state.inFlight = null
  sendEvent(name: "numberOfButtons", value: 3)
  sendChanged("watchStatus", "updating")
  configureSchedule()
  if (settings?.logEnable) runIn(1800, "logsOff")
  refresh()
}

def configureSchedule() {
  int minutes = settingNumber("pollMinutes", 15d, 5d, 60d).intValue()
  if (minutes == 60) runEvery1Hour("refresh")
  else schedule("0 */${minutes} * ? * *", "refresh")
}

def logsOff() {
  device.updateSetting("logEnable", [value: "false", type: "bool"])
}

def refresh() {
  if (state.configured != true) {
    sendChanged("watchStatus", "setup")
    return
  }

  if (state.inFlight) {
    if (now() - ((state.requestStarted ?: 0L) as Long) < 180000L) return
    state.inFlight = null
    recordError("The previous NOAA update did not complete")
  }

  long started = now()
  String token = "${started}-${state.generation}"
  state.inFlight = token
  state.requestStarted = started
  state.batch = [token: token, values: [:], errors: [], successes: 0]
  sendChanged("lastChecked", localText(started))
  sendChanged("watchStatus", "updating")
  if (settings?.logEnable) log.debug "Starting NOAA SWPC update"
  requestProduct("scalesResponse", "/products/noaa-scales.json", token)
}

private void requestProduct(String callback, String path, String token) {
  try {
    asynchttpGet(callback, [
      uri: "${BASE_URL}${path}",
      contentType: "application/json",
      timeout: 20,
      headers: ["User-Agent": "Hubitat-SpaceWeatherWatch/1.0 (github.com/b69ca/hubitat-spaceweatherwatchnoaa)"]
    ], [token: token])
  } catch (Exception e) {
    addBatchError("${path}: ${e.message}")
    continueAfter(callback, token)
  }
}

def scalesResponse(resp, data) {
  if (!validCallback(data)) return
  try {
    Map body = responseMap(resp, "NOAA scales")
    Map current = body["0"] instanceof Map ? body["0"] : null
    if (!current) throw new IllegalArgumentException("Current scales are missing")

    Map values = batchValues()
    values.currentG = scaleValue(current.G?.Scale)
    values.currentS = scaleValue(current.S?.Scale)
    values.currentR = scaleValue(current.R?.Scale)
    values.currentGText = safeText(current.G?.Text, 80) ?: scaleWord(values.currentG as Integer)
    values.currentSText = safeText(current.S?.Text, 80) ?: scaleWord(values.currentS as Integer)
    values.currentRText = safeText(current.R?.Text, 80) ?: scaleWord(values.currentR as Integer)
    values.scaleTime = parseUtc("${current.DateStamp}T${current.TimeStamp}")
    ["1", "2", "3"].each { key ->
      Map day = body[key] instanceof Map ? body[key] : [:]
      values["forecastG${key}"] = scaleValue(day.G?.Scale)
    }
    batchSuccess()
  } catch (Exception e) {
    addBatchError("NOAA scales: ${e.message}")
  }
  requestProduct("kpResponse", "/products/noaa-planetary-k-index-forecast.json", data.token)
}

def kpResponse(resp, data) {
  if (!validCallback(data)) return
  try {
    List body = responseList(resp, "planetary Kp")
    long current = now()
    long forecastEnd = current + settingNumber("forecastHours", 24d, 12d, 72d).longValue() * 3600000L
    List parsed = body.collect { row -> parseKpRow(row) }.findAll { it }
    List actual = parsed.findAll { it.time <= current + 3600000L && it.source != "predicted" }
    List future = parsed.findAll { it.time >= current - 3600000L && it.time <= forecastEnd && it.source == "predicted" }
    if (!actual) throw new IllegalArgumentException("No current Kp measurement")

    Map latest = actual.max { it.time }
    Map peak = future ? future.max { it.kp } : latest
    Map values = batchValues()
    values.currentKp = latest.kp
    values.currentKpTime = latest.time
    values.currentKpSource = latest.source
    values.forecastKpPeak = peak.kp
    values.forecastKpPeakTime = peak.time
    batchSuccess()
  } catch (Exception e) {
    addBatchError("Planetary Kp: ${e.message}")
  }

  if (settings?.collectSolarWind == false) {
    if (settings?.monitorMessages == false) finishBatch(data.token)
    else requestProduct("messagesResponse", "/products/alerts.json", data.token)
  } else {
    requestProduct("windResponse", "/products/summary/solar-wind-speed.json", data.token)
  }
}

def windResponse(resp, data) {
  if (!validCallback(data)) return
  try {
    List body = responseList(resp, "solar-wind speed")
    Map row = body ? body[-1] : null
    Double speed = finiteNumber(row?.proton_speed)
    if (speed == null) throw new IllegalArgumentException("Measurement is missing")
    batchValues().solarWindSpeed = speed
    batchValues().windTime = parseUtc(row.time_tag)
    batchSuccess()
  } catch (Exception e) {
    addBatchError("Solar-wind speed: ${e.message}")
  }
  requestProduct("fieldResponse", "/products/summary/solar-wind-mag-field.json", data.token)
}

def fieldResponse(resp, data) {
  if (!validCallback(data)) return
  try {
    List body = responseList(resp, "solar-wind magnetic field")
    Map row = body ? body[-1] : null
    Double bt = finiteNumber(row?.bt)
    Double bz = finiteNumber(row?.bz_gsm)
    if (bt == null && bz == null) throw new IllegalArgumentException("Measurements are missing")
    batchValues().magneticFieldStrength = bt
    batchValues().interplanetaryBz = bz
    batchSuccess()
  } catch (Exception e) {
    addBatchError("Solar-wind magnetic field: ${e.message}")
  }
  requestProduct("fluxResponse", "/products/summary/10cm-flux.json", data.token)
}

def fluxResponse(resp, data) {
  if (!validCallback(data)) return
  try {
    List body = responseList(resp, "10.7 cm radio flux")
    Map row = body ? body[-1] : null
    Double flux = finiteNumber(row?.flux)
    if (flux == null) throw new IllegalArgumentException("Measurement is missing")
    batchValues().solarRadioFlux = flux
    batchSuccess()
  } catch (Exception e) {
    addBatchError("10.7 cm radio flux: ${e.message}")
  }
  if (settings?.monitorMessages == false) finishBatch(data.token)
  else requestProduct("messagesResponse", "/products/alerts.json", data.token)
}

def messagesResponse(resp, data) {
  if (!validCallback(data)) return
  try {
    List body = responseList(resp, "SWPC messages")
    long cutoff = now() - settingNumber("messageLookbackHours", 24d, 6d, 72d).longValue() * 3600000L
    List messages = body.collect { row -> parseMessage(row) }.findAll { it && it.issued >= cutoff && includedMessageType(it.type) }
    messages.sort { a, b -> b.issued <=> a.issued }
    batchValues().messages = messages.take(100)
    batchSuccess()
  } catch (Exception e) {
    addBatchError("SWPC messages: ${e.message}")
  }
  finishBatch(data.token)
}

private void continueAfter(String callback, String token) {
  switch (callback) {
    case "scalesResponse": requestProduct("kpResponse", "/products/noaa-planetary-k-index-forecast.json", token); break
    case "kpResponse":
      if (settings?.collectSolarWind == false) {
        if (settings?.monitorMessages == false) finishBatch(token)
        else requestProduct("messagesResponse", "/products/alerts.json", token)
      } else requestProduct("windResponse", "/products/summary/solar-wind-speed.json", token)
      break
    case "windResponse": requestProduct("fieldResponse", "/products/summary/solar-wind-mag-field.json", token); break
    case "fieldResponse": requestProduct("fluxResponse", "/products/summary/10cm-flux.json", token); break
    case "fluxResponse":
      if (settings?.monitorMessages == false) finishBatch(token)
      else requestProduct("messagesResponse", "/products/alerts.json", token)
      break
    default: finishBatch(token)
  }
}

private void finishBatch(String token) {
  if (token != state.inFlight || state.batch?.token != token) return
  Map batch = state.batch ?: [:]
  Map values = batch.values instanceof Map ? batch.values : [:]
  List errors = batch.errors instanceof List ? batch.errors : []
  state.inFlight = null
  state.batch = null

  if (!values.containsKey("currentKp") && !values.containsKey("currentG")) {
    recordError(errors ? errors.join("; ") : "NOAA returned no usable primary data")
    return
  }

  boolean first = state.hasBaseline != true
  Map previous = state.previous instanceof Map ? state.previous : [:]
  Map event = chooseEvent(values, previous, first)
  publishValues(values)
  updateMessages(values, first)

  state.previous = [
    currentKp: values.currentKp,
    forecastKpPeak: values.forecastKpPeak,
    maximumScale: maximumScale(values)
  ]
  state.hasBaseline = true

  if (event) emitAutomationEvent(event)

  long completed = now()
  sendChanged("lastSuccessfulCheck", localText(completed))
  sendChanged("lastError", errors ? errors.join("; ").take(500) : "")
  sendChanged("watchStatus", errors ? "partial" : "ready")
  if (settings?.logEnable) log.debug "NOAA SWPC update complete${errors ? " with ${errors.size()} partial error(s)" : ""}"
}

private Map chooseEvent(Map values, Map previous, boolean first) {
  if (first && settings?.notifyInitialState != true) return null

  int stormThreshold = settingNumber("stormScale", 1d, 1d, 5d).intValue()
  int currentMaximum = maximumScale(values)
  int oldMaximum = safeInteger(previous.maximumScale, 0)
  boolean scaleQualified = currentMaximum >= stormThreshold
  boolean scaleChanged = !onlyIncreases() || oldMaximum < stormThreshold || currentMaximum > oldMaximum

  double activityThreshold = settingNumber("activityKp", 4d, 3d, 9d)
  double currentActivity = safeDouble(values.currentKp, 0d)
  if (settings?.includeForecastActivity != false) currentActivity = Math.max(currentActivity, safeDouble(values.forecastKpPeak, 0d))
  double oldActivity = safeDouble(previous.currentKp, 0d)
  if (settings?.includeForecastActivity != false) oldActivity = Math.max(oldActivity, safeDouble(previous.forecastKpPeak, 0d))
  boolean activityQualified = currentActivity >= activityThreshold
  boolean activityChanged = !onlyIncreases() || oldActivity < activityThreshold || currentActivity > oldActivity

  Map message = newestUnseenMessage(values.messages)
  if (scaleQualified && scaleChanged) {
    String scales = scaleSummary(values)
    return [button: 2, type: "storm", text: "NOAA space-weather scale threshold reached: ${scales}. Current Kp ${formatNumber(values.currentKp)}."]
  }
  if (message) {
    return [button: 3, type: "message", text: "New NOAA SWPC ${message.type}: ${message.title}"]
  }
  if (activityQualified && activityChanged) {
    boolean forecast = safeDouble(values.forecastKpPeak, 0d) > safeDouble(values.currentKp, 0d)
    String text = forecast ? "NOAA forecasts Kp ${formatNumber(values.forecastKpPeak)} near ${localText(values.forecastKpPeakTime as Long)}." :
      "Planetary Kp reached ${formatNumber(values.currentKp)} (${auroraLevel(safeDouble(values.currentKp, 0d))})."
    return [button: 1, type: "activity", text: text]
  }
  return null
}

private void publishValues(Map values) {
  if (values.containsKey("currentKp")) {
    sendChanged("currentKp", rounded(safeDouble(values.currentKp, 0d), 2))
    sendChanged("currentKpTime", localText(values.currentKpTime as Long))
    sendChanged("currentKpSource", values.currentKpSource ?: "unknown")
    sendChanged("auroraActivity", auroraLevel(safeDouble(values.currentKp, 0d)))
    long age = Math.max(0L, now() - (values.currentKpTime as Long))
    sendChanged("dataAgeMinutes", (age / 60000L) as Long)
  }
  if (values.containsKey("forecastKpPeak")) {
    sendChanged("forecastKpPeak", rounded(safeDouble(values.forecastKpPeak, 0d), 2))
    sendChanged("forecastKpPeakTime", localText(values.forecastKpPeakTime as Long))
    sendChanged("forecastGScale", "G${kpToG(safeDouble(values.forecastKpPeak, 0d))}")
    sendChanged("forecastAuroraActivity", auroraLevel(safeDouble(values.forecastKpPeak, 0d)))
  }
  if (values.containsKey("currentG")) {
    sendChanged("currentGScale", "G${values.currentG}")
    sendChanged("currentSScale", "S${values.currentS}")
    sendChanged("currentRScale", "R${values.currentR}")
    sendChanged("currentGText", values.currentGText)
    sendChanged("currentSText", values.currentSText)
    sendChanged("currentRText", values.currentRText)
    sendChanged("maximumCurrentScale", maximumScale(values))
    sendChanged("forecastGToday", "G${values.forecastG1 ?: 0}")
    sendChanged("forecastGTomorrow", "G${values.forecastG2 ?: 0}")
    sendChanged("forecastGDayThree", "G${values.forecastG3 ?: 0}")
  }
  if (values.solarWindSpeed != null) sendChanged("solarWindSpeed", rounded(safeDouble(values.solarWindSpeed, 0d), 0))
  if (values.interplanetaryBz != null) sendChanged("interplanetaryBz", rounded(safeDouble(values.interplanetaryBz, 0d), 1))
  if (values.magneticFieldStrength != null) sendChanged("magneticFieldStrength", rounded(safeDouble(values.magneticFieldStrength, 0d), 1))
  if (values.solarRadioFlux != null) sendChanged("solarRadioFlux", rounded(safeDouble(values.solarRadioFlux, 0d), 1))

  String forecast = "Peak Kp ${formatNumber(values.forecastKpPeak)} (${values.containsKey("forecastKpPeak") ? "G${kpToG(safeDouble(values.forecastKpPeak, 0d))}" : "unavailable"})"
  if (values.containsKey("forecastG1")) forecast += "; daily G forecast G${values.forecastG1}/G${values.forecastG2}/G${values.forecastG3}"
  sendChanged("forecastSummary", forecast)

  int maximum = maximumScale(values)
  double kp = safeDouble(values.currentKp, 0d)
  String condition = "Kp ${formatNumber(values.currentKp)}; ${scaleSummary(values)}"
  if (values.solarWindSpeed != null) condition += "; wind ${formatNumber(values.solarWindSpeed)} km/s"
  sendChanged("conditionSummary", condition)

  String weatherState = "quiet"
  if (maximum >= 3) weatherState = "severe"
  else if (maximum >= 1 || kp >= 5d) weatherState = "storm"
  else if (kp >= 4d || safeDouble(values.forecastKpPeak, 0d) >= 5d) weatherState = "unsettled"
  if (weatherState != "quiet") state.lastElevatedAt = now()
  else {
    long hold = settingNumber("keepStateHours", 6d, 1d, 24d).longValue() * 3600000L
    if (state.lastElevatedAt && now() - (state.lastElevatedAt as Long) < hold) weatherState = "unsettled"
  }
  sendChanged("spaceWeatherState", weatherState)
}

private void updateMessages(Map values, boolean first) {
  if (!(values.messages instanceof List)) return
  List messages = values.messages
  sendChanged("messageCount", messages.size())
  if (messages) {
    Map latest = messages[0]
    sendChanged("latestMessageId", latest.id)
    sendChanged("latestMessageType", latest.type)
    sendChanged("latestMessageIssued", localText(latest.issued as Long))
    sendChanged("latestMessageTitle", latest.title)
    sendChanged("latestMessageText", latest.text)
  } else {
    ["latestMessageId", "latestMessageIssued", "latestMessageTitle", "latestMessageText"].each { sendChanged(it, "") }
    sendChanged("latestMessageType", "unknown")
  }
  List ids = messages.collect { it.id }.findAll { it }
  List remembered = state.seenMessageIds instanceof List ? state.seenMessageIds : []
  state.seenMessageIds = (ids + remembered).unique().take(200)
}

private Map newestUnseenMessage(def rawMessages) {
  if (!(rawMessages instanceof List)) return null
  List seen = state.seenMessageIds instanceof List ? state.seenMessageIds : []
  return rawMessages.find { !seen.contains(it.id) }
}

private void emitAutomationEvent(Map event) {
  String text = safeText(event.text, 500)
  sendEvent(name: "pushed", value: event.button, isStateChange: true, descriptionText: text)
  sendEvent(name: "notificationText", value: text, isStateChange: true)
  sendEvent(name: "notificationType", value: event.type, isStateChange: true)
}

def clearMessageHistory() {
  state.generation = (state.generation ?: 0L) + 1L
  state.inFlight = null
  state.batch = null
  state.seenMessageIds = []
  state.hasBaseline = false
  state.previous = [:]
  refresh()
}

def testActivityEvent() {
  emitAutomationEvent([button: 1, type: "test", text: "TEST: Elevated space-weather activity. No real NOAA threshold is implied."])
}

def testStormEvent() {
  emitAutomationEvent([button: 2, type: "test", text: "TEST: NOAA space-weather scale event. No real storm is implied."])
}

def testMessageEvent() {
  emitAutomationEvent([button: 3, type: "test", text: "TEST: New NOAA SWPC message. No real watch, warning or alert is implied."])
}

private Map parseKpRow(def raw) {
  if (!(raw instanceof Map)) return null
  Long time = parseUtc(raw.time_tag)
  Double kp = finiteNumber(raw.kp)
  String source = safeText(raw.observed, 20)?.toLowerCase()
  if (time == null || kp == null || kp < 0d || kp > 9d || !(source in ["observed", "estimated", "predicted"])) return null
  return [time: time, kp: kp, source: source]
}

private Map parseMessage(def raw) {
  if (!(raw instanceof Map)) return null
  String product = safeText(raw.product_id, 30)
  String issuedText = safeText(raw.issue_datetime, 40)
  String message = raw.message == null ? "" : raw.message.toString().take(5000)
  Long issued = parseUtc(issuedText?.replace(" ", "T")?.replaceFirst(/\.\d+$/, ""))
  if (!product || issued == null || !message) return null
  String upper = message.toUpperCase()
  String type = upper.contains("WARNING:") || upper.contains("EXTENDED WARNING:") ? "warning" :
    upper.contains("ALERT:") ? "alert" : upper.contains("WATCH:") ? "watch" : "summary"
  String title = message.readLines().find { line ->
    String candidate = line?.trim()?.toUpperCase()
    candidate?.startsWith("WATCH:") || candidate?.startsWith("WARNING:") || candidate?.startsWith("EXTENDED WARNING:") || candidate?.startsWith("ALERT:")
  }?.trim()
  if (!title) title = message.readLines().find { it?.trim() }?.trim() ?: "Space Weather Message ${product}"
  String clean = message.replaceAll(/[\r\n]+/, " ").replaceAll(/\s+/, " ").trim().take(1000)
  return [id: "${product}|${issuedText}", product: product, issued: issued, type: type, title: title.take(240), text: clean]
}

private boolean includedMessageType(String type) {
  if (type == "watch") return settings?.includeWatches != false
  if (type == "warning") return settings?.includeWarnings != false
  if (type == "alert") return settings?.includeAlerts != false
  return false
}

private Map responseMap(resp, String label) {
  responseOkay(resp, label)
  def body = resp.getJson()
  if (!(body instanceof Map)) throw new IllegalArgumentException("Unexpected response")
  return body as Map
}

private List responseList(resp, String label) {
  responseOkay(resp, label)
  def body = resp.getJson()
  if (!(body instanceof List)) throw new IllegalArgumentException("Unexpected response")
  return body as List
}

private void responseOkay(resp, String label) {
  if (resp == null || resp.hasError() || resp.status != 200) {
    throw new IllegalArgumentException("${label} request failed (HTTP ${resp?.status ?: "unknown"})")
  }
}

private boolean validCallback(def data) {
  return data?.token && data.token == state.inFlight && data.token == state.batch?.token
}

private Map batchValues() {
  if (!(state.batch?.values instanceof Map)) state.batch.values = [:]
  return state.batch.values as Map
}

private void batchSuccess() {
  state.batch.successes = safeInteger(state.batch.successes, 0) + 1
}

private void addBatchError(String message) {
  if (!(state.batch?.errors instanceof List)) state.batch.errors = []
  state.batch.errors << safeText(message, 300)
}

private int maximumScale(Map values) {
  return [safeInteger(values.currentG, 0), safeInteger(values.currentS, 0), safeInteger(values.currentR, 0)].max() as Integer
}

private String scaleSummary(Map values) {
  return "G${safeInteger(values.currentG, 0)} / S${safeInteger(values.currentS, 0)} / R${safeInteger(values.currentR, 0)}"
}

private int scaleValue(def raw) {
  return Math.max(0, Math.min(5, safeInteger(raw, 0)))
}

private String scaleWord(int value) {
  return SCALE_WORDS[value] ?: "none"
}

private int kpToG(double kp) {
  if (kp >= 9d) return 5
  if (kp >= 8d) return 4
  if (kp >= 7d) return 3
  if (kp >= 6d) return 2
  if (kp >= 5d) return 1
  return 0
}

private String auroraLevel(double kp) {
  if (kp >= 8d) return "extreme"
  if (kp >= 7d) return "high"
  if (kp >= 5d) return "storm"
  if (kp >= 4d) return "active"
  return "low"
}

private boolean onlyIncreases() {
  return settings?.notifyOnlyOnIncrease != false
}

private Double settingNumber(String name, double fallback, double minimum, double maximum) {
  Double value = finiteNumber(settings?.get(name))
  if (value == null) value = fallback
  return Math.max(minimum, Math.min(maximum, value))
}

private Double finiteNumber(def raw) {
  try {
    if (raw == null || raw.toString().trim() == "") return null
    double value = raw as Double
    return (Double.isNaN(value) || Double.isInfinite(value)) ? null : value
  } catch (Exception ignored) {
    return null
  }
}

private double safeDouble(def raw, double fallback) {
  Double value = finiteNumber(raw)
  return value == null ? fallback : value
}

private int safeInteger(def raw, int fallback) {
  try {
    return raw == null ? fallback : new BigDecimal(raw.toString()).intValue()
  } catch (Exception ignored) {
    return fallback
  }
}

private Long parseUtc(def raw) {
  String value = safeText(raw, 60)
  if (!value) return null
  value = value.replace("Z", "")
  List formats = ["yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss.SSS"]
  for (String format : formats) {
    try {
      return Date.parse(format, value, TimeZone.getTimeZone("UTC")).time
    } catch (Exception ignored) { }
  }
  return null
}

private String safeText(def raw, int maximum) {
  if (raw == null) return ""
  String value = raw.toString().replaceAll(/[\u0000-\u001F\u007F]/, " ").replaceAll(/\s+/, " ").trim()
  return value.take(maximum)
}

private String formatNumber(def raw) {
  Double value = finiteNumber(raw)
  if (value == null) return "unavailable"
  return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}

private def rounded(double value, int places) {
  return new BigDecimal(Double.toString(value)).setScale(places, BigDecimal.ROUND_HALF_UP)
}

private TimeZone hubTimezone() {
  return location?.timeZone ?: TimeZone.getTimeZone("UTC")
}

private String localText(long time) {
  return new Date(time).format("yyyy-MM-dd HH:mm:ss z", hubTimezone())
}

private def sendChanged(String name, value) {
  if (device.currentValue(name)?.toString() != value?.toString()) sendEvent(name: name, value: value)
}

private void recordError(String message) {
  sendChanged("watchStatus", "error")
  sendEvent(name: "lastError", value: safeText(message, 500))
  log.warn "Space Weather Watch: ${message}"
}
