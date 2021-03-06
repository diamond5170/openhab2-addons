/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.vera.internal.discovery;

import static org.openhab.binding.vera.VeraBindingConstants.*;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.config.discovery.DiscoveryServiceCallback;
import org.eclipse.smarthome.config.discovery.ExtendedDiscoveryService;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.openhab.binding.vera.controller.CategoryType;
import org.openhab.binding.vera.controller.json.Device;
import org.openhab.binding.vera.controller.json.Scene;
import org.openhab.binding.vera.handler.VeraBridgeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link VeraDeviceDiscoveryService} is responsible for device discovery.
 *
 * @author Dmitriy Ponomarev
 */
public class VeraDeviceDiscoveryService extends AbstractDiscoveryService implements ExtendedDiscoveryService {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private static final int SEARCH_TIME = 60;
    private static final int INITIAL_DELAY = 15;
    private static final int SCAN_INTERVAL = 240;

    private VeraBridgeHandler mBridgeHandler;
    private VeraDeviceScan mVeraDeviceScanningRunnable;
    private ScheduledFuture<?> mVeraDeviceScanningJob;

    private DiscoveryServiceCallback callback;

    public VeraDeviceDiscoveryService(VeraBridgeHandler bridgeHandler) {
        super(SUPPORTED_DEVICE_THING_TYPES_UIDS, SEARCH_TIME);
        logger.debug("Initializing VeraDeviceDiscoveryService");
        mBridgeHandler = bridgeHandler;
        mVeraDeviceScanningRunnable = new VeraDeviceScan();
        activate(null);
    }

    private void scan() {
        logger.debug("Starting scan on Vera Controller {}", mBridgeHandler.getThing().getUID());

        if (mBridgeHandler == null || !mBridgeHandler.getThing().getStatus().equals(ThingStatus.ONLINE)) {
            logger.debug("Vera bridge handler not found or not ONLINE.");
            return;
        }

        final ThingUID bridgeUID = mBridgeHandler.getThing().getUID();

        List<Device> deviceList = mBridgeHandler.getData().getDevices();
        for (Device device : deviceList) {
            if (device.getCategoryType().equals(CategoryType.Controller)
                    || device.getCategoryType().equals(CategoryType.Interface)) {
                continue;
            }
            ThingUID thingUID = new ThingUID(THING_TYPE_DEVICE, mBridgeHandler.getThing().getUID(), device.getId());
            if (callback != null && callback.getExistingDiscoveryResult(thingUID) == null
                    && callback.getExistingThing(thingUID) == null) {
                DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(thingUID).withLabel(device.getName())
                        .withBridge(bridgeUID).withProperty(DEVICE_CONFIG_ID, device.getId())
                        .withProperty(DEVICE_PROP_CATEGORY, device.getCategory())
                        .withProperty(DEVICE_PROP_SUBCATEGORY, device.getSubcategory()).build();
                thingDiscovered(discoveryResult);
                logger.debug("Vera device found: {}, {}", device.getId(), device.getName());
            }
        }

        List<Scene> sceneList = mBridgeHandler.getData().getScenes();
        for (Scene scene : sceneList) {
            ThingUID thingUID = new ThingUID(THING_TYPE_SCENE, mBridgeHandler.getThing().getUID(), scene.getId());
            if (callback != null && callback.getExistingDiscoveryResult(thingUID) == null
                    && callback.getExistingThing(thingUID) == null) {
                DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(thingUID).withLabel(scene.getName())
                        .withBridge(bridgeUID).withProperty(SCENE_CONFIG_ID, scene.getId()).build();
                thingDiscovered(discoveryResult);
                logger.debug("Vera scene found: {}, {}", scene.getId(), scene.getName());
            }
        }
    }

    @Override
    protected void startScan() {
        scan();
    }

    @Override
    protected synchronized void stopScan() {
        super.stopScan();
        removeOlderResults(getTimestampOfLastScan());
    }

    @Override
    protected void startBackgroundDiscovery() {
        if (mVeraDeviceScanningJob == null || mVeraDeviceScanningJob.isCancelled()) {
            logger.debug("Starting background scanning job");
            mVeraDeviceScanningJob = AbstractDiscoveryService.scheduler.scheduleWithFixedDelay(
                    mVeraDeviceScanningRunnable, INITIAL_DELAY, SCAN_INTERVAL, TimeUnit.SECONDS);
        } else {
            logger.debug("Scanning job is allready active");
        }
    }

    @Override
    protected void stopBackgroundDiscovery() {
        if (mVeraDeviceScanningJob != null && !mVeraDeviceScanningJob.isCancelled()) {
            mVeraDeviceScanningJob.cancel(false);
            mVeraDeviceScanningJob = null;
        }
    }

    public class VeraDeviceScan implements Runnable {
        @Override
        public void run() {
            scan();
        }
    }

    @Override
    public void setDiscoveryServiceCallback(DiscoveryServiceCallback discoveryServiceCallback) {
        callback = discoveryServiceCallback;
    }
}
