package io.homeassistant.devi.mqtt.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.homeassistant.binding.danfoss.internal.DeviRegHandler;

/** Routes MQTT commands to the handler of the thermostat they address. */
public class CommandMediator implements Mediator {

    private static final Logger logger = LoggerFactory.getLogger(CommandMediator.class);

    private final Map<String, DeviRegHandler> deviRegHandlers = new ConcurrentHashMap<>();

    public void addDeviRegHandler(String handlerId, DeviRegHandler deviRegHandler) {
        deviRegHandlers.put(handlerId, deviRegHandler);
    }

    @Override
    public void notify(Object sender, InputCommand event) {
        if (sender instanceof MQTTService) {
            handleMQTTMessage(event);
        }
    }

    private void handleMQTTMessage(InputCommand inputCommand) {
        DeviRegHandler handler = deviRegHandlers.get(inputCommand.deviceId);
        if (handler == null) {
            logger.warn("Command for unknown thermostat {}", inputCommand.deviceId);
            return;
        }
        try {
            handler.handleCommand(inputCommand.sensorName, inputCommand.payload);
        } catch (Exception e) {
            logger.error("Failed to handle {}", inputCommand, e);
        }
    }
}
