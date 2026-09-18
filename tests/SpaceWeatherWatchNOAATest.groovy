// Run from the repository root with Groovy 2.4.21.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def state = [:]
def settings = [:]
def readings = [:]
def events = []
def requests = []
def jobs = [:]
def warnings = []
long clock = 1789758000000L // 2026-09-18 17:00:00 UTC
def location = [timeZone: TimeZone.getTimeZone('America/Denver')]

def binding = new Binding([
  state: state,
  settings: settings,
  location: location,
  metadata: { Closure ignored -> },
  now: { -> clock },
  device: [
    currentValue: { String name -> readings[name] },
    updateSetting: { String name, Map value -> settings[name] = value.value }
  ],
  log: [warn: { message -> warnings << message.toString() }, debug: { ignored -> }, info: { ignored -> }],
  sendEvent: { Map event -> events << event; readings[event.name] = event.value },
  runIn: { seconds, handler, Map options = [:] -> jobs[handler] = [seconds: seconds, options: options] },
  unschedule: { String handler = null -> if (handler) jobs.remove(handler) else jobs.clear() },
  runEvery1Hour: { handler -> jobs[handler] = [every: 3600] },
  schedule: { expression, handler -> jobs[handler] = [cron: expression] },
  asynchttpGet: { callback, params, data -> requests << [callback: callback, params: params, data: data] }
])

def driver = new GroovyShell(binding).parse(new File('SpaceWeatherWatchNOAA.groovy'))
driver.run()

def ok = { body -> [hasError: { -> false }, status: 200, getJson: { -> body }] }
def failed = { int status -> [hasError: { -> true }, status: status, getJson: { -> null }] }
def utc = { long time -> new Date(time).format("yyyy-MM-dd'T'HH:mm:ss", TimeZone.getTimeZone('UTC')) }
def scales = { int g, int s = 0, int r = 0, List forecast = [0, 0, 0] ->
  [
    '0': [DateStamp: new Date(clock).format('yyyy-MM-dd', TimeZone.getTimeZone('UTC')),
          TimeStamp: new Date(clock).format('HH:mm:ss', TimeZone.getTimeZone('UTC')),
          G: [Scale: "${g}", Text: g ? 'active' : 'none'],
          S: [Scale: "${s}", Text: s ? 'active' : 'none'],
          R: [Scale: "${r}", Text: r ? 'active' : 'none']],
    '1': [G: [Scale: "${forecast[0]}"]],
    '2': [G: [Scale: "${forecast[1]}"]],
    '3': [G: [Scale: "${forecast[2]}"]]
  ]
}
def kp = { double current, double peak = current ->
  [
    [time_tag: utc(clock - 10800000L), kp: Math.max(0d, current - 1d), observed: 'observed', noaa_scale: null],
    [time_tag: utc(clock), kp: current, observed: 'estimated', noaa_scale: current >= 5 ? "G${(int) current - 4}" : null],
    [time_tag: utc(clock + 10800000L), kp: peak, observed: 'predicted', noaa_scale: peak >= 5 ? "G${(int) peak - 4}" : null]
  ]
}
def message = { String id, String kind, long issued = clock - 60000L ->
  [product_id: id, issue_datetime: new Date(issued).format('yyyy-MM-dd HH:mm:ss.SSS', TimeZone.getTimeZone('UTC')),
   message: "Space Weather Message Code: ${id}\r\n${kind.toUpperCase()}: Test ${kind} for geomagnetic activity\r\nPotential Impacts: test data"]
}

def complete = { Map args = [:] ->
  def first = requests[-1]
  assert first.callback == 'scalesResponse'
  driver.scalesResponse(args.scaleResponse ?: ok(scales(args.g ?: 0, args.s ?: 0, args.r ?: 0, args.daily ?: [0, 0, 0])), first.data)
  def kpRequest = requests[-1]
  assert kpRequest.callback == 'kpResponse'
  driver.kpResponse(args.kpResponse ?: ok(kp(args.currentKp == null ? 2d : args.currentKp as double,
    args.peakKp == null ? (args.currentKp == null ? 2d : args.currentKp as double) : args.peakKp as double)), kpRequest.data)
  if (settings.collectSolarWind != false) {
    def windRequest = requests[-1]
    assert windRequest.callback == 'windResponse'
    driver.windResponse(args.windResponse ?: ok([[proton_speed: 425, time_tag: utc(clock) + 'Z']]), windRequest.data)
    def fieldRequest = requests[-1]
    assert fieldRequest.callback == 'fieldResponse'
    driver.fieldResponse(args.fieldResponse ?: ok([[bt: 6.2, bz_gsm: -3.1, time_tag: utc(clock) + 'Z']]), fieldRequest.data)
    def fluxRequest = requests[-1]
    assert fluxRequest.callback == 'fluxResponse'
    driver.fluxResponse(args.fluxResponse ?: ok([[flux: 142.5, time_tag: utc(clock)]]), fluxRequest.data)
  }
  if (settings.monitorMessages != false) {
    def messageRequest = requests[-1]
    assert messageRequest.callback == 'messagesResponse'
    driver.messagesResponse(args.messageResponse ?: ok(args.messages ?: []), messageRequest.data)
  }
}

