# DEVIreg Smart MQTT

Brings DEVIreg™ Smart floor-heating thermostats into Home Assistant through MQTT, with automatic discovery.
It talks to the thermostats through the same Danfoss cloud the DEVIsmart app uses, so it needs internet access.

## Before you start

- Install the **Mosquitto broker** app and the **MQTT** integration.
- Create an MQTT login for this app (Mosquitto broker → Configuration → Logins), or use a Home Assistant user.
- Enter the broker host (`core-mosquitto`), port, username and password in this app's **Configuration**.

## Pairing (once)

> **Use the Android DEVIsmart app for this step.** Sharing from the iPhone app fails with "couldn't send
> configuration" (a known DEVIsmart iOS issue). If you only have iPhones, share the house to the Android app in an
> emulator such as BlueStacks on a Windows PC first, then share from there.

1. In the **DEVIsmart** app open **Settings → Share house** and generate a code.
2. Paste the code into **Pairing code** in this app's configuration and save.
3. Start (or restart) the app and watch the **Log** tab. When it says *Code accepted*, confirm the share in the
   DEVIsmart app on your phone within 3 minutes.
4. When the log says *Pairing complete*, the thermostats appear under **Settings → Devices & services → MQTT**.

The pairing result is stored in `devi_config.json` in this app's config folder (`/addon_configs/<id>_devismart-mqtt/`).
It contains the private key that gives access to your thermostats: it is readable by this app only and is included in
Home Assistant backups. To pair again, delete that file and repeat the steps above with a new code.

Codes work once and expire. If the log says the code was refused, generate a new one.

If the phone says it **couldn't send the configuration**, check in the DEVIsmart app that all thermostats are
online and that they have fewer than 10 shared users; remove old or duplicate users and try again.

## What you get per thermostat

- A **climate** entity: current temperature, target temperature, heating on/off and the thermostat's modes as presets
  (Schedule, Manual, Override, Vacation, Pause). "Off" turns the thermostat off; "Heat" turns it back on in the mode
  it was in before.
- Numbers for every setpoint (comfort, economy, manual, away, frost protection, floor limits), switches, sensors
  for room and floor temperature, energy use, alarms, Wi-Fi and firmware.

Changing the target temperature on the climate card changes the setpoint that is active at that moment, exactly like
the DEVIsmart app: in schedule mode that is the comfort or economy temperature, in manual mode the manual temperature.

## Reliability

- If the app stops or crashes, all its entities show as **unavailable** in Home Assistant instead of old values.
- If a thermostat is offline, its entities show as unavailable.
- After a Mosquitto or Home Assistant restart the app reconnects, re-subscribes and re-publishes everything by itself.
- If MQTT does not work for 10 minutes, or no thermostat answers for 30 minutes, the app exits and the Supervisor
  restarts it. Turn on **Watchdog** on the app's Info tab for this.
- Values sent to the thermostats are checked and limited to the thermostat's ranges before they are sent.

## Advanced

- **Custom templates:** copy the `auto-discovery-templates` folder from the repository to the app's config folder
  and edit the JSON files. They replace the built-in ones.
- **mqtt_config.json:** if this file exists in the config folder (from older versions), its MQTT settings are used
  instead of the configuration tab.
- **Pairing on a computer:** `java -cp ha-devi-mqtt.jar io.homeassistant.devi.mqtt.service.DiscoveryService`
  writes `devi_config.json` in the current folder; copy it to the app's config folder.

## Credits

Based on [igor-podpalchenko/ha-devi-mqtt](https://github.com/igor-podpalchenko/ha-devi-mqtt) and
[bob-tm/ha-devi-mqtt](https://github.com/bob-tm/ha-devi-mqtt) / [bob-tm/ha-apps](https://github.com/bob-tm/ha-apps),
which build on Pavel Fedin's openHAB DEVIreg binding and [OpenSDG](https://github.com/Sonic-Amiga/opensdg).
