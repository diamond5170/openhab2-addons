/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.vera2.internal;

import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.Set;

import org.eclipse.smarthome.config.discovery.DiscoveryService;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandlerFactory;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.openhab.binding.vera2.handler.VeraBridgeHandler;
import org.openhab.binding.vera2.handler.VeraDeviceHandler;
import org.openhab.binding.vera2.internal.discovery.VeraDeviceDiscoveryService;
import org.osgi.framework.ServiceRegistration;

import com.google.common.collect.ImmutableSet;

/**
 * The {@link VeraHandlerFactory} is responsible for creating things and thing
 * handlers.
 *
 * @author Dmitriy Ponomarev
 */
public class VeraHandlerFactory extends BaseThingHandlerFactory {

    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = ImmutableSet
            .of(VeraBridgeHandler.SUPPORTED_THING_TYPE, VeraDeviceHandler.SUPPORTED_THING_TYPE);

    private Map<ThingUID, ServiceRegistration<?>> discoveryServiceRegs = new HashMap<>();

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected ThingHandler createHandler(Thing thing) {
        if (VeraBridgeHandler.SUPPORTED_THING_TYPE.equals(thing.getThingTypeUID())) {
            VeraBridgeHandler handler = new VeraBridgeHandler((Bridge) thing);
            registerDeviceDiscoveryService(handler);
            return handler;
        } else if (VeraDeviceHandler.SUPPORTED_THING_TYPE.equals(thing.getThingTypeUID())) {
            return new VeraDeviceHandler(thing);
        } else {
            return null;
        }
    }

    @Override
    protected synchronized void removeHandler(ThingHandler thingHandler) {
        if (thingHandler instanceof VeraBridgeHandler) {
            ServiceRegistration<?> serviceReg = this.discoveryServiceRegs.get(thingHandler.getThing().getUID());
            if (serviceReg != null) {
                serviceReg.unregister();
                discoveryServiceRegs.remove(thingHandler.getThing().getUID());
            }
        }
    }

    private void registerDeviceDiscoveryService(VeraBridgeHandler handler) {
        VeraDeviceDiscoveryService discoveryService = new VeraDeviceDiscoveryService(handler);
        this.discoveryServiceRegs.put(handler.getThing().getUID(), bundleContext
                .registerService(DiscoveryService.class.getName(), discoveryService, new Hashtable<String, Object>()));
    }
}