driver.installed()
assert requests.empty
assert readings.watchStatus == 'setup'
assert readings.numberOfButtons == 3
driver.initialize()
assert jobs.refresh.cron == '0 */15 * ? * *'
assert requests[-1].params.uri == 'https://services.swpc.noaa.gov/products/noaa-scales.json'
assert !requests[-1].params.toString().toLowerCase().contains('apikey')
complete(currentKp: 2.33d, peakKp: 4d, daily: [0, 1, 0], messages: [message('A20F', 'watch')])
assert readings.watchStatus == 'ready'
assert readings.currentKp.toString() == '2.33'
assert readings.currentKpSource == 'estimated'
assert readings.currentGScale == 'G0'
assert readings.forecastKpPeak as Double == 4d
assert readings.forecastGTomorrow == 'G1'
assert readings.solarWindSpeed.toString() == '425'
assert readings.interplanetaryBz.toString() == '-3.1'
assert readings.solarRadioFlux.toString() == '142.5'
assert readings.messageCount == 1
assert readings.latestMessageType == 'watch'
assert readings.spaceWeatherState == 'quiet'
assert !events.any { it.name == 'pushed' }
assert state.hasBaseline && state.seenMessageIds.size() == 1
println 'PASS: setup gate, no-key product pipeline, baseline suppression and published attributes'

clock += 900000L
driver.refresh()
complete(g: 2, currentKp: 6d, peakKp: 7d, daily: [2, 1, 0], messages: [message('K06A', 'alert'), message('A20F', 'watch', clock - 960000L)])
assert readings.spaceWeatherState == 'storm'
assert readings.currentGScale == 'G2'
assert readings.forecastGScale == 'G3'
assert readings.auroraActivity == 'storm'
assert readings.forecastAuroraActivity == 'high'
assert events.count { it.name == 'pushed' && it.value == 2 } == 1
assert readings.notificationType == 'storm'
assert readings.notificationText.contains('G2 / S0 / R0')
println 'PASS: NOAA-scale priority, Kp forecast and one-event precedence'

clock += 900000L
driver.refresh()
complete(g: 2, currentKp: 6d, peakKp: 6d, messages: [message('K05W', 'warning'), message('K06A', 'alert', clock - 960000L)])
assert events.count { it.name == 'pushed' && it.value == 3 } == 1
assert readings.notificationType == 'message'
assert readings.notificationText.contains('warning')
assert readings.latestMessageTitle.startsWith('WARNING:')
assert !readings.latestMessageText.contains('\r')
println 'PASS: new official-message detection, class filtering and deduplication'

// Establish a quiet reading, then cross the activity threshold without a NOAA scale event.
clock += 900000L
driver.refresh()
complete(currentKp: 2d, peakKp: 2d, messages: [message('K05W', 'warning', clock - 960000L)])
int eventCount = events.count { it.name == 'pushed' }
clock += 900000L
driver.refresh()
complete(currentKp: 4d, peakKp: 5d, messages: [message('K05W', 'warning', clock - 1860000L)])
assert events.count { it.name == 'pushed' } == eventCount + 1
assert events.findAll { it.name == 'pushed' }[-1].value == 1
assert readings.notificationType == 'activity'
assert readings.notificationText.startsWith('NOAA forecasts Kp 5')
println 'PASS: activity threshold crossing and forecast-aware automation'

driver.testActivityEvent()
assert events[-1].name == 'notificationType' && readings.notificationType == 'test'
assert events.findAll { it.name == 'pushed' }[-1].value == 1
driver.testStormEvent()
assert events.findAll { it.name == 'pushed' }[-1].value == 2
driver.testMessageEvent()
assert events.findAll { it.name == 'pushed' }[-1].value == 3
assert readings.notificationText.startsWith('TEST:')

// A supplemental failure keeps primary conditions usable and reports partial status.
clock += 900000L
driver.refresh()
complete(currentKp: 3d, peakKp: 3d, windResponse: failed(503), messages: [])
assert readings.watchStatus == 'partial'
assert readings.lastError.contains('HTTP 503')

// Stale callbacks are ignored.
def staleData = requests[-1].data
int beforeStale = requests.size()
driver.scalesResponse(ok(scales(5)), staleData)
assert requests.size() == beforeStale

def restored = new JsonSlurper().parseText(JsonOutput.toJson(state))
assert restored.seenMessageIds instanceof List
assert JsonOutput.toJson(state).length() < 30000
println 'PASS: test commands, partial failures, stale callbacks and JSON-safe state'
println 'ALL CHECKS PASSED'
