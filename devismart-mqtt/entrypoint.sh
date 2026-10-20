#!/bin/bash
# Start script for the DEVIreg Smart MQTT app.
#
# 1. If the thermostats are not paired yet, pair using the code from the app configuration.
#    If that is not possible, wait quietly (no restart loop, no retrying a used code).
# 2. Start the bridge. If it exits with an error, the Supervisor watchdog restarts the app.

JAR=/app/ha-devi-mqtt.jar
OPTIONS=/data/options.json
DEVI_CONFIG=/config/devi_config.json
TEMPLATES=/app/auto-discovery-templates
JAVA_OPTS="-Xms32m -Xmx256m -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

wait_forever() {
    trap 'exit 0' TERM INT
    while true; do
        sleep 3600 &
        wait $!
    done
}

if [ ! -s "$DEVI_CONFIG" ]; then
    java $JAVA_OPTS -cp "$JAR" io.homeassistant.devi.mqtt.service.AddonPairing "$OPTIONS" "$DEVI_CONFIG"
    rc=$?
    if [ "$rc" -ne 0 ]; then
        echo "Waiting. Fix the issue above in the app configuration, then restart the app."
        wait_forever
    fi
fi

chmod 600 "$DEVI_CONFIG" 2>/dev/null

exec java $JAVA_OPTS -cp "$JAR" io.homeassistant.devi.mqtt.service.AddonMain "$OPTIONS" "$DEVI_CONFIG" "$TEMPLATES"
