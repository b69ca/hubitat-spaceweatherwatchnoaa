# Space Weather Watch (NOAA)

A Hubitat driver that turns public NOAA Space Weather Prediction Center data into dashboard attributes and Rule Machine triggers. It monitors planetary Kp, the official G/R/S scales, short-term forecasts, solar-wind measurements and newly issued SWPC watches, warnings and alerts.

There is no account, API key, subscription, location request, companion server or additional Hubitat library.

## What it does

- Reports the latest observed or estimated planetary Kp index and its age.
- Finds the peak predicted Kp during a configurable 12–72 hour window.
- Publishes the current NOAA geomagnetic-storm (G), solar-radiation-storm (S) and radio-blackout (R) scales.
- Publishes NOAA's geomagnetic forecast for today and the following two days.
- Classifies current and forecast auroral activity from Kp without claiming local visibility.
- Optionally reports solar-wind speed, total magnetic field, north/south Bz and the 10.7 cm solar radio flux.
- Tracks new NOAA SWPC watches, warnings and alerts without replaying old messages after installation.
- Emits three standard Hubitat button events for straightforward automations.
- Continues with a `partial` status when a supplemental NOAA product is temporarily unavailable.

Space Weather Watch summarizes NOAA products; it does not issue forecasts or warnings. Do not use it as the only source for safety, aviation, navigation, radio, satellite or electrical-grid decisions.

## Install

