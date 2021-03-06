/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.vera.handler;

import static org.openhab.binding.vera.VeraBindingConstants.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.OpenClosedType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingStatusInfo;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.thing.binding.builder.ChannelBuilder;
import org.eclipse.smarthome.core.thing.binding.builder.ThingBuilder;
import org.eclipse.smarthome.core.thing.type.ChannelTypeUID;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.openhab.binding.vera.config.VeraDeviceConfiguration;
import org.openhab.binding.vera.controller.json.Device;
import org.openhab.binding.vera.internal.converter.VeraDeviceStateConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link VeraDeviceHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Dmitriy Ponomarev
 */
public class VeraDeviceHandler extends BaseThingHandler {
    private Logger logger = LoggerFactory.getLogger(getClass());

    private VeraDeviceConfiguration mConfig;

    public VeraDeviceHandler(Thing thing) {
        super(thing);
    }

    private class Initializer implements Runnable {
        @Override
        public void run() {
            try {
                VeraBridgeHandler veraBridgeHandler = getVeraBridgeHandler();
                if (veraBridgeHandler != null && veraBridgeHandler.getThing().getStatus().equals(ThingStatus.ONLINE)) {
                    ThingStatusInfo statusInfo = veraBridgeHandler.getThing().getStatusInfo();
                    logger.debug("Change device status to bridge status: {}", statusInfo);

                    // Set thing status to bridge status
                    updateStatus(statusInfo.getStatus(), statusInfo.getStatusDetail(), statusInfo.getDescription());

                    logger.debug("Add channels");
                    Device device = veraBridgeHandler.getController().getDevice(mConfig.getDeviceId());
                    if (device != null) {
                        logger.debug("Found {} device", device.getName());
                        updateLabelAndLocation(device.getName(), device.getRoomName());
                        addDeviceAsChannel(device,
                                veraBridgeHandler.getVeraBridgeConfiguration().getHomekitIntegration());
                    }
                } else {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.HANDLER_INITIALIZING_ERROR,
                            "Controller is not online");
                }
            } catch (Exception e) {
                logger.error("Error occurred when adding device as channel: ", e);
                if (getThing().getStatus() == ThingStatus.ONLINE) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.HANDLER_INITIALIZING_ERROR,
                            "Error occurred when adding device as channel: " + e.getMessage());
                }
            }
        }
    };

    protected synchronized VeraBridgeHandler getVeraBridgeHandler() {
        Bridge bridge = getBridge();
        if (bridge == null) {
            return null;
        }
        ThingHandler handler = bridge.getHandler();
        if (handler instanceof VeraBridgeHandler) {
            return (VeraBridgeHandler) handler;
        } else {
            return null;
        }
    }

    private VeraDeviceConfiguration loadAndCheckConfiguration() {
        VeraDeviceConfiguration config = getConfigAs(VeraDeviceConfiguration.class);
        if (config.getDeviceId() == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Couldn't create device, deviceId is missing.");
            return null;
        }
        return config;
    }

    @Override
    public void initialize() {
        logger.debug("Initializing Vera device handler ...");
        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.CONFIGURATION_PENDING,
                "Checking configuration and bridge...");
        mConfig = loadAndCheckConfiguration();
        if (mConfig != null) {
            logger.debug("Configuration complete: {}", mConfig);
            scheduler.schedule(new Initializer(), 2, TimeUnit.SECONDS);
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "DeviceId required!");
        }
    }

    @Override
    public void dispose() {
        logger.debug("Dispose Vera device handler ...");
        if (mConfig != null && mConfig.getDeviceId() != null) {
            mConfig.setDeviceId(null);
        }
        super.dispose();
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        // Only called if status ONLINE or OFFLINE
        logger.debug("Vera bridge status changed: {}", bridgeStatusInfo);

        if (bridgeStatusInfo.getStatus().equals(ThingStatus.OFFLINE)) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "Bridge status is offline.");
        } else if (bridgeStatusInfo.getStatus().equals(ThingStatus.ONLINE)) {
            // Initialize thing, if all OK the status of device thing will be ONLINE
            scheduler.execute(new Initializer());
        }
    }

    private void updateLabelAndLocation(String label, String location) {
        if (!label.equals(thing.getLabel()) || !location.equals(thing.getLocation())) {
            logger.debug("Set location to {}", location);
            ThingBuilder thingBuilder = editThing();
            if (!label.equals(thing.getLabel())) {
                thingBuilder.withLabel(thing.getLabel());
            }
            if (!location.equals(thing.getLocation())) {
                thingBuilder.withLocation(location);
            }
            updateThing(thingBuilder.build());
        }
    }

    private class DevicePolling implements Runnable {
        @Override
        public void run() {
            for (Channel channel : getThing().getChannels()) {
                if (isLinked(channel.getUID().getId())) {
                    refreshChannel(channel);
                } else {
                    logger.debug("Polling for device: {} not possible (channel {} not linked", thing.getLabel(),
                            channel.getLabel());
                }
            }
        }
    };

    protected void refreshAllChannels() {
        scheduler.execute(new DevicePolling());
    }

    private void refreshChannel(Channel channel) {
        VeraBridgeHandler veraBridgeHandler = getVeraBridgeHandler();
        if (veraBridgeHandler == null || !veraBridgeHandler.getThing().getStatus().equals(ThingStatus.ONLINE)) {
            logger.debug("Vera bridge handler not found or not ONLINE.");
            return;
        }

        // Check device id associated with channel
        String deviceId = channel.getProperties().get(DEVICE_CONFIG_ID);
        if (deviceId == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE,
                    "Not found deviceId for channel: " + channel.getChannelTypeUID());
            logger.debug("Vera device disconnected: {}", deviceId);
            return;
        }

        Device device = veraBridgeHandler.getController().getDevice(deviceId);
        if (device == null) {
            updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Channel refresh for device: " + deviceId
                    + " with channel: " + channel.getChannelTypeUID() + " failed!");
            logger.debug("Vera device disconnected: {}", deviceId);
            return;
        }

        updateLabelAndLocation(device.getName(), device.getRoomName());
        updateState(channel.getUID(), VeraDeviceStateConverter.toState(device, channel, logger));
        ThingStatusInfo statusInfo = veraBridgeHandler.getThing().getStatusInfo();
        updateStatus(statusInfo.getStatus(), statusInfo.getStatusDetail(), statusInfo.getDescription());
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        logger.debug("Vera device channel linked: {}", channelUID);
        VeraBridgeHandler veraBridgeHandler = getVeraBridgeHandler();
        if (veraBridgeHandler == null || !veraBridgeHandler.getThing().getStatus().equals(ThingStatus.ONLINE)) {
            logger.debug("Vera bridge handler not found or not ONLINE.");
            return;
        }
        super.channelLinked(channelUID);
    }

    @Override
    public void channelUnlinked(ChannelUID channelUID) {
        logger.debug("Vera device channel unlinked: {}", channelUID);
        VeraBridgeHandler veraBridgeHandler = getVeraBridgeHandler();
        if (veraBridgeHandler == null || !veraBridgeHandler.getThing().getStatus().equals(ThingStatus.ONLINE)) {
            logger.debug("Vera bridge handler not found or not ONLINE.");
            return;
        }
        super.channelUnlinked(channelUID);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, final Command command) {
        logger.debug("Handle command for channel: {} with command: {}", channelUID.getId(), command.toString());

        VeraBridgeHandler veraBridgeHandler = getVeraBridgeHandler();
        if (veraBridgeHandler == null || !veraBridgeHandler.getThing().getStatus().equals(ThingStatus.ONLINE)) {
            logger.debug("Vera bridge handler not found or not ONLINE.");
            return;
        }

        final Channel channel = getThing().getChannel(channelUID.getId());
        final String deviceId = channel.getProperties().get("deviceId");

        if (deviceId != null) {
            Device device = veraBridgeHandler.getController().getDevice(deviceId);
            if (device != null) {
                if (command instanceof RefreshType) {
                    logger.debug("Handle command: RefreshType");
                    refreshChannel(channel);
                } else {
                    if (command instanceof PercentType) {
                        logger.debug("Handle command: PercentType");
                        veraBridgeHandler.getController().setDimLevel(device, ((PercentType) command).toString());
                    }
                    if (command instanceof DecimalType) {
                        logger.debug("Handle command: DecimalType");
                        veraBridgeHandler.getController().setDimLevel(device, ((DecimalType) command).toString());
                    }
                    if (command instanceof OnOffType) {
                        logger.debug("Handle command: OnOffType");
                        if (command.equals(OnOffType.ON)) {
                            veraBridgeHandler.getController().turnDeviceOn(device);
                        } else if (command.equals(OnOffType.OFF)) {
                            veraBridgeHandler.getController().turnDeviceOff(device);
                        }
                    } else if (command instanceof OpenClosedType) {
                        logger.debug("Handle command: OpenClosedType");
                        if (command.equals(OpenClosedType.CLOSED)) {
                            veraBridgeHandler.getController().turnDeviceOn(device);
                        } else if (command.equals(OpenClosedType.OPEN)) {
                            veraBridgeHandler.getController().turnDeviceOff(device);
                        }
                    } else {
                        logger.warn("Unknown command type: {}, {}, {}, {}", command, deviceId, device.getCategory(),
                                device.getCategoryType());
                    }
                }
            } else {
                logger.warn("Device {} not loaded", deviceId);
            }
        }
    }

    protected synchronized void addDeviceAsChannel(Device device, boolean homekitIntegration) {
        if (device != null) {
            logger.debug("Add device as channel: {}", device.getName());

            HashMap<String, String> properties = new HashMap<>();
            properties.put("deviceId", device.getId());

            String id = null;
            String acceptedItemType = "";
            String tag = null;

            int subcategory = Integer.parseInt(device.getSubcategory());
            switch (device.getCategoryType()) {
                case Controller:
                case Interface:
                    break;
                case DimmableLight:
                    switch (subcategory) {
                        case 1:
                        case 2:
                        case 3:
                            id = SWITCH_MULTILEVEL_CHANNEL;
                            acceptedItemType = "Dimmer";
                            tag = "Lighting";
                            break;
                        case 4:
                            id = SWITCH_COLOR_CHANNEL;
                            acceptedItemType = "Color";
                            tag = "Lighting";
                            break;
                    }
                    break;
                case Switch:
                    id = SWITCH_BINARY_CHANNEL;
                    acceptedItemType = "Switch";
                    tag = "Switchable";
                    break;
                case SecuritySensor:
                    switch (subcategory) {
                        case 1:
                            id = SENSOR_DOOR_WINDOW_CHANNEL;
                            acceptedItemType = "Contact";
                            break;
                        case 2:
                            id = SENSOR_FLOOD_CHANNEL;
                            acceptedItemType = "Switch";
                        case 3:
                            id = SENSOR_MOTION_CHANNEL;
                            acceptedItemType = "Switch";
                            break;
                        case 4:
                            id = SENSOR_SMOKE_CHANNEL;
                            acceptedItemType = "Switch";
                            break;
                        case 5:
                            id = SENSOR_CO_CHANNEL;
                            acceptedItemType = "Switch";
                            break;
                        case 6:
                            id = SENSOR_BINARY_CHANNEL;
                            acceptedItemType = "Switch";
                            break;
                    }
                    break;
                case HVAC: // TODO
                    logger.warn("TODO: {}, {}", device, device.getCategoryType());
                    break;
                case DoorLock:
                    id = DOORLOCK_CHANNEL;
                    acceptedItemType = "Switch";
                    tag = "Switchable";
                    break;
                case WindowCovering:
                    id = SWITCH_ROLLERSHUTTER_CHANNEL;
                    acceptedItemType = "Rollershutter";
                    break;
                case GenericSensor:
                    id = SENSOR_BINARY_CHANNEL;
                    acceptedItemType = "Switch";
                    break;
                case SceneController:
                    id = SWITCH_BINARY_CHANNEL;
                    acceptedItemType = "Switch";
                    break;
                case HumiditySensor:
                    id = SENSOR_HUMIDITY_CHANNEL;
                    acceptedItemType = "Number";
                    tag = "CurrentHumidity";
                    break;
                case TemperatureSensor:
                    id = SENSOR_TEMPERATURE_CHANNEL;
                    acceptedItemType = "Number";
                    tag = "CurrentTemperature";
                    break;
                case LightSensor:
                    id = SENSOR_LUMINOSITY_CHANNEL;
                    acceptedItemType = "Number";
                    break;
                case PowerMeter:
                    id = SENSOR_ENERGY_CHANNEL;
                    acceptedItemType = "Number";
                    break;
                case UVSensor:
                    id = SENSOR_ULTRAVIOLET_CHANNEL;
                    acceptedItemType = "Number";
                    break;
                case Camera:
                case RemoteControl:
                case IRTransmitter:
                case GenericIO:
                case SerialPort:
                case AV:
                case ZWaveInterface:
                case InsteonInterface:
                case AlarmPanel:
                case AlarmPartition:
                case Siren:
                case Weather:
                case PhilipsController:
                case Appliance:
                    logger.warn("TODO: {}, {}", device, device.getCategoryType());
                    break;
                case Unknown:
                    logger.warn("Unknown device type: {}, {}", device, device.getCategory());
                    break;
            }

            if (id != null) {
                addChannel(id, acceptedItemType, device.getName(), properties, homekitIntegration ? tag : null);

                logger.debug("Channel for device added with channel id: {}, accepted item type: {} and title: {}", id,
                        acceptedItemType, device.getName());

                if (device.getBatterylevel() != null && !device.getBatterylevel().isEmpty()) {
                    addChannel(BATTERY_CHANNEL, "Number", "Battery", properties, null);
                }

                if (device.getKwh() != null) {
                    addChannel(SENSOR_METER_KWH_CHANNEL, "Number", "Energy ALL", properties, null);
                }

                if (device.getWatts() != null) {
                    addChannel(SENSOR_METER_W_CHANNEL, "Number", "Energy Current", properties, null);
                }
            } else {
                // Thing status will not be updated because thing could have more than one channel
                logger.warn("No channel for device added: {}", device);
            }
        }
    }

    private synchronized void addChannel(String id, String acceptedItemType, String label,
            HashMap<String, String> properties, String tag) {
        String channelId = id + "-" + properties.get("deviceId");
        boolean channelExists = false;
        // Check if a channel for this device exist.
        List<Channel> channels = getThing().getChannels();
        for (Channel channel : channels) {
            if (channelId.equals(channel.getUID().getId())) {
                channelExists = true;
            }
        }
        if (!channelExists) {
            ThingBuilder thingBuilder = editThing();
            ChannelTypeUID channelTypeUID = new ChannelTypeUID(BINDING_ID, id);
            ChannelBuilder channelBuilder = ChannelBuilder.create(new ChannelUID(getThing().getUID(), channelId),
                    acceptedItemType);
            channelBuilder.withType(channelTypeUID);
            channelBuilder.withLabel(label);
            channelBuilder.withProperties(properties);
            if (tag != null) {
                Set<String> tags = new HashSet<String>();
                tags.add(tag);
                channelBuilder.withDefaultTags(tags);
            }
            thingBuilder.withChannel(channelBuilder.build());
            thingBuilder.withLabel(thing.getLabel());
            updateThing(thingBuilder.build());
        }
    }
}
