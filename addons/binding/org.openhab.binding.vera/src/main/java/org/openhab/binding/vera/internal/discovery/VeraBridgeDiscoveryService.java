/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.vera.internal.discovery;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.util.Enumeration;
import java.util.regex.Pattern;

import org.apache.commons.net.util.SubnetUtils;
import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.config.discovery.DiscoveryServiceCallback;
import org.eclipse.smarthome.config.discovery.ExtendedDiscoveryService;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.openhab.binding.vera.VeraBindingConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link VeraBridgeDiscoveryService} is responsible for device discovery.
 *
 * @author Dmitriy Ponomarev
 */
public class VeraBridgeDiscoveryService extends AbstractDiscoveryService implements ExtendedDiscoveryService {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private static final int SEARCH_TIME = 240;

    private DiscoveryServiceCallback callback;

    public VeraBridgeDiscoveryService() {
        super(VeraBindingConstants.SUPPORTED_DEVICE_THING_TYPES_UIDS, SEARCH_TIME);
        logger.debug("Initializing VeraBridgeDiscoveryService");
    }

    private void scan() {
        logger.debug("Starting scan for Vera controller");

        ValidateIPV4 validator = new ValidateIPV4();

        try {
            Enumeration<NetworkInterface> enumNetworkInterface = NetworkInterface.getNetworkInterfaces();
            while (enumNetworkInterface.hasMoreElements()) {
                NetworkInterface networkInterface = enumNetworkInterface.nextElement();
                if (networkInterface.isUp() && !networkInterface.isVirtual() && !networkInterface.isLoopback()) {
                    for (InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
                        if (validator.isValidIPV4(address.getAddress().getHostAddress())) {
                            String ipAddress = address.getAddress().getHostAddress();
                            Short prefix = address.getNetworkPrefixLength();

                            logger.debug("Scan IP address for Vera Controller: {}", ipAddress);

                            String subnet = ipAddress + "/" + prefix;
                            SubnetUtils utils = new SubnetUtils(subnet);
                            String[] addresses = utils.getInfo().getAllAddresses();

                            for (String addressInSubnet : addresses) {
                                scheduler.execute(new VeraControllerScan(addressInSubnet));
                            }
                        }
                    }
                }
            }
        } catch (SocketException e) {
            logger.warn("Error occurred while searching Vera controller: ", e);
        }
    }

    public boolean pingHost(String host, int port, int timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            return true;
        } catch (IOException e) {
            return false; // Either timeout or unreachable or failed DNS lookup.
        }
    }

    public class VeraControllerScan implements Runnable {
        private String ipAddress;

        public VeraControllerScan(String ipAddress) {
            this.ipAddress = ipAddress;
        }

        @Override
        public void run() {
            if (!pingHost(ipAddress, 3480, 100)) {
                return;
            }

            try {
                URL url = new URL("http://" + ipAddress + ":3480/data_request?id=sdata&output_format=json");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                if (connection.getResponseCode() == 200) {
                    ThingUID thingUID = new ThingUID(VeraBindingConstants.THING_TYPE_BRIDGE,
                            ipAddress.replaceAll("\\.", "_"));

                    if (callback != null && callback.getExistingDiscoveryResult(thingUID) == null
                            && callback.getExistingThing(thingUID) == null) {
                        DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(thingUID)
                                .withProperty(VeraBindingConstants.BRIDGE_CONFIG_VERA_SERVER_IP_ADDRESS, ipAddress)
                                .withLabel("Vera controller " + ipAddress).build();
                        thingDiscovered(discoveryResult);
                    } else {
                        logger.debug("Thing or inbox entry already exists for UID={}, IP={}", thingUID, ipAddress);
                    }
                }
            } catch (IOException e) {
                logger.warn("Discovery resulted in an unexpected exception: ", e);
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

    class ValidateIPV4 {
        private final String ipV4Regex = "^((0|1\\d?\\d?|2[0-4]?\\d?|25[0-5]?|[3-9]\\d?)\\.){3}(0|1\\d?\\d?|2[0-4]?\\d?|25[0-5]?|[3-9]\\d?)$";
        private Pattern ipV4Pattern = Pattern.compile(ipV4Regex);

        public boolean isValidIPV4(final String s) {
            return ipV4Pattern.matcher(s).matches();
        }
    }

    @Override
    public void setDiscoveryServiceCallback(DiscoveryServiceCallback discoveryServiceCallback) {
        callback = discoveryServiceCallback;
    }
}
