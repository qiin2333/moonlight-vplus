package com.limelight.nvstream.http;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ComputerDetails {
    public enum State {
        ONLINE, OFFLINE, UNKNOWN
    }

    public static class AddressTuple {
        public String address;
        public int port;

        public AddressTuple(String address, int port) {
            if (address == null) {
                throw new IllegalArgumentException("Address cannot be null");
            }
            if (port <= 0) {
                throw new IllegalArgumentException("Invalid port");
            }

            // If this was an escaped IPv6 address, remove the brackets
            if (address.startsWith("[") && address.endsWith("]")) {
                address = address.substring(1, address.length() - 1);
            }

            this.address = address;
            this.port = port;
        }

        @Override
        public int hashCode() {
            return Objects.hash(address, port);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof AddressTuple)) return false;

            AddressTuple that = (AddressTuple) obj;
            return port == that.port && address.equals(that.address);
        }

        @Override
        public String toString() {
            return address.contains(":") 
                    ? "[" + address + "]:" + port 
                    : address + ":" + port;
        }
    }

    private static final String ZERO_MAC = "00:00:00:00:00:00";

    // Persistent attributes
    public String uuid;
    public String name;
    public AddressTuple localAddress;
    public AddressTuple remoteAddress;
    public AddressTuple manualAddress;
    public AddressTuple ipv6Address;
    public String macAddress;
    public X509Certificate serverCert;

    // Transient attributes
    public State state;
    public AddressTuple activeAddress;
    public List<AddressTuple> availableAddresses;
    public int httpsPort;
    public int externalPort;
    public PairingManager.PairState pairState;
    public int runningGameId;
    public String rawAppList;
    public boolean nvidiaServer;
    public boolean useVdd;

    public ComputerDetails() {
        state = State.UNKNOWN;
        availableAddresses = new ArrayList<>();
    }

    public ComputerDetails(ComputerDetails details) {
        this();
        update(details);
    }

    public int guessExternalPort() {
        if (externalPort != 0) {
            return externalPort;
        }
        if (remoteAddress != null) {
            return remoteAddress.port;
        }
        if (activeAddress != null) {
            return activeAddress.port;
        }
        if (ipv6Address != null) {
            return ipv6Address.port;
        }
        if (localAddress != null) {
            return localAddress.port;
        }
        return NvHTTP.DEFAULT_HTTP_PORT;
    }

    public void update(ComputerDetails details) {
        this.state = details.state;
        this.name = details.name;
        this.uuid = details.uuid;
        
        if (details.activeAddress != null) {
            this.activeAddress = details.activeAddress;
        }
        // We can get IPv4 loopback addresses with GS IPv6 Forwarder
        if (details.localAddress != null && !details.localAddress.address.startsWith("127.")) {
            this.localAddress = details.localAddress;
        }
        if (details.remoteAddress != null) {
            this.remoteAddress = details.remoteAddress;
        } else if (this.remoteAddress != null && details.externalPort != 0) {
            // Propagate external port to existing remote address
            this.remoteAddress.port = details.externalPort;
        }
        if (details.manualAddress != null) {
            this.manualAddress = details.manualAddress;
        }
        if (details.ipv6Address != null) {
            this.ipv6Address = details.ipv6Address;
        }
        if (details.macAddress != null && !ZERO_MAC.equals(details.macAddress)) {
            this.macAddress = details.macAddress;
        }
        if (details.serverCert != null) {
            this.serverCert = details.serverCert;
        }
        
        this.externalPort = details.externalPort;
        this.httpsPort = details.httpsPort;
        this.pairState = details.pairState;
        this.runningGameId = details.runningGameId;
        this.nvidiaServer = details.nvidiaServer;
        this.useVdd = details.useVdd;
        this.rawAppList = details.rawAppList;
        
        if (details.availableAddresses != null) {
            this.availableAddresses = new ArrayList<>(details.availableAddresses);
        }
    }

    public void addAvailableAddress(AddressTuple address) {
        if (address == null) return;
        
        if (availableAddresses == null) {
            availableAddresses = new ArrayList<>();
        }
        if (!availableAddresses.contains(address)) {
            availableAddresses.add(address);
        }
    }

    public List<AddressTuple> getAvailableAddresses() {
        if (availableAddresses == null) {
            availableAddresses = new ArrayList<>();
        }
        return availableAddresses;
    }

    public boolean hasMultipleAddresses() {
        return availableAddresses != null && availableAddresses.size() > 1;
    }

    public String getAddressTypeDescription(AddressTuple address) {
        if (address == null) return "";
        
        if (address.equals(localAddress)) {
            return "本地网络";
        }
        if (address.equals(remoteAddress)) {
            return "远程网络";
        }
        if (address.equals(manualAddress)) {
            return "手动配置";
        }
        if (address.equals(ipv6Address)) {
            return "IPv6网络";
        }
        return "其他网络";
    }

    public static boolean isLanIpv4Address(AddressTuple address) {
        if (address == null || address.address == null) {
            return false;
        }
        
        try {
            InetAddress inetAddress = InetAddress.getByName(address.address);
            if (!(inetAddress instanceof Inet4Address)) {
                return false;
            }
            
            if (inetAddress.isSiteLocalAddress() || inetAddress.isLinkLocalAddress()) {
                return true;
            }
            
            return isPrivateIpv4(address.address);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isPrivateIpv4(String addr) {
        if (addr.startsWith("10.") || addr.startsWith("192.168.")) {
            return true;
        }
        
        if (addr.startsWith("172.")) {
            String[] parts = addr.split("\\.");
            if (parts.length >= 2) {
                try {
                    int secondOctet = Integer.parseInt(parts[1]);
                    return secondOctet >= 16 && secondOctet <= 31;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }
        return false;
    }

    public static boolean isIpv6Address(AddressTuple address) {
        return address != null && address.address != null && address.address.contains(":");
    }

    public static boolean isPublicAddress(AddressTuple address) {
        if (address == null || address.address == null) {
            return false;
        }
        return !isLanIpv4Address(address) && !isIpv6Address(address);
    }

    public List<AddressTuple> getLanIpv4Addresses() {
        List<AddressTuple> lanAddresses = new ArrayList<>();
        if (availableAddresses == null) {
            return lanAddresses;
        }
        
        for (AddressTuple address : availableAddresses) {
            if (isLanIpv4Address(address)) {
                lanAddresses.add(address);
            }
        }
        return lanAddresses;
    }

    public boolean hasMultipleLanAddresses() {
        return getLanIpv4Addresses().size() > 1;
    }

    public AddressTuple selectBestAddress() {
        if (availableAddresses == null || availableAddresses.isEmpty()) {
            return selectBestAddressFromFields();
        }
        
        // 优先选择LAN IPv4地址
        List<AddressTuple> lanAddresses = getLanIpv4Addresses();
        if (!lanAddresses.isEmpty()) {
            return selectFromLanAddresses(lanAddresses);
        }
        
        // 其次选择IPv6地址
        if (ipv6Address != null && availableAddresses.contains(ipv6Address)) {
            return ipv6Address;
        }
        
        // 最后选择公网地址
        if (remoteAddress != null && availableAddresses.contains(remoteAddress)) {
            return remoteAddress;
        }
        
        return availableAddresses.get(0);
    }

    private AddressTuple selectBestAddressFromFields() {
        if (localAddress != null && isLanIpv4Address(localAddress)) {
            return localAddress;
        }
        if (ipv6Address != null) {
            return ipv6Address;
        }
        if (remoteAddress != null) {
            return remoteAddress;
        }
        return localAddress;
    }

    private AddressTuple selectFromLanAddresses(List<AddressTuple> lanAddresses) {
        if (localAddress != null && lanAddresses.contains(localAddress)) {
            return localAddress;
        }
        if (manualAddress != null && lanAddresses.contains(manualAddress)) {
            return manualAddress;
        }
        return lanAddresses.get(0);
    }

    public String getPairName(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("pair_name_map", Context.MODE_PRIVATE);
        return prefs.getString(uuid, "");
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        str.append("Name: ").append(name).append("\n");
        str.append("State: ").append(state).append("\n");
        str.append("Active Address: ").append(activeAddress).append("\n");
        str.append("UUID: ").append(uuid).append("\n");
        str.append("Local Address: ").append(localAddress).append("\n");
        str.append("Remote Address: ").append(remoteAddress).append("\n");
        str.append("IPv6 Address: ").append(ipv6Address).append("\n");
        str.append("Manual Address: ").append(manualAddress).append("\n");
        str.append("MAC Address: ").append(macAddress).append("\n");
        str.append("Pair State: ").append(pairState).append("\n");
        str.append("Running Game ID: ").append(runningGameId).append("\n");
        str.append("HTTPS Port: ").append(httpsPort).append("\n");
        return str.toString();
    }
}
