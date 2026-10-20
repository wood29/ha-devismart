# Changelog

## 1.0.0

Stabilized fork of bob-tm's Devismart MQTT 0.5 (ha-devi-mqtt dc38368, ha-apps bf168d5).

### Reliability
- Commands keep working after a Mosquitto or Home Assistant restart: the app re-subscribes on every reconnect.
- One reconnect mechanism (Paho automatic reconnect with backoff) replaces the old one, which could loop forever.
- A malformed MQTT message is ignored instead of closing the MQTT connection.
- New `devi/bridge/status` topic with a retained last will: all entities become unavailable when the app stops.
- Entities of an offline thermostat become unavailable instead of showing frozen values.
- Discovery is re-published when Home Assistant restarts; all state is retained, so values show immediately.
- Thermostat reconnects can no longer stop for good after an unexpected error; backoff up to 5 minutes.
- The app exits (and the Supervisor restarts it) when MQTT or the thermostats stay unreachable.

### Thermostat control
- Values are checked and limited to the thermostat's ranges before they are sent; invalid values are dropped.
- An invalid weekly schedule no longer wipes the current schedule.
- Climate entity: "Heat" returns to the previous mode instead of forcing Schedule; Off is no longer also a preset;
  removed the invalid `device_class`; the card shows "off" when the thermostat is off; no more 0 °C target in Off.
- Number entity ranges follow the datasheet (e.g. frost protection 5-9 °C).

### Setup and security
- Pairing from the app configuration (`pairing_code`); no SSH terminal or disabled protection mode needed.
- `devi_config.json` is written readable by the app only; the private key is never printed or logged.
- No open network port; no web server in waiting mode.
- Built from the code in this repository with fixed library versions (no download of the latest Git version
  at build time). Updated protobuf, logback, gson, slf4j and commons-text.
- Configurable log level; publishes are logged at debug level only.