1. In Hubitat, open **Drivers Code → New Driver**.
2. Import the [raw driver](https://raw.githubusercontent.com/b69ca/hubitat-spaceweatherwatchnoaa/main/SpaceWeatherWatchNOAA.groovy), or paste its contents, and click **Save**.
3. Open **Devices → Add Device → Virtual** and select **Space Weather Watch (NOAA)** from the user drivers.
4. Give the device a name such as `Space Weather Watch`.
5. Review its preferences and click **Save Preferences**.
6. Check the device page. `watchStatus` should move from `setup` through `updating` to `ready`.

Creating the virtual device does not start network requests. The driver stays in `setup` until preferences are saved or **Initialize** is deliberately pressed.

The first successful update normally creates a silent baseline. Existing activity and NOAA messages populate the attributes without being announced as new. Enable **Notify for the most important condition on the first successful check** if you prefer an initial event.

To update later, import the raw URL again, save the code, and press **Initialize** on the device.

## Network and privacy

The driver downloads public JSON products from `services.swpc.noaa.gov`. It sends no location, account details, API key, Hubitat identifier, device name or automation data. As with any HTTPS request, NOAA's service receives the hub's public IP address and the driver user-agent.

The default 15-minute update downloads two primary products, three small measurement summaries, and the current SWPC message list. Disable solar-wind collection or message monitoring if those attributes are unnecessary. Results and deduplication history remain on the hub.

## Preferences

| Preference | Default | Purpose |
| --- | --- | --- |
| Automatic check interval | 15 minutes | Checks every 5, 10, 15, 30 or 60 minutes. |
| Kp forecast window | 24 hours | Finds the peak predicted Kp in the next 12–72 hours. |
| Activity-event Kp threshold | Kp 4 | Button 1 threshold for current or forecast activity. |
| NOAA-scale event threshold | Level 1 | Button 2 threshold across the current G, S and R scales. |
| Forecast may trigger activity | On | Includes forecast Kp when evaluating button 1. |
| Notify only on increase | On | Avoids repeating an event while a condition remains unchanged. |
| Monitor official messages | On | Downloads and tracks SWPC messages. |
| Include watches/warnings/alerts | All on | Selects which message classes can trigger button 3. |
| Message lookback | 24 hours | Considers messages issued in the last 6–72 hours. |
| Notify initial state | Off | Optionally announces the highest-priority condition at first update. |
| Keep activity state elevated | 6 hours | Holds a recently elevated state at `unsettled` after conditions subside. |
| Collect solar wind and radio flux | On | Adds solar-wind speed, Bt, Bz and F10.7 measurements. |
| Debug logging | Off | Logs update summaries and automatically disables after 30 minutes. |

With **Notify only on increase** enabled, an event is emitted when a threshold is crossed from below or its severity rises. Turning it off permits a matching condition to emit again on each update.

## Automation events

The driver implements Hubitat's **Pushable Button** capability:

| Button | Meaning |
| --- | --- |
| 1 | Current or forecast Kp reached the configured activity threshold. |
| 2 | A current NOAA G, S or R scale reached the configured scale threshold. |
| 3 | NOAA issued a new selected watch, warning or alert. |

One button event is emitted per update. If several conditions appear together, the driver chooses the NOAA-scale event first, then a new official message, then the general Kp activity event. `notificationText` explains the selected event and `notificationType` identifies it as `storm`, `message` or `activity`.

Example Rule Machine setup:

1. Trigger on **Space Weather Watch → button 2 pushed**.
2. Read the device's `notificationText` custom attribute into a local string variable.
3. Send that text to a notification device or announcement rule.
4. Run **Test Storm Event** from the device page to verify the rule.

Use button 1 for aurora-oriented or general activity notifications. Use button 3 when you want NOAA's issued products rather than a numeric threshold. Test messages begin with `TEST:` and do not alter the real condition state or message history.

**Clear Message History** forgets the saved message IDs and rebuilds the baseline. With the default initial-state setting, current messages become a new silent baseline. This command is intended for troubleshooting.

## Attributes

### Status and summary

| Attribute | Meaning |
| --- | --- |
| `watchStatus` | `setup`, `updating`, `ready`, `partial` or `error`. |
| `spaceWeatherState` | `quiet`, `unsettled`, `storm` or `severe`. |
| `conditionSummary` | Compact current Kp, scales and solar-wind summary. |
| `lastChecked` | Time the latest update began. |
| `lastSuccessfulCheck` | Time usable primary data was last processed. |
| `lastError` | Latest product or response error; empty after a complete update. |
| `dataAgeMinutes` | Age of the Kp measurement used by the driver. |

### Kp, scales and forecasts

| Attribute | Meaning |
| --- | --- |
| `currentKp` | Latest observed or estimated planetary Kp. |
| `currentKpTime` | Timestamp of that Kp interval. |
| `currentKpSource` | `observed`, `estimated` or `unknown`. |
| `currentGScale`, `currentSScale`, `currentRScale` | Current official NOAA levels such as `G1`, `S0`, or `R2`. |
| `currentGText`, `currentSText`, `currentRText` | NOAA's accompanying scale descriptions. |
| `maximumCurrentScale` | Highest numeric level among G, S and R. |
| `forecastKpPeak` | Highest predicted Kp in the selected forecast window. |
| `forecastKpPeakTime` | Time of that predicted peak. |
| `forecastGScale` | G-scale equivalent calculated from forecast Kp. |
| `forecastGToday`, `forecastGTomorrow`, `forecastGDayThree` | NOAA's daily G forecast. |
| `forecastSummary` | Human-readable Kp and daily G forecast. |
| `auroraActivity`, `forecastAuroraActivity` | Broad Kp activity categories: `low`, `active`, `storm`, `high`, or `extreme`. |

### Measurements and messages

| Attribute | Meaning |
| --- | --- |
| `solarWindSpeed` | Solar-wind proton speed in km/s. |
| `interplanetaryBz` | North/south GSM Bz in nanotesla; sustained negative values can favor geomagnetic coupling. |
| `magneticFieldStrength` | Total interplanetary magnetic field Bt in nanotesla. |
| `solarRadioFlux` | Observed 10.7 cm solar radio flux in solar flux units. |
| `messageCount` | Selected recent messages inside the lookback. |
| `latestMessageId` | Product ID plus issue time used for deduplication. |
| `latestMessageType` | `watch`, `warning`, `alert`, `summary`, or `unknown`. |
| `latestMessageIssued` | Hub-local issue time. |
| `latestMessageTitle` | Headline extracted from the NOAA message. |
| `latestMessageText` | Sanitized message text, capped for a Hubitat attribute. |
| `notificationText` | Last automation or test message; retained until replaced. |
| `notificationType` | `activity`, `storm`, `message`, or `test`. |

## Interpreting the data

Kp is a global, three-hour geomagnetic activity index from 0 to 9. NOAA's G scale begins at Kp 5. The R and S scales describe different phenomena: radio blackouts associated with solar X-ray flares and solar radiation storms associated with energetic particles.

Aurora visibility cannot be determined from Kp alone. Cloud cover, darkness, light pollution, the auroral oval, magnetic latitude and short-lived changes all matter. The driver's aurora attributes are activity labels, not local visibility forecasts.

Solar-wind Bz is especially dynamic. A negative value can support geomagnetic activity when other conditions align, but a single reading is not a forecast. F10.7 is a broad solar-activity proxy and is normally updated daily.

For the authoritative context behind any value or message, use the [NOAA Space Weather Prediction Center](https://www.swpc.noaa.gov/).

## Errors and troubleshooting

- `watchStatus = error`: both primary products failed or contained no usable current data. Check hub internet access and press **Refresh**.
- `watchStatus = partial`: primary conditions are usable, but at least one supplemental product failed. `lastError` identifies it.
- No button event after installation: the first successful update is a baseline by default.
- Repeated notification text: `notificationText` deliberately preserves the last automation event.
- No SWPC messages: the selected classes may have no messages inside the configured lookback.
- Old `spaceWeatherState = unsettled`: the recent-state hold avoids rapid state flapping; shorten the hold if desired.
- Forecast peak is unavailable: NOAA may temporarily omit predicted Kp rows while current scales remain usable.

## Development and tests

The driver is a single Hubitat-compatible Groovy file. Tests simulate Hubitat and NOAA responses without contacting the network.

With Groovy 2.4.21 installed, run:

```sh
groovy tests/SpaceWeatherWatchNOAATest.groovy
```

The suite covers the setup gate, request sequence, first-run suppression, current and forecast Kp, NOAA scales, event precedence, official-message filtering and deduplication, threshold crossings, partial failures, stale callbacks, test commands, sanitization and JSON-safe state. GitHub Actions runs it on every push and pull request.

## Data source, license and acknowledgment

Data and issued products courtesy of the [NOAA/NWS Space Weather Prediction Center](https://www.swpc.noaa.gov/). The driver uses the public products served from [`services.swpc.noaa.gov`](https://services.swpc.noaa.gov/products/) and follows the official [NOAA Space Weather Scales](https://www.swpc.noaa.gov/noaa-scales-explanation).

The driver source is published under the MIT License. Space Weather Watch is not affiliated with or endorsed by NOAA, NWS, Hubitat or the U.S. government.
